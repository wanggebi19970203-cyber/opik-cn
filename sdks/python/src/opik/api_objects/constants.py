FEEDBACK_SCORE_SOURCE_SDK = "sdk"
DATASET_SOURCE_SDK = "sdk"

FEEDBACK_SCORES_MAX_BATCH_SIZE = 1000
EXPERIMENT_ITEMS_MAX_BATCH_SIZE = 1000
EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE = 1000

# bulk 端点会拒绝任何*序列化*后体积超过 4MB 的请求
# （MaxRequestSize.java）。后端统计的是整个请求，包括信封字段，
# 因此我们针对一个更低的上限进行分批，为
# experiment_name/dataset_name/experiment_id/project_name 以及
# 我们的体积估算与实际 JSON 编码之间的差距预留余量。
EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE_MB = 3.5

# 上传线程的上限，与文件上传池保持一致。用于防止
# 调用者传入任意大的 num_threads。
EXPERIMENT_ITEMS_BULK_MAX_THREADS = 32
DATASET_ITEMS_MAX_BATCH_SIZE = 1000
ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE = 1000
DELETE_TRACE_BATCH_SIZE = 1000

DATASET_STREAM_BATCH_SIZE = 2000

# 并行数据集插入需要一个能对并发的数据集版本写入进行序列化的后端。
# 在早于此版本的后端上，共享同一个 batch_group_id 的并发批次会发生竞态，
# 可能导致 500 或静默丢行；2.2.8 是首个包含该修复的版本（OPIK-7264，
# https://github.com/comet-ml/opik/pull/7518）。此值不可由用户调整：
# 降低它会重新打开该竞态。
MIN_BACKEND_VERSION_FOR_PARALLEL_INSERT = "2.2.8"
