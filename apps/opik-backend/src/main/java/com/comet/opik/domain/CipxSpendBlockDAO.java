package com.comet.opik.domain;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metrics.ServerMetrics;
import com.clickhouse.data.ClickHouseFormat;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Lists;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 写入 cipx_spend_blocks 表：每个 cipx block 一行，其 token 分配和仪表盘
 * 分组在摄取时于 Java 中派生（{@link BlockRow#from}），因此检索时无需重新派生。
 * 有两种行，通过 {@code src} 区分：'a'（归属行，每个真实 block 一行）和
 * 'r'（残差行——已计费但没有任何 block 可吸收的层级，显示为 'unattributed'）。
 *
 * <p>分配将 span 的四个已计费用量计数器分别拆分到 span 中匹配层级的 block 上，
 * 按字符数成比例：{@code alloc = chars * u_tier / tier_chars}。每个输入
 * 都来自单个 span 载荷，因此整个派生过程只是对 span 的 block 做一次遍历。
 *
 * <p>block_idx 使 ReplacingMergeTree 的排序键每行保持唯一，并且是确定性的（归属行为
 * cipx.blocks[] 中的原始位置，残差行为 65531 + 层级序号），因此重放
 * 的插入会产生相同的键并去重，而不是重复。普通 INSERT，契约与
 * {@link CipxSpendDAO} 相同：只创建摄取，project_id 必须非空，last_updated_at 留给
 * 列 DEFAULT now64(6)。
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class CipxSpendBlockDAO {

    /** 按残差 block_idx 序号排序的层级名称。cache_creation 条目（序号 2）是一个
     * 占位符：其实际写入的标签是每个 span 的 TTL 变体（cache_creation_5m / cache_creation_1h），
     * 由 {@link BlockRow#tierName(int, String)} 解析。 */
    private static final String[] TIER_NAMES = {"input", "cache_read", "cache_creation", "output"};
    private static final int RESIDUAL_IDX_BASE = 65531;
    private static final int CACHE_CREATION_TIER = 2;
    /** 每个批量插入块的行数；将 JSON 载荷限制在约 35MB（约 650 字节/行）。 */
    private static final int INSERT_CHUNK_SIZE = 50_000;
    private static final String SRC_ATTRIBUTED = "a";
    private static final String SRC_RESIDUAL = "r";

    /** 一个 cipx_spend_blocks 行：原始 block 字段加上摄取派生的列。 */
    @Builder(toBuilder = true)
    public record BlockRow(
            @NonNull String spanId,
            @NonNull String traceId,
            @NonNull String projectId,
            @NonNull Instant startTime,
            @NonNull String model,
            /** 每次调用的速度修饰符；选择为该调用定价的费率表。被携带在
             * 每个 block 上，因为 block 级别的成本需要为其定价的那个值。'' = 标准，
             * 包括该字段存在之前写入的每一行。 */
            @NonNull String speed,
            int blockIdx,
            @NonNull String src,
            @NonNull String category,
            @NonNull String side,
            @NonNull String cacheStatus,
            @NonNull String parentCategory,
            long chars,
            @NonNull String toolName,
            @NonNull String toolServer,
            @NonNull String toolUseId,
            @NonNull String resource,
            @NonNull String kind,
            /** 该 block 是 `category` 的哪个变体（memory：auto_memory vs project_instructions
             * vs rule vs user_global）。'' = 未知，包括 cipx 发出该字段之前写入的每一个 block
             * ——消费者必须将其视为“无法判断”，而不是一个默认值。 */
            @NonNull String subcategory,
            @NonNull String tier,
            @NonNull String lane,
            @NonNull String bdLane,
            @NonNull String label,
            int isDefinition,
            double alloc,
            @NonNull String contentSha256) {

        /**
         * 为一个 cipx span 派生所有行：每个非 identity block 一个归属行（保留
         * 该 block 的原始数组位置作为 block_idx），外加为 span 上已计费但其中没有 block 的
         * 每个层级生成一个残差行。与派生之前的查询保持对等规则：blocks 字符数
         * 之和为零的层级分配 0 且不产生残差行（该层级是存在的）；其
         * (side, cache_status) 映射不到任何层级的 block 仍会得到 alloc 为 0 的行（拆分会统计它们）。
         */
        public static List<BlockRow> from(UUID spanId, UUID traceId, UUID projectId, JsonNode metadata,
                Instant startTime) {
            JsonNode call = metadata.path("cipx").path("call");
            JsonNode usage = call.path("usage");
            JsonNode config = call.path("config");
            String model = call.path("model").asText("");
            long[] tierTokens = {
                    usage.path("input_tokens").asLong(0),
                    usage.path("cache_read_input_tokens").asLong(0),
                    usage.path("cache_creation_input_tokens").asLong(0),
                    usage.path("output_tokens").asLong(0),
            };
            // 一个 cipx span 是一次单独的 LLM 调用，Claude Code 会用同一个 TTL 写入一次调用的所有缓存断点，
            // 因此 span 上的每个 write block 都继承 span 的 TTL。从用量拆分中选取它；
            // 当拆分缺失（旧版 / 未上报）时回退到 1h，因为 CC 的
            // 缓存写入绝大多数是 1h（OPIK-7392）。
            JsonNode cacheCreation = usage.path("cache_creation");
            String writeTier = cacheCreation.path("ephemeral_5m_input_tokens").asLong(0) > 0
                    && cacheCreation.path("ephemeral_1h_input_tokens").asLong(0) == 0
                            ? "cache_creation_5m"
                            : "cache_creation_1h";

            JsonNode blocks = metadata.path("cipx").path("blocks");
            long[] tierChars = new long[TIER_NAMES.length];
            boolean[] tierPresent = new boolean[TIER_NAMES.length];
            if (blocks.isArray()) {
                for (JsonNode block : blocks) {
                    if (isIdentityContext(block)) {
                        continue;
                    }
                    int tier = tierOrdinal(block.path("side").asText(""), block.path("cache_status").asText(""));
                    if (tier >= 0) {
                        tierChars[tier] += block.path("chars").asLong(0);
                        tierPresent[tier] = true;
                    }
                }
            }

            List<BlockRow> rows = new ArrayList<>();
            var base = BlockRow.builder()
                    .spanId(spanId.toString())
                    .traceId(traceId.toString())
                    .projectId(projectId != null ? projectId.toString() : "")
                    .startTime(startTime)
                    .model(model)
                    .speed(config.path("speed").asText(""));
            if (blocks.isArray()) {
                for (int idx = 0; idx < blocks.size(); idx++) {
                    JsonNode block = blocks.get(idx);
                    if (isIdentityContext(block)) {
                        continue;
                    }
                    rows.add(attributed(base, idx, block, tierTokens, tierChars, writeTier));
                }
            }
            for (int tier = 0; tier < TIER_NAMES.length; tier++) {
                if (!tierPresent[tier] && tierTokens[tier] > 0) {
                    rows.add(residual(base, tier, tierTokens[tier], writeTier));
                }
            }
            return rows;
        }

        /** cache_creation（序号 2）写为其每个 span 的 TTL 变体；其余则是固定的。 */
        private static String tierName(int tier, String writeTier) {
            return tier == CACHE_CREATION_TIER ? writeTier : TIER_NAMES[tier];
        }

        private static BlockRow attributed(BlockRowBuilder base, int idx, JsonNode block, long[] tierTokens,
                long[] tierChars, String writeTier) {
            String category = block.path("category").asText("");
            String side = block.path("side").asText("");
            String cacheStatus = block.path("cache_status").asText("");
            String toolName = block.path("tool_name").asText("");
            String toolServer = block.path("tool_server").asText("");
            String resource = block.path("resource").asText("");
            String kind = block.path("kind").asText("");
            long chars = block.path("chars").asLong(0);

            int tier = tierOrdinal(side, cacheStatus);
            double alloc = tier >= 0 && tierChars[tier] > 0
                    ? chars * (double) tierTokens[tier] / tierChars[tier]
                    : 0;
            return base
                    .blockIdx(idx)
                    .src(SRC_ATTRIBUTED)
                    .category(category)
                    .side(side)
                    .cacheStatus(cacheStatus)
                    .parentCategory(block.path("parent_category").asText(""))
                    .chars(chars)
                    .toolName(toolName)
                    .toolServer(toolServer)
                    .toolUseId(block.path("tool_use_id").asText(""))
                    .resource(resource)
                    .kind(kind)
                    .subcategory(block.path("subcategory").asText(""))
                    .tier(tier >= 0 ? tierName(tier, writeTier) : "")
                    .lane(lane(category, toolServer))
                    .bdLane(bdLane(category, toolServer))
                    .label(label(category, toolServer, toolName, resource, kind, chars))
                    .isDefinition(isDefinition(category))
                    .alloc(alloc)
                    .contentSha256(block.path("sha256").asText(""))
                    .build();
        }

        private static BlockRow residual(BlockRowBuilder base, int tier, long tokens, String writeTier) {
            return base
                    .blockIdx(RESIDUAL_IDX_BASE + tier)
                    .src(SRC_RESIDUAL)
                    .category("")
                    .side("")
                    .cacheStatus("")
                    .parentCategory("")
                    .chars(0)
                    .toolName("")
                    .toolServer("")
                    .toolUseId("")
                    .resource("")
                    .kind("")
                    .subcategory("")
                    .tier(tierName(tier, writeTier))
                    .lane("unattributed")
                    .bdLane("")
                    .label("")
                    .isDefinition(0)
                    .alloc(tokens)
                    .contentSha256("")
                    .build();
        }

        private static boolean isIdentityContext(JsonNode block) {
            return "identity_context".equals(block.path("category").asText())
                    && "identity_context".equals(block.path("parent_category").asText());
        }

        private static int tierOrdinal(String side, String cacheStatus) {
            if ("output".equals(side)) {
                return 3;
            }
            if ("input".equals(side)) {
                return switch (cacheStatus) {
                    case "fresh" -> 0;
                    case "read" -> 1;
                    case "write" -> 2;
                    default -> -1;
                };
            }
            return -1;
        }

        /** 组合通道：每个 category 都映射到某处；未知 category 落入 'unattributed'。 */
        private static String lane(String category, String toolServer) {
            return switch (category) {
                case "tool_io" -> toolServer.isEmpty() ? "built_in_tools" : "mcp_servers";
                case "system_tools", "system_tools_deferred" -> "built_in_tools";
                case "user_prompts" -> "user_prompts";
                case "prior_assistant" -> "prior_assistant";
                case "mcp_tools_active", "mcp_tools_deferred", "mcp_server_instructions" -> "mcp_servers";
                case "skills_menu", "skills_loaded" -> "skills";
                case "custom_agents" -> "custom_agents";
                case "memory" -> "memory";
                case "file_attachments" -> "file_attachments";
                case "system_prompt", "env_info" -> "static_overhead";
                case "auto_classifier", "agent_overhead" -> "static_overhead";
                case "thinking" -> "thinking";
                case "assistant_text" -> "assistant_text";
                case "built_in_tool_calls" -> "built_in_tool_calls";
                case "mcp_tool_calls" -> "mcp_tool_calls";
                case "skill_invocations" -> "skill_invocations";
                default -> "unattributed";
            };
        }

        /**
         * 拆分通道：类似 {@link #lane}，但没有拆分行的 category 映射到 ''（被排除）。
         * 委托 {@link #lane} 处理共享的分派表，只覆盖两处
         * 有意为之的差异，这样两张表就不会在将来新增 category 时发生漂移。
         */
        private static String bdLane(String category, String toolServer) {
            if (category.equals("mcp_tools_deferred") || category.equals("mcp_server_instructions")) {
                return "";
            }
            String baseLane = lane(category, toolServer);
            return baseLane.equals("unattributed") ? "" : baseLane;
        }

        /** 拆分行的键；行依赖哪些原始字段名取决于 category。 */
        private static String label(String category, String toolServer, String toolName, String resource,
                String kind, long chars) {
            return switch (category) {
                case "user_prompts" ->
                    chars < 1_000 ? "small" : chars < 10_000 ? "medium" : chars < 100_000 ? "large" : "xlarge";
                case "file_attachments", "skills_menu", "skills_loaded", "custom_agents", "memory",
                        "skill_invocations" ->
                    resource;
                case "tool_io" -> toolServer.isEmpty() ? toolName : toolServer;
                case "system_tools", "system_tools_deferred" -> toolName.isEmpty() ? "(unattributed)" : toolName;
                case "prior_assistant" -> kind;
                case "mcp_tools_active", "mcp_tool_calls" -> toolServer;
                case "system_prompt", "env_info", "auto_classifier", "agent_overhead" -> category;
                case "thinking" -> "thinking";
                case "assistant_text" -> "assistant_text";
                case "built_in_tool_calls" -> toolName;
                default -> "";
            };
        }

        /** 1 = 承载该事物的成本（schema、菜单、常驻上下文）；0 = 使用它的成本。 */
        private static int isDefinition(String category) {
            return switch (category) {
                case "skills_menu", "custom_agents", "memory", "mcp_tools_active",
                        "system_prompt", "env_info", "system_tools", "system_tools_deferred" ->
                    1;
                default -> 0;
            };
        }
    }

    private final @NonNull Client clickHouseClient;

    /**
     * 通过 ClickHouse v2 HTTP 客户端使用 JSONEachRow 进行批量插入，而不是走同类 cipx DAO
     * 使用的 R2DBC 语句路径。一个 span 事件会扇出成数百个 block 行（约 350 行/span，因此
     * 一个 200 span 的批次约为 70k 行 × 26 列），而 R2DBC 驱动程序对每个命名绑定都用
     * 语句参数列表的线性扫描来解析——约 170 万个参数上是 O(n^2)，单个事件就要
     * 数小时的 CPU（参见 ExperimentAggregatesDAO.insertExperimentItems 中的同一权衡）。
     * JSONEachRow 载荷是一个 HTTP 主体，完全没有按参数的额外工作。
     *
     * <p>last_updated_at 从载荷中省略，因此列 DEFAULT now64(6) 会生效
     * （input_format_defaults_for_omitted_fields 默认开启）。完全非阻塞：客户端
     * 以 useAsyncRequests(true) 构建（参见 DatabaseAnalyticsFactory.buildClient），因此返回的
     * future 在 v2 客户端自己的执行器上运行 HTTP 往返——不借用任何共享调度器
     * （boundedElastic 或其他）来做 I/O。
     *
     * <p>行按顺序分块插入，因此每个事件的峰值载荷分配以
     * 一个块为上限，而与传入 span 批次大小（由客户端控制）无关：一个
     * 1000 span 的批次会扇出约 350k 个 block 行，若作为单个 JSON 主体会瞬时
     * 分配约 1GB（UTF-16 builder + String 复制 + UTF-8 字节）。
     */
    public Mono<Long> insert(@NonNull List<BlockRow> rows, @NonNull String workspaceId, @NonNull String userName) {
        if (rows.isEmpty()) {
            return Mono.just(0L);
        }
        return Flux.fromIterable(Lists.partition(rows, INSERT_CHUNK_SIZE))
                .concatMap(chunk -> insertChunk(chunk, workspaceId, userName))
                .reduce(0L, Long::sum);
    }

    private Mono<Long> insertChunk(List<BlockRow> rows, String workspaceId, String userName) {
        return Mono.fromFuture(() -> {
            StringBuilder body = new StringBuilder();
            for (BlockRow row : rows) {
                appendJsonRow(body, workspaceId, row);
            }
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            String logComment = "insert_cipx_spend_blocks:%s:%s:%d".formatted(workspaceId, userName, rows.size());
            var settings = new InsertSettings()
                    .logComment(logComment)
                    .serverSetting("date_time_input_format", "best_effort");

            return clickHouseClient.insert(
                    "cipx_spend_blocks",
                    new ByteArrayInputStream(payload),
                    ClickHouseFormat.JSONEachRow,
                    settings);
        }).map(response -> {
            try (response) {
                return response.getMetrics().getMetric(ServerMetrics.NUM_ROWS_WRITTEN).getLong();
            }
        });
    }

    private void appendJsonRow(StringBuilder out, String workspaceId, BlockRow row) {
        var node = JsonUtils.createObjectNode();
        node.put("workspace_id", workspaceId);
        node.put("project_id", row.projectId());
        node.put("trace_id", row.traceId());
        node.put("span_id", row.spanId());
        node.put("block_idx", row.blockIdx());
        node.put("model", row.model());
        node.put("speed", row.speed());
        node.put("src", row.src());
        node.put("category", row.category());
        node.put("side", row.side());
        node.put("cache_status", row.cacheStatus());
        node.put("parent_category", row.parentCategory());
        node.put("chars", row.chars());
        node.put("tool_name", row.toolName());
        node.put("tool_server", row.toolServer());
        node.put("tool_use_id", row.toolUseId());
        node.put("resource", row.resource());
        node.put("kind", row.kind());
        node.put("subcategory", row.subcategory());
        node.put("tier", row.tier());
        node.put("lane", row.lane());
        node.put("bd_lane", row.bdLane());
        node.put("label", row.label());
        node.put("is_definition", row.isDefinition());
        node.put("alloc", row.alloc());
        node.put("content_sha256", row.contentSha256());
        node.put("start_time", ClickHouseDateTimeFormat.formatNanos(row.startTime()));
        out.append(node).append('\n');
    }
}
