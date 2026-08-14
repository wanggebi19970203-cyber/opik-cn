package com.comet.opik.domain.filter;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.comet.opik.domain.filter.FilterQueryBuilder.JSONPATH_ROOT;

/**
 * 构建查询分析数据库时用于寻址动态字典字段（通常是 {@code metadata}）的 JSONPath 表达式。
 * <p>
 * ClickHouse 在分析查询时（执行开始之前）解析 {@code JSON_VALUE} 的 JSONPath 参数。因此一个它无法解析的
 * 表达式会以 {@code BAD_ARGUMENTS: Unable to parse JSONPath} 中止整个语句，而不是返回零行，而且没有任何
 * 运行时选项可以缓和这一点。{@link FilterQueryBuilder} 中用于状态数据库的
 * {@code RETURNING ... NULL ON ERROR} 子句是 MySQL 专有语法，ClickHouse 会直接拒绝它。
 * 因此路径必须在构建时就确定好。
 * <p>
 * 未加引号的点号记法只接受键中的 {@code [A-Za-z0-9_]}，因此持有任何其他字符的键——最常见的是连字符——
 * 无法用这种方式表达。方括号记法在加引号后接受任意字符，用于这些键。
 */
@UtilityClass
public class JsonPathUtils {

    private static final Pattern DOT_NOTATION_SEGMENT = Pattern.compile("[A-Za-z0-9_]+");

    /**
     * 承载路径含义（而不是键的一部分）的下标：数组索引、通配符或带引号的键。ClickHouse 接受两种引号风格，
     * 因此两者都会被识别——把 {@code a["version"].b} 当作字面键会解析出与今天不同的值。
     */
    private static final Pattern PATH_SUBSCRIPT = Pattern
            .compile("\\[(?:\\d+|\\*|'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\")]");

    private static final String PATH_SEPARATOR = ".";

    /**
     * 扫描时的“不在带引号的片段内”哨兵值；否则就是打开该片段的定界符，这样用另一种风格引用的方括号就不会被
     * 错误计数。
     */
    private static final char NOT_QUOTED = 0;

    /**
     * 把字典过滤键解析为分析数据库的 JSONPath。
     * <p>
     * 已经携带 JSONPath 语法的键是由调用方编写的，因此它完全按原样组装，只做损坏筛查。它有意地不针对可接受
     * 形状的允许列表进行匹配：ClickHouse 接受诸如通配符索引（{@code version[*]}）这类构造，而此处的任何列表
     * 都没有枚举它们，拒绝它们会悄然破坏今天仍然有效的过滤条件。
     * <p>
     * 其他所有情况会逐段加引号转成方括号记法，这种记法可以表达任意字符，因此总能解析。有两类键会落到这里：
     * <ul>
     * <li>使用点号记法不支持的字符的普通键，例如连字符。加引号让它能正常解析。</li>
     * <li>格式错误的编写表达式。加引号把它变成一个任何文档都不携带的字面键，因此它什么都不会匹配。</li>
     * </ul>
     * 两种情况查询都会运行而不是中止。
     * <p>
     * 每个片段都能用点号记法表达的普通键会继续生成与之前完全相同的路径，并且点号始终作为片段分隔符保持其
     * 含义。
     *
     * @param key 字典键，例如 {@code environment} 或 {@code hidden_params.retry-count}
     * @return JSONPath，例如 {@code $.environment} 或 {@code $['hidden_params']['retry-count']}
     */
    public static String toAnalyticsDbJsonPath(@NonNull String key) {
        var segments = key.split("\\.", -1);

        if (isPathExpression(key)) {
            var path = toRootedJsonPath(key);

            return isStructurallySound(path) ? path : toBracketNotation(segments);
        }

        return isExpressibleInDotNotation(segments)
                ? toRootedJsonPath(key)
                : toBracketNotation(segments);
    }

    private static boolean isPathExpression(String key) {
        if (isRooted(key) || key.startsWith("[") || key.startsWith(PATH_SEPARATOR)) {
            return true;
        }

        return hasSubscript(key) && everySubscriptCarriesPathMeaning(key);
    }

    /**
     * 前导 {@code $} 只有在后面接着路径时才作为表达式的根：什么都没有、分隔符或下标。诸如 {@code $schema} 或
     * {@code $ref} 这类键只是以该字符开头，属于普通键，因此会被加引号，而不是作为一个 ClickHouse 无法解析的
     * 表达式交给它。
     */
    private static boolean isRooted(String key) {
        if (!key.startsWith(JSONPATH_ROOT)) {
            return false;
        }

        var afterRoot = key.substring(JSONPATH_ROOT.length());

        return afterRoot.isEmpty() || afterRoot.startsWith(PATH_SEPARATOR) || afterRoot.startsWith("[");
    }

    private static boolean hasSubscript(String key) {
        return key.indexOf('[') >= 0 || key.indexOf(']') >= 0;
    }

    /**
     * 区分 {@code version[*]}（其中方括号是键的下标）和 {@code feature[beta]}（其中方括号是键本身的一部分）。
     * 只有当方括号的内容是索引、通配符或带引号的键时，它才表示下标；其他任何内容都会让整个东西保持字面键，
     * 随后被加引号，而不是作为路径语法交给 ClickHouse。
     */
    private static boolean everySubscriptCarriesPathMeaning(String key) {
        return !hasSubscript(PATH_SUBSCRIPT.matcher(key).replaceAll(""));
    }

    /**
     * 筛查编写表达式是否有任何 JSONPath 都无法幸存的损坏：不平衡的方括号、未终止的引号，或其后没有内容的尾随
     * 分隔符。
     * <p>
     * 这只会拒绝在任何语法下都无法解析的表达式，因此一个正常工作的过滤条件不可能被变成空过滤条件。它不是
     * 完整性检查——此处格式良好的表达式仍可能被 ClickHouse 拒绝。
     */
    private static boolean isStructurallySound(String path) {
        if (path.endsWith(PATH_SEPARATOR)) {
            return false;
        }

        var depth = 0;
        var openQuote = NOT_QUOTED;

        for (var i = 0; i < path.length(); i++) {
            var current = path.charAt(i);

            if (openQuote != NOT_QUOTED) {
                if (current == '\\') {
                    i++;
                } else if (current == openQuote) {
                    openQuote = NOT_QUOTED;
                }
                continue;
            }

            switch (current) {
                case '\'', '"' -> openQuote = current;
                case '[' -> depth++;
                case ']' -> depth--;
                default -> {
                }
            }

            if (depth < 0) {
                return false;
            }
        }

        return depth == 0 && openQuote == NOT_QUOTED;
    }

    private static boolean isExpressibleInDotNotation(String[] segments) {
        return Arrays.stream(segments).allMatch(segment -> DOT_NOTATION_SEGMENT.matcher(segment).matches());
    }

    /**
     * 给键加上根前缀，使其读作路径，例如 {@code .a} 和 {@code a} 都变成 {@code $.a}。键已经携带的任何语法——
     * 下标、通配符、带引号的片段——都保持原样；只补充根。与
     * {@link com.comet.opik.domain.GroupingQueryBuilder} 共享，使两者对有根路径的定义保持一致。
     */
    public static String toRootedJsonPath(@NonNull String key) {
        if (key.startsWith(JSONPATH_ROOT)) {
            return key;
        }

        if (key.startsWith("[") || key.startsWith(PATH_SEPARATOR)) {
            return "%s%s".formatted(JSONPATH_ROOT, key);
        }

        return "%s%s%s".formatted(JSONPATH_ROOT, PATH_SEPARATOR, key);
    }

    /**
     * 将 {@code a.b-c} 渲染为 {@code $['a']['b-c']}。
     */
    private static String toBracketNotation(String[] segments) {
        return Arrays.stream(segments)
                .map(JsonPathUtils::quoteSegment)
                .collect(Collectors.joining("", JSONPATH_ROOT, ""));
    }

    private static String quoteSegment(String segment) {
        return "['%s']".formatted(segment.replace("\\", "\\\\").replace("'", "\\'"));
    }
}
