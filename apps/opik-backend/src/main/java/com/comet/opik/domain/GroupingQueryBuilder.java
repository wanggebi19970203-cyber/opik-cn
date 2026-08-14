package com.comet.opik.domain;

import com.comet.opik.api.grouping.GroupBy;
import com.comet.opik.domain.filter.JsonPathUtils;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.ST;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Singleton
@Slf4j
public class GroupingQueryBuilder {

    /**
     * 用于校验 JSON 路径键的正则表达式。
     *
     * 校验满足以下条件的 JSON 路径：
     * - 以 '$' 开头
     * - 后面可以跟：
     *   - 对象键的点号表示法（例如 .key、.key_name）
     *   - 数组索引（例如 [0]、[123]）
     *   - 字符串键的方括号表示法（例如 ['key']、['complex.key']）
     * - 允许多个分段（例如 $.key[0]['another_key']、$.key1.key2）
     *
     * 有效路径示例：
     *   $, $.key, $['key'], $[0], $[4].model, $.key[0]['another_key'], $.key1.key2, $.input.key[4].role, $.input['key1'][12]['key2']
     *
     * 无效路径示例：
     *   $[0].['model weird'], $.key with space, $[abc], $['unterminated], model.xx, $.
     *
     */
    private static final String VALID_JSON_KEY_REGEXP = "^\\$(?:\\.(?:[A-Za-z0-9_]+)|\\[\\d+\\]|\\['(?:[^'\\\\]|\\\\.)*'\\])*$";
    public static final String DUMMY_JSON_KEY = "$.__dummy__";
    public static final String JSON_FIELD = "JSON_VALUE(%s, '%s')";

    public void addGroupingTemplateParams(@NonNull List<GroupBy> groups, @NonNull ST template) {
        List<String> groupings = groups.stream()
                .map(group -> switch (group.type()) {
                    case DICTIONARY -> JSON_FIELD.formatted(group.field(), getKeyAndValidate(group));
                    case STRING -> group.field();
                    case LIST -> "arrayJoin(if(empty(%s), [''], %s))".formatted(group.field(), group.field());
                    default -> throw new BadRequestException("Unsupported grouping field type: " + group.type());
                })
                .toList();

        String groupByClause = String.join(", ", groupings);
        String groupBySelect = IntStream.range(0, groups.size())
                .mapToObj(i -> "%s AS group_%d".formatted(groupings.get(i), i))
                .collect(Collectors.joining(", "));

        template.add("groupBy", groupByClause);
        template.add("groupSelects", groupBySelect);
    }

    private String getKeyAndValidate(GroupBy group) {

        String key = JsonPathUtils.toRootedJsonPath(group.key());
        return isValidJsonPath(key) ? key : DUMMY_JSON_KEY;
    }

    static boolean isValidJsonPath(String path) {
        // 必须以 "$" 开头并匹配允许的模式
        return path.matches(VALID_JSON_KEY_REGEXP);
    }
}
