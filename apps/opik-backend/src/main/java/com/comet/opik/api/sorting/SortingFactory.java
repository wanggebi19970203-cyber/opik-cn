package com.comet.opik.api.sorting;

import com.comet.opik.utils.JsonUtils;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public abstract class SortingFactory {
    public static final String ERR_INVALID_SORTING_PARAM_TEMPLATE = "Invalid sorting query parameter '%s'";
    public static final String ERR_ILLEGAL_SORTING_FIELDS_TEMPLATE = "Invalid sorting fields '%s'";
    public static final String ERR_MULTIPLE_SORTING = "Sorting by multiple fields is currently not supported";

    public List<SortingField> newSorting(String queryParam) {
        List<SortingField> sorting = new ArrayList<>();

        if (StringUtils.isBlank(queryParam)) {
            return sorting;
        }

        try {
            sorting = JsonUtils.readCollectionValue(queryParam, List.class, SortingField.class);
        } catch (UncheckedIOException exception) {
            throw new BadRequestException(ERR_INVALID_SORTING_PARAM_TEMPLATE.formatted(queryParam), exception);
        }

        // 在任何按字段处理之前先丢弃 field 为空或空白的条目。子类的
        // processFields 钩子（例如 SortingFactoryDatasets.ensureBindKeyParam）以及有效性
        // 检查都会解引用 field，而不可变的 getSortableFields().contains(null) /
        // field.startsWith(...) 在 field 为 null 时都会抛出 NPE。
        sorting = sorting.stream().filter(field -> StringUtils.isNotBlank(field.field())).toList();

        // 供子类在反序列化后处理字段的钩子
        sorting = processFields(sorting);

        // 过滤掉无效字段并返回有效字段
        return filterValidFields(sorting);
    }

    /**
     * 供子类在反序列化后处理/转换排序字段的钩子方法。
     * 默认实现原样返回字段。
     *
     * @param sorting JSON 反序列化后的排序字段
     * @return 处理后的排序字段
     */
    protected List<SortingField> processFields(@NonNull List<SortingField> sorting) {
        return sorting;
    }

    public abstract List<String> getSortableFields();

    /**
     * 过滤掉无效的排序字段，而不是抛出错误。
     * 这提供了优雅降级——无效字段会被记录日志并忽略，
     * 从而允许请求以有效字段或默认排序继续。
     *
     * @param sorting 要过滤的排序字段
     * @return 有效排序字段列表（可能为空）
     */
    private List<SortingField> filterValidFields(List<SortingField> sorting) {
        if (CollectionUtils.isEmpty(sorting)) {
            return sorting;
        }

        // 目前仅支持单字段排序
        if (sorting.size() > 1) {
            log.info("请求了多个排序字段但不支持，仅使用第一个字段：'{}'",
                    sorting.stream().map(SortingField::field).toList());
            sorting = List.of(sorting.get(0));
        }

        // 过滤掉不支持的字段
        List<SortingField> validFields = sorting.stream()
                .filter(sortField -> {
                    boolean isValid = isFieldSupported(sortField.field()) || isDynamicFieldSupported(sortField.field());
                    if (!isValid) {
                        log.info("忽略不支持的排序字段：'{}'", sortField.field());
                    }
                    return isValid;
                })
                .toList();

        return validFields;
    }

    private boolean isFieldSupported(String field) {
        return this.getSortableFields().contains(field);
    }

    private boolean isDynamicFieldSupported(String field) {
        if (field.contains(".")) {
            // 在第一个点处分割
            String[] parts = field.split("\\.", 2);
            String baseField = parts[0];
            String dynamicPart = parts.length > 1 ? parts[1] : "";

            // 动态部分不能为空
            if (dynamicPart.isEmpty()) {
                return false;
            }

            // 检查基础字段是否匹配任何受支持的动态字段模式
            return this.getSortableFields()
                    .stream()
                    .filter(supportedField -> supportedField.contains(".*"))
                    .anyMatch(supportedField -> {
                        String supportedBaseField = supportedField.substring(0, supportedField.indexOf(".*"));
                        return baseField.equals(supportedBaseField);
                    });
        }

        return false;
    }
}
