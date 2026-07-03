package com.comet.opik.api;

import com.comet.opik.api.validation.DatasetItemBatchValidation;
import com.comet.opik.infrastructure.ratelimit.RateEventContainer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@DatasetItemBatchValidation
public record DatasetItemBatch(
        @JsonView({
                DatasetItem.View.Write.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "如果为空，则必须提供 dataset_id") String datasetName,
        @JsonView({
                DatasetItem.View.Write.class}) @Schema(description = "如果为空，则必须提供 dataset_name") UUID datasetId,
        @JsonView({
                DatasetItem.View.Write.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "可选。按名称将批次与项目关联。如果提供了 project_id，则忽略此项。") String projectName,
        @JsonView({
                DatasetItem.View.Write.class}) @Schema(description = "可选。按 ID 将批次与项目关联。优先于 project_name。") UUID projectId,
        @JsonView({DatasetItem.View.Write.class}) @NotNull @Size(min = 1, max = 1000) @Valid List<DatasetItem> items,
        @JsonView({
                DatasetItem.View.Write.class}) @Schema(description = "可选的批次组 ID，用于将多个批次分组到单个数据集版本中。如果为空，则修改最新版本而非创建新版本。") UUID batchGroupId,
        // OPIK-6696: 当 copy_from_* 坐标都设置时，复制未更改行的 INSERT FROM SELECT
        // 从此 (dataset, version) 对读取，而非目标数据集的先前版本，
        // 避免多副本写后读窗口。
        @JsonView({
                DatasetItem.View.Write.class}) @Schema(description = "可选。在物化新版本时，从中读取延续行的数据集。需要与 copy_from_version_id 一起提供。如果为空，则从目标数据集的先前版本读取延续行。") UUID copyFromDatasetId,
        @JsonView({
                DatasetItem.View.Write.class}) @Schema(description = "可选。在 copy_from_dataset_id 中读取延续行的版本。需要与 copy_from_dataset_id 一起提供。") UUID copyFromVersionId)
        implements
            RateEventContainer {

    @Override
    public long eventCount() {
        return items.size();
    }
}
