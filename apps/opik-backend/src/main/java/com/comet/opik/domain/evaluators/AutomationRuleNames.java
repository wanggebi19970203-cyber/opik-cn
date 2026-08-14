package com.comet.opik.domain.evaluators;

import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.text.Normalizer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 在名称冲突时通过追加数字后缀来解析唯一的自动化规则名称（OPIK-7371）。
 * <p>
 * 规则名称在数据库层并未强制唯一（现有安装中已存在冲突）。因此，当请求的名称在同一作用域内已存在时，
 * 我们会自动追加 {@code -1}、{@code -2}、……，从而使规则在 UI 中保持可区分，同时不破坏现有数据。
 */
@UtilityClass
class AutomationRuleNames {

    // 匹配 automation_rules.name VARCHAR(150)。
    private static final int MAX_NAME_LENGTH = 150;

    // NFD 分解后遗留的组合标记。仅编译一次：每次保存时 canonicalKey 都会对每个候选名运行，
    // 因此内联的 replaceAll 会在每次比较时重新编译该模式。
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}");

    // truncateToFit 可追加的最长后缀："-" + Integer.MAX_VALUE (2147483647) = 11 个字符，再加 1 个字符
    // 用于其代理对回退，这会再缩短候选名的基础部分一个字符。LIKE 搜索前缀必须限制在
    // MAX_NAME_LENGTH 减去该预留量以内，否则一个为腾出空间而截断基础部分的后缀候选名将不再共享该前缀，
    // 重跑时会重新生成其名称（从而存储重复项）。
    private static final int MAX_PREFIX_LENGTH = MAX_NAME_LENGTH - 12;

    /**
     * 如果 {@code requestedName} 在 {@code existingNames} 中空闲，则原样返回该名称；否则返回同一个名称
     * 加上最小的可用 {@code -N} 后缀（从 1 开始）。基础名称计作索引 0，因此第一次冲突会得到
     * {@code name-1}。
     * <p>
     * 比较对大小写和重音不敏感，以近似 MySQL 的 {@code utf8mb4_unicode_ci} 排序规则
     * （因此 {@code Hallucination}/{@code hallucination} 以及 {@code Café}/{@code Cafe} 都会冲突），
     * 并且每个候选名都会按实际（可能被截断的）将被存储的字符串进行检查，从而使得接近 150 字符列
     * 上限的名称仍能解析为互不相同的值。
     *
     * @param requestedName 用户请求的名称
     * @param existingNames 目标作用域中已存在的名称
     */
    static String generateUniqueName(String requestedName, Collection<String> existingNames) {
        if (StringUtils.isBlank(requestedName) || CollectionUtils.isEmpty(existingNames)) {
            return requestedName;
        }

        Set<String> taken = new HashSet<>();
        for (String name : existingNames) {
            if (name != null) {
                taken.add(canonicalKey(name));
            }
        }

        if (!taken.contains(canonicalKey(requestedName))) {
            return requestedName;
        }

        // 依次探测后缀并返回第一个确实空闲的候选名。检查最终字符串（经过任何截断之后）
        // 可以保证我们绝不会重新生成一个已存在的名称。
        for (int suffix = 1;; suffix++) {
            String candidate = truncateToFit(requestedName, suffix);
            if (!taken.contains(canonicalKey(candidate))) {
                return candidate;
            }
        }
    }

    /**
     * 构建转义后的前缀，用于 {@link AutomationRuleDAO#findCandidateNames} 中的
     * {@code name LIKE concat(?, '%') ESCAPE '!'} 查找。末尾空格会被去除（MySQL 的 PAD SPACE 比较会
     * 忽略它们），LIKE 元字符会被转义，从而按字面量匹配而不是作为通配符——否则一个名为 {@code 50%} 的
     * 规则会匹配项目中的每个名称。
     * <p>
     * 转义字符使用 {@code !} 而不是常规的 {@code \}：该查询是 Java 文本块，在到达 MySQL 之前要经过
     * StringTemplate 渲染，而这三层中的每一层都把反斜杠当作转义符，因此在源码中声明
     * {@code ESCAPE '\'} 需要八个反斜杠，并且只要任何一层发生变化就会悄然退化为语法错误。{@code !}
     * 在这三层中都是惰性的。
     * <p>
     * 由于前缀是作为绑定参数传入（绝不会被插值进语句中），规则名称中的反斜杠在此无需处理——它会作为
     * 字面字符到达 MySQL。大小写/重音折叠交给列排序规则处理。最终的精确匹配在返回的候选集上由
     * {@link #generateUniqueName} 完成。
     * <p>
     * 对于长于 {@link #MAX_PREFIX_LENGTH} 的名称，前缀会被截断到该长度：为这样的名称追加后缀会截断其
     * 基础部分以适配列宽，因此先前存储的 {@code name-1} 不再以完整的请求名称开头，而全长的前缀会漏掉
     * 它——导致下次运行时重新生成同名。较短的前缀会取到稍宽的候选集；{@link #generateUniqueName} 中的
     * 精确匹配会丢弃多余项。
     */
    static String likePrefix(String name) {
        if (name == null) {
            return null;
        }
        String stripped = StringUtils.stripEnd(name, " ");
        if (stripped.length() > MAX_PREFIX_LENGTH) {
            int cut = MAX_PREFIX_LENGTH;
            // 避免拆分代理对，否则会在 LIKE 模式中留下一个孤立的代理字符。
            if (Character.isHighSurrogate(stripped.charAt(cut - 1)) && Character.isLowSurrogate(stripped.charAt(cut))) {
                cut--;
            }
            stripped = stripped.substring(0, cut);
        }
        // 转义字符本身必须最先转义，否则会把它后面的内容双重转义。
        return stripped
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    /**
     * 对名称进行规范化以便进行冲突比较，近似 MySQL {@code utf8mb4_unicode_ci} 排序规则的折叠行为：
     * 去除末尾空格（PAD SPACE 比较会忽略它们）、去除变音符号并转为小写。与
     * {@link com.comet.opik.domain.SlugUtils} 使用的规范化保持一致。注意：特殊扩展
     * （{@code ß}->{@code ss}）以及安装特定的排序规则（例如 {@code utf8mb4_0900_ai_ci}）并未精确复现。
     */
    private static String canonicalKey(String name) {
        // MySQL PAD SPACE 排序规则将末尾空格视为无意义。
        String trimmed = StringUtils.stripEnd(name, " ");
        String normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFD);
        return DIACRITICS.matcher(normalized).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static String truncateToFit(String baseName, int suffix) {
        String suffixStr = "-" + suffix;
        if (baseName.length() + suffixStr.length() <= MAX_NAME_LENGTH) {
            return baseName + suffixStr;
        }
        int cut = MAX_NAME_LENGTH - suffixStr.length();
        // 当截断点落在某个非 BMP 字符中间时，避免拆分代理对。
        if (Character.isHighSurrogate(baseName.charAt(cut - 1)) && Character.isLowSurrogate(baseName.charAt(cut))) {
            cut--;
        }
        return baseName.substring(0, cut) + suffixStr;
    }
}
