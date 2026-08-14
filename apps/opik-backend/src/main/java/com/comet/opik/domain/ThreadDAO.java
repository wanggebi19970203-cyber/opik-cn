package com.comet.opik.domain;

import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.TraceThreadSortingFactory;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import com.comet.opik.utils.TruncationUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.FilterUtils.bindTraceThreadSearchCriteria;
import static com.comet.opik.infrastructure.FilterUtils.getLogComment;
import static com.comet.opik.infrastructure.FilterUtils.newTraceThreadFindTemplate;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.SentinelTranslation.epochToNull;
import static java.util.function.Predicate.not;

@ImplementedBy(ThreadDAOImpl.class)
public interface ThreadDAO {

    Mono<TraceThread.TraceThreadPage> find(int size, int page, TraceSearchCriteria threadSearchCriteria);

    Mono<TraceThread> findById(UUID projectId, String threadId, boolean truncate);

    Flux<TraceThread> search(int limit, TraceSearchCriteria criteria);

    Mono<ProjectStats> getThreadStats(TraceSearchCriteria criteria);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
// TODO: 在 v1 下线后，移除 annotation_queue_filters 条件，仅保留 annotation_queue_id
class ThreadDAOImpl implements ThreadDAO {

    private static final String THREAD_SEARCH_CLAUSE = """
            (ilike(thread_id, :search_text)
            OR ilike(id, :search_text)
            OR ilike(input, :search_text)
            OR ilike(output, :search_text))""";

    /***
     * 当将一组 trace 视为线程时，会执行多项聚合来获取线程详情。
     * <p>
     * 更多详情请参考 SELECT_TRACES_THREAD_BY_ID 查询。
     ***/
    private static final String SELECT_TRACES_THREADS_BY_PROJECT_IDS = """
            WITH <if(traces_final_ids)>traces_final_ids AS (
                SELECT DISTINCT id, thread_id
                FROM (
                    SELECT *
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND thread_id \\<> ''
                    <if(uuid_from_time)> AND id >= :uuid_from_time AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) <endif>
                    <if(uuid_to_time)> AND id \\<= :uuid_to_time AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) <endif>
                    <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                )
                WHERE 1 = 1
                <if(filters)> AND <filters> <endif>
                <if(search_text)> AND <search_text> <endif>
            ), <endif><if(page_pushdown)>page_thread_ids AS (
                SELECT thread_id
                FROM (
                    SELECT
                        thread_id,
                        min(start_time) AS start_time,
                        max(end_time) AS end_time,
                        max(last_updated_at) AS trace_last_updated_at
                    FROM (
                        SELECT id, thread_id, start_time, end_time, last_updated_at
                        FROM traces
                        WHERE workspace_id = :workspace_id
                          AND project_id = :project_id
                          AND thread_id \\<> ''
                          <if(filters)> AND <filters> <endif>
                          <if(search_text)> AND <search_text> <endif>
                        ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    )
                    GROUP BY thread_id
                ) AS pt
                LEFT JOIN (
                    SELECT thread_id, id, last_updated_at
                    FROM trace_threads
                    WHERE workspace_id = :workspace_id
                      AND project_id = :project_id
                    ORDER BY (workspace_id, project_id, thread_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS ptt ON pt.thread_id = ptt.thread_id
                ORDER BY if(ptt.last_updated_at = toDateTime64(0, 6, 'UTC'), pt.trace_last_updated_at, ptt.last_updated_at) DESC,
                    pt.start_time ASC,
                    nullIf(pt.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) DESC,
                    pt.thread_id
                LIMIT :limit <if(offset)>OFFSET :offset<endif>
            ), <endif>traces_final AS (
                SELECT
                    id,
                    workspace_id,
                    project_id,
                    thread_id,
                    start_time,
                    end_time,
                    input,
                    output,
                    truncated_input,
                    truncated_output,
                    input_length,
                    output_length,
                    truncation_threshold,
                    last_updated_at,
                    last_updated_by,
                    created_by,
                    created_at,
                    environment
                FROM (
                    SELECT
                        *,
                        truncated_input,
                        truncated_output,
                        input_length,
                        output_length
                    FROM traces
                    WHERE workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND thread_id \\<> ''
                      <if(page_pushdown)>
                          AND thread_id IN (SELECT thread_id FROM page_thread_ids)
                          <if(filters)> AND <filters> <endif>
                          <if(search_text)> AND <search_text> <endif>
                      <else>
                          <if(traces_final_ids)>
                              AND id IN (SELECT id FROM traces_final_ids)
                              <if(uuid_from_time)> AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) <endif>
                              <if(uuid_to_time)> AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) <endif>
                          <else>
                              <if(uuid_from_time)> AND id >= :uuid_from_time AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) <endif>
                              <if(uuid_to_time)> AND id \\<= :uuid_to_time AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) <endif>
                              <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                          <endif>
                      <endif>
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
            ), spans_deduped AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    id,
                    last_updated_at,
                    usage,
                    total_estimated_cost,
                    provider
                FROM spans
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  <if(page_pushdown)>
                      AND trace_id IN (SELECT id FROM traces_final)
                  <else>
                      <if(traces_final_ids)>
                          AND trace_id IN (SELECT id FROM traces_final_ids)
                      <else>
                          <if(uuid_from_time)> AND trace_id >= :uuid_from_time <endif>
                          <if(uuid_to_time)> AND trace_id \\<= :uuid_to_time <endif>
                      <endif>
                  <endif>
                ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    arraySort(groupUniqArrayIf(provider, provider != '')) as providers
                FROM spans_deduped
                GROUP BY workspace_id, project_id, trace_id
            ), trace_threads_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    thread_id,
                    id as thread_model_id,
                    status,
                    tags,
                    created_by,
                    last_updated_by,
                    created_at,
                    last_updated_at,
                    environment
                FROM trace_threads
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(uuid_from_time)>
                    AND id >= :uuid_from_time
                    <if(uuid_to_time)>AND id \\<= :uuid_to_time<endif>
                <else>
                    <if(traces_final_ids)>
                        AND thread_id IN (SELECT thread_id FROM traces_final_ids)
                    <endif>
                <endif>
                <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                <if(page_pushdown)> AND thread_id IN (SELECT thread_id FROM traces_final) <endif>
                ORDER BY (workspace_id, project_id, thread_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), feedback_scores_deduped AS (
                SELECT *
                FROM (
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        created_by,
                        last_updated_by,
                        created_at,
                        last_updated_at,
                        feedback_scores.last_updated_by AS author,
                        CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = 'thread'
                      AND workspace_id = :workspace_id
                      AND project_id IN :project_id
                      AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                    UNION ALL
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        created_by,
                        last_updated_by,
                        created_at,
                        last_updated_at,
                        author,
                        source_queue_id
                    FROM authored_feedback_scores
                    WHERE entity_type = 'thread'
                       AND workspace_id = :workspace_id
                       AND project_id IN :project_id
                       AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                       <if(annotation_queue_id)>AND source_queue_id = :annotation_queue_id<endif>
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), feedback_scores_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(tuple(value, reason, category_name, source, author, created_by, last_updated_by, created_at, last_updated_at, source_queue_id)) AS entries
                FROM feedback_scores_deduped
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                    IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                    IF(length(entries) = 1, entries[1].2, arrayStringConcat(arrayMap(e -> if(e.2 = '', '\\<no reason>', e.2), entries), ', ')) AS reason,
                    entries[1].4 AS source,
                    mapFromArrays(
                        arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                        arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, '', '', e.10, e.5), entries)
                    ) AS value_by_author,
                    arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                    arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                    arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                    arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
                FROM feedback_scores_grouped
            ), feedback_scores_agg AS (
                SELECT
                    entity_id,
                    mapFromArrays(
                            groupArray(name),
                            groupArray(value)
                    ) AS feedback_scores,
                    groupArray(tuple(
                            name,
                            category_name,
                            value,
                            reason,
                            source,
                            value_by_author,
                            created_at,
                            last_updated_at,
                            created_by,
                            last_updated_by
                               )) AS feedback_scores_list
                FROM feedback_scores_final
                GROUP BY workspace_id, project_id, entity_id
            ), comments_final AS (
              SELECT
                   entity_id,
                   groupArray(tuple(*)) AS comments
              FROM (
                SELECT
                    id,
                    text,
                    created_at,
                    last_updated_at,
                    created_by,
                    last_updated_by,
                    source_queue_id,
                    entity_id,
                    workspace_id,
                    project_id
                FROM comments
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                <if(annotation_queue_id)>AND source_queue_id = :annotation_queue_id<endif>
                ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
              )
              GROUP BY workspace_id, project_id, entity_id
            ), thread_annotation_queue_ids AS (
                 SELECT thread_id,
                        groupArray(id) AS annotation_queue_ids
                 FROM (
                    SELECT DISTINCT aq.id as id, aqi.item_id as thread_id
                    FROM annotation_queue_items aqi
                    JOIN annotation_queues aq ON aq.id = aqi.queue_id
                    WHERE aq.scope = 'thread'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      <if(uuid_from_time)> AND aqi.item_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND aqi.item_id \\<= :uuid_to_time <endif>
                 ) AS annotation_queue_ids_with_thread_id
                 GROUP BY thread_id
            )
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores_final
                    ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>
            SELECT
                t.workspace_id as workspace_id,
                t.project_id as project_id,
                t.id as id,
                t.start_time as start_time,
                t.end_time as end_time,
                t.duration as duration,
                <if(truncate)> replaceRegexpAll(t.truncated_first_message, '<truncate>', '"[image]"') as first_message <else> t.first_message as first_message<endif>,
                <if(truncate)> replaceRegexpAll(t.truncated_last_message, '<truncate>', '"[image]"') as last_message <else> t.last_message as last_message<endif>,
                <if(truncate)> t.first_message_length >= t.first_message_truncation_threshold as first_message_truncated <else> false as first_message_truncated <endif>,
                <if(truncate)> t.last_message_length >= t.last_message_truncation_threshold as last_message_truncated <else> false as last_message_truncated <endif>,
                t.number_of_messages as number_of_messages,
                t.total_estimated_cost as total_estimated_cost,
                t.usage as usage,
                if(tt.created_by = '', t.created_by, tt.created_by) as created_by,
                if(tt.last_updated_by = '', t.last_updated_by, tt.last_updated_by) as last_updated_by,
                if(tt.last_updated_at == toDateTime64(0, 6, 'UTC'), t.last_updated_at, tt.last_updated_at) as last_updated_at,
                if(tt.created_at = toDateTime64(0, 9, 'UTC'), t.created_at, tt.created_at) as created_at,
                if(tt.status = 'unknown', 'active', tt.status) as status,
                if(LENGTH(CAST(tt.thread_model_id AS Nullable(String))) > 0, tt.thread_model_id, NULL) as thread_model_id,
                tt.tags as tags,
                if(tt.environment = '', t.environment, tt.environment) as environment,
                fsagg.feedback_scores_list as feedback_scores_list,
                fsagg.feedback_scores as feedback_scores,
                c.comments AS comments
            FROM (
                SELECT
                    t.thread_id as id,
                    t.workspace_id as workspace_id,
                    t.project_id as project_id,
                    min(t.start_time) as start_time,
                    max(t.end_time) as end_time,
                    if(end_time IS NOT NULL AND notEquals(end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND start_time IS NOT NULL
                           AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                       (dateDiff('microsecond', start_time, end_time) / 1000.0),
                       NULL) AS duration,
                    argMin(t.input, t.start_time) as first_message,
                    argMax(t.output, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as last_message,
                    argMin(t.truncated_input, t.start_time) as truncated_first_message,
                    argMax(t.truncated_output, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as truncated_last_message,
                    argMin(t.input_length, t.start_time) as first_message_length,
                    argMax(t.output_length, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as last_message_length,
                    argMin(t.truncation_threshold, t.start_time) as first_message_truncation_threshold,
                    argMax(t.truncation_threshold, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as last_message_truncation_threshold,
                    count(DISTINCT t.id) * 2 as number_of_messages,
                    sum(s.total_estimated_cost) as total_estimated_cost,
                    sumMap(s.usage) as usage,
                    max(t.last_updated_at) as last_updated_at,
                    argMax(t.last_updated_by, t.last_updated_at) as last_updated_by,
                    argMin(t.created_by, t.created_at) as created_by,
                    min(t.created_at) as created_at,
                    argMin(t.environment, t.start_time) as environment
                FROM traces_final AS t
                    LEFT JOIN spans_agg AS s ON t.id = s.trace_id
                GROUP BY
                    t.workspace_id, t.project_id, t.thread_id
            ) AS t
            <if(uuid_from_time)>INNER<else>LEFT<endif> JOIN trace_threads_final AS tt ON t.workspace_id = tt.workspace_id
                AND t.project_id = tt.project_id
                AND t.id = tt.thread_id
            LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = tt.thread_model_id
            LEFT JOIN comments_final c ON c.entity_id = tt.thread_model_id
            <if(annotation_queue_filters || annotation_queue_id)>
            LEFT JOIN thread_annotation_queue_ids as ttaqi ON ttaqi.thread_id = tt.thread_model_id
            <endif>
            WHERE workspace_id = :workspace_id
            <if(feedback_scores_filters)>
            AND thread_model_id IN (
                SELECT
                    entity_id
                FROM (
                    SELECT *
                    FROM feedback_scores_final
                    ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                )
                GROUP BY entity_id
                HAVING <feedback_scores_filters>
            )
            <endif>
            <if(feedback_scores_empty_filters)>
            AND (
                thread_model_id IN (SELECT entity_id FROM fsc WHERE fsc.feedback_scores_count = 0)
                    OR
                thread_model_id NOT IN (SELECT entity_id FROM fsc)
            )
            <endif>
            <if(trace_thread_filters)>AND<trace_thread_filters><endif>
            <if(annotation_queue_filters)> AND <annotation_queue_filters> <endif>
            <if(annotation_queue_id)> AND has(ttaqi.annotation_queue_ids, :annotation_queue_id) <endif>
            <if(last_retrieved_id)> AND thread_model_id > :last_retrieved_id<endif>
            <if(stream)>
            ORDER BY workspace_id, project_id, thread_model_id DESC
            <else>
            <if(sort_fields)> ORDER BY <sort_fields>, last_updated_at DESC <else> ORDER BY last_updated_at DESC, start_time ASC, nullIf(end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) DESC <endif>
            <endif>
            LIMIT :limit <if(page_pushdown)><else><if(offset)>OFFSET :offset<endif><endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /***
     * 当将一组 trace 视为线程时，会执行多项聚合来获取线程详情。
     * <p>
     * 更多详情请参考 SELECT_TRACES_THREAD_BY_ID 查询。
     ***/
    private static final String SELECT_COUNT_TRACES_THREADS_BY_PROJECT_IDS = """
            WITH <if(traces_final_ids)>traces_final_ids AS (
                SELECT DISTINCT id, thread_id
                FROM (
                    SELECT *
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND thread_id \\<> ''
                    <if(uuid_from_time)> AND id >= :uuid_from_time AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) <endif>
                    <if(uuid_to_time)> AND id \\<= :uuid_to_time AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) <endif>
                    <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                )
                WHERE 1 = 1
                <if(filters)> AND <filters> <endif>
                <if(search_text)> AND <search_text> <endif>
            ), <endif>traces_final AS (
                SELECT
                    id,
                    workspace_id,
                    project_id,
                    thread_id,
                    start_time,
                    end_time,
                    input,
                    output,
                    last_updated_at,
                    last_updated_by,
                    created_by,
                    created_at
                FROM (
                    SELECT *
                    FROM traces
                    WHERE workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND thread_id \\<> ''
                      <if(traces_final_ids)>
                          AND id IN (SELECT id FROM traces_final_ids)
                          <if(uuid_from_time)> AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) <endif>
                          <if(uuid_to_time)> AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) <endif>
                      <else>
                          <if(uuid_from_time)> AND id >= :uuid_from_time AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) <endif>
                          <if(uuid_to_time)> AND id \\<= :uuid_to_time AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) <endif>
                          <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                      <endif>
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
            ), trace_threads_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    thread_id,
                    id as thread_model_id,
                    status,
                    tags,
                    created_by,
                    last_updated_by,
                    created_at,
                    last_updated_at,
                    environment
                FROM trace_threads
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(uuid_from_time)>
                    AND id >= :uuid_from_time
                    <if(uuid_to_time)>AND id \\<= :uuid_to_time<endif>
                <else>
                    <if(traces_final_ids)>
                        AND thread_id IN (SELECT thread_id FROM traces_final_ids)
                    <endif>
                <endif>
                ORDER BY (workspace_id, project_id, thread_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            <if(feedback_scores_needed)>
            , feedback_scores_deduped AS (
                SELECT *
                FROM (
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        created_by,
                        last_updated_by,
                        created_at,
                        last_updated_at,
                        feedback_scores.last_updated_by AS author,
                        CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = 'thread'
                       AND workspace_id = :workspace_id
                       AND project_id = :project_id
                       AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                    UNION ALL
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        created_by,
                        last_updated_by,
                        created_at,
                        last_updated_at,
                        author,
                        source_queue_id
                    FROM authored_feedback_scores
                    WHERE entity_type = 'thread'
                       AND workspace_id = :workspace_id
                       AND project_id = :project_id
                       AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                       <if(annotation_queue_id)>AND source_queue_id = :annotation_queue_id<endif>
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), feedback_scores_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(tuple(value, reason, category_name, source, author, created_by, last_updated_by, created_at, last_updated_at, source_queue_id)) AS entries
                FROM feedback_scores_deduped
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                    IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                    IF(length(entries) = 1, entries[1].2, arrayStringConcat(arrayMap(e -> if(e.2 = '', '\\<no reason>', e.2), entries), ', ')) AS reason,
                    entries[1].4 AS source,
                    mapFromArrays(
                        arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                        arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, '', '', e.10, e.5), entries)
                    ) AS value_by_author,
                    arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                    arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                    arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                    arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
                FROM feedback_scores_grouped
            )
            <endif>
            <if(annotation_queue_filters || annotation_queue_id)>
            , thread_annotation_queue_ids AS (
                 SELECT thread_id,
                        groupArray(id) AS annotation_queue_ids
                 FROM (
                    SELECT DISTINCT aq.id as id, aqi.item_id as thread_id
                    FROM annotation_queue_items aqi
                    JOIN annotation_queues aq ON aq.id = aqi.queue_id
                    WHERE aq.scope = 'thread'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      <if(uuid_from_time)> AND aqi.item_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND aqi.item_id \\<= :uuid_to_time <endif>
                 ) AS annotation_queue_ids_with_thread_id
                 GROUP BY thread_id
            )
            <endif>
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores_final
                    ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>
            SELECT
                count(DISTINCT t.id) AS count
            FROM (
                SELECT
                    t.workspace_id as workspace_id,
                    t.project_id as project_id,
                    t.id as id,
                    t.start_time as start_time,
                    t.end_time as end_time,
                    t.duration as duration,
                    t.first_message as first_message,
                    t.last_message as last_message,
                    t.number_of_messages as number_of_messages,
                    if(tt.created_by = '', t.created_by, tt.created_by) as created_by,
                    if(tt.last_updated_by = '', t.last_updated_by, tt.last_updated_by) as last_updated_by,
                    if(tt.last_updated_at == toDateTime64(0, 6, 'UTC'), t.last_updated_at, tt.last_updated_at) as last_updated_at,
                    if(tt.created_at = toDateTime64(0, 9, 'UTC'), t.created_at, tt.created_at) as created_at,
                    if(tt.status = 'unknown', 'active', tt.status) as status,
                    if(LENGTH(CAST(tt.thread_model_id AS Nullable(String))) > 0, tt.thread_model_id, NULL) as thread_model_id,
                    tt.tags as tags
                FROM (
                    SELECT
                        t.thread_id as id,
                        t.workspace_id as workspace_id,
                        t.project_id as project_id,
                        min(t.start_time) as start_time,
                        max(t.end_time) as end_time,
                        if(end_time IS NOT NULL AND notEquals(end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND start_time IS NOT NULL
                               AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                           (dateDiff('microsecond', start_time, end_time) / 1000.0),
                           NULL) AS duration,
                        argMin(t.input, t.start_time) as first_message,
                        argMax(t.output, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as last_message,
                        count(DISTINCT t.id) * 2 as number_of_messages,
                        max(t.last_updated_at) as last_updated_at,
                        argMax(t.last_updated_by, t.last_updated_at) as last_updated_by,
                        argMin(t.created_by, t.created_at) as created_by,
                        min(t.created_at) as created_at
                    FROM traces_final AS t
                    GROUP BY
                        t.workspace_id, t.project_id, t.thread_id
                ) AS t
                <if(uuid_from_time)>INNER<else>LEFT<endif> JOIN trace_threads_final AS tt ON t.workspace_id = tt.workspace_id
                    AND t.project_id = tt.project_id
                    AND t.id = tt.thread_id
                <if(annotation_queue_filters || annotation_queue_id)>
                LEFT JOIN thread_annotation_queue_ids as ttaqi ON ttaqi.thread_id = tt.thread_model_id
                <endif>
                WHERE workspace_id = :workspace_id
                <if(feedback_scores_filters)>
                AND thread_model_id IN (
                    SELECT
                        entity_id
                    FROM (
                        SELECT *
                        FROM feedback_scores_final
                        ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                        LIMIT 1 BY entity_id, name
                    )
                    GROUP BY entity_id
                    HAVING <feedback_scores_filters>
                )
                <endif>
                <if(feedback_scores_empty_filters)>
                AND (
                    thread_model_id IN (SELECT entity_id FROM fsc WHERE fsc.feedback_scores_count = 0)
                        OR
                    thread_model_id NOT IN (SELECT entity_id FROM fsc)
                )
                <endif>
                <if(trace_thread_filters)>AND<trace_thread_filters><endif>
                <if(annotation_queue_filters)> AND <annotation_queue_filters> <endif>
            <if(annotation_queue_id)> AND has(ttaqi.annotation_queue_ids, :annotation_queue_id) <endif>
            ) AS t
            SETTINGS log_comment = '<log_comment>'
            """;

    /***
     * 当将一组 trace 视为线程时，会执行多项聚合来获取线程详情。
     * <p>
     * 所执行的聚合包括：
     *  - 线程的时长，计算为列表中第一条和最后一条 trace 的 start_time 与 end_time 之差。
     *  - 线程中的第一条消息，即列表中第一条 trace 的 input。
     *  - 线程中的最后一条消息，即列表中最后一条 trace 的 output。
     *  - 线程中的消息数量，即列表中 trace 的数量乘以 2。
     *  - 线程的最后更新时间，即列表中最后一条 trace 的 last_updated_at。
     *  - 线程的创建者，即列表中第一条 trace 的 created_by。
     *  - 线程的创建时间，即列表中第一条 trace 的 created_at。
     ***/
    private static final String SELECT_TRACES_THREAD_BY_ID = """
            WITH traces_ids AS (
                SELECT
                    id
                FROM traces
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND thread_id = :thread_id
            ), traces_final AS (
                SELECT
                    *,
                    truncated_input,
                    truncated_output,
                    input_length,
                    output_length,
                    truncation_threshold
                FROM traces
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND id IN (SELECT id FROM traces_ids)
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    arraySort(groupUniqArrayIf(provider, provider != '')) as providers
                FROM spans final
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND trace_id IN (SELECT DISTINCT id FROM traces_ids)
                GROUP BY workspace_id, project_id, trace_id
            ), trace_threads_ids AS (
                SELECT
                    id as thread_model_id
                FROM trace_threads
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND thread_id = :thread_id
            ), trace_threads_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    thread_id,
                    id as thread_model_id,
                    status,
                    tags,
                    created_by,
                    last_updated_by,
                    created_at,
                    last_updated_at,
                    environment
                FROM trace_threads
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND thread_id = :thread_id
                ORDER BY (workspace_id, project_id, thread_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), feedback_scores_deduped AS (
                SELECT *
                FROM (
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           category_name,
                           value,
                           reason,
                           source,
                           created_by,
                           last_updated_by,
                           created_at,
                           last_updated_at,
                           feedback_scores.last_updated_by AS author,
                           CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = 'thread'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND entity_id IN (SELECT thread_model_id FROM trace_threads_ids)
                    UNION ALL
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        created_by,
                        last_updated_by,
                        created_at,
                        last_updated_at,
                        author,
                        source_queue_id
                    FROM authored_feedback_scores
                    WHERE entity_type = 'thread'
                       AND workspace_id = :workspace_id
                       AND project_id = :project_id
                       AND entity_id IN (SELECT thread_model_id FROM trace_threads_ids)
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), feedback_scores_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(tuple(value, reason, category_name, source, author, created_by, last_updated_by, created_at, last_updated_at, source_queue_id)) AS entries
                FROM feedback_scores_deduped
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                    IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                    IF(length(entries) = 1, entries[1].2, arrayStringConcat(arrayMap(e -> if(e.2 = '', '\\<no reason>', e.2), entries), ', ')) AS reason,
                    entries[1].4 AS source,
                    mapFromArrays(
                        arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                        arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, '', '', e.10, e.5), entries)
                    ) AS value_by_author,
                    arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                    arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                    arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                    arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
                FROM feedback_scores_grouped
            ), feedback_scores_agg AS (
                SELECT
                    entity_id,
                    mapFromArrays(
                            groupArray(name),
                            groupArray(value)
                    ) AS feedback_scores,
                    groupArray(tuple(
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        value_by_author,
                        created_at,
                        last_updated_at,
                        created_by,
                        last_updated_by
                    )) AS feedback_scores_list
                FROM feedback_scores_final
                GROUP BY workspace_id, project_id, entity_id
            ), comments_final AS (
              SELECT
                   entity_id,
                   groupArray(tuple(*)) AS comments
              FROM (
                SELECT
                    id,
                    text,
                    created_at,
                    last_updated_at,
                    created_by,
                    last_updated_by,
                    source_queue_id,
                    entity_id,
                    workspace_id,
                    project_id
                FROM comments
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND entity_id IN (SELECT thread_model_id FROM trace_threads_ids)
                ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
              )
              GROUP BY workspace_id, project_id, entity_id
            )
            SELECT
                t.workspace_id as workspace_id,
                t.project_id as project_id,
                t.thread_id as id,
                t.start_time as start_time,
                t.end_time as end_time,
                t.duration as duration,
                <if(truncate)> t.truncated_first_message as first_message <else> t.first_message as first_message<endif>,
                <if(truncate)> t.truncated_last_message as last_message <else> t.last_message as last_message<endif>,
                <if(truncate)> t.first_message_length >= t.first_message_truncation_threshold as first_message_truncated <else> false as first_message_truncated <endif>,
                <if(truncate)> t.last_message_length >= t.last_message_truncation_threshold as last_message_truncated <else> false as last_message_truncated <endif>,
                t.number_of_messages as number_of_messages,
                t.total_estimated_cost as total_estimated_cost,
                t.usage as usage,
                if(tt.created_by = '', t.created_by, tt.created_by) as created_by,
                if(tt.last_updated_by = '', t.last_updated_by, tt.last_updated_by) as last_updated_by,
                if(tt.last_updated_at == toDateTime64(0, 6, 'UTC'), t.last_updated_at, tt.last_updated_at) as last_updated_at,
                if(tt.created_at = toDateTime64(0, 9, 'UTC'), t.created_at, tt.created_at) as created_at,
                if(tt.status = 'unknown', 'active', tt.status) as status,
                if(LENGTH(CAST(tt.thread_model_id AS Nullable(String))) > 0, tt.thread_model_id, NULL) as thread_model_id,
                tt.tags as tags,
                if(tt.environment = '', t.environment, tt.environment) as environment,
                fsagg.feedback_scores_list as feedback_scores_list,
                fsagg.feedback_scores as feedback_scores,
                c.comments AS comments
            FROM (
                SELECT
                    t.thread_id as thread_id,
                    t.workspace_id as workspace_id,
                    t.project_id as project_id,
                    min(t.start_time) as start_time,
                    max(t.end_time) as end_time,
                    if(end_time IS NOT NULL AND notEquals(end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND start_time IS NOT NULL
                           AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                       (dateDiff('microsecond', start_time, end_time) / 1000.0),
                       NULL) AS duration,
                    argMin(t.input, t.start_time) as first_message,
                    argMax(t.output, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as last_message,
                    argMin(t.truncated_input, t.start_time) as truncated_first_message,
                    argMax(t.truncated_output, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as truncated_last_message,
                    argMin(t.input_length, t.start_time) as first_message_length,
                    argMax(t.output_length, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as last_message_length,
                    argMin(t.truncation_threshold, t.start_time) as first_message_truncation_threshold,
                    argMax(t.truncation_threshold, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as last_message_truncation_threshold,
                    count(DISTINCT t.id) * 2 as number_of_messages,
                    sum(s.total_estimated_cost) as total_estimated_cost,
                    sumMap(s.usage) as usage,
                    max(t.last_updated_at) as last_updated_at,
                    argMax(t.last_updated_by, t.last_updated_at) as last_updated_by,
                    argMin(t.created_by, t.created_at) as created_by,
                    min(t.created_at) as created_at,
                    argMin(t.environment, t.start_time) as environment
                FROM traces_final AS t
                LEFT JOIN spans_agg AS s ON t.id = s.trace_id
                GROUP BY t.workspace_id, t.project_id, t.thread_id
            ) AS t
            LEFT JOIN trace_threads_final AS tt ON t.workspace_id = tt.workspace_id AND t.project_id = tt.project_id AND t.thread_id = tt.thread_id
            LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = tt.thread_model_id
            LEFT JOIN comments_final c ON c.entity_id = tt.thread_model_id
            SETTINGS log_comment = '<log_comment>'
            """;

    /***
     * 通过执行两级聚合来计算线程统计信息：
     * 1. 第一级：使用与 SELECT_TRACES_THREADS_BY_PROJECT_IDS 相同的线程聚合（复用完全相同的 CTE 和聚合逻辑）
     * 2. 第二级：包装线程结果，并跨所有线程计算统计信息（AVG、SUM、分位数）
     ***/
    private static final String SELECT_TRACE_THREADS_STATS = """
            SELECT
                threads.workspace_id as workspace_id,
                threads.project_id as project_id,
                countDistinct(threads.id) AS thread_count,
                arrayMap(
                  v -> toDecimal64(
                         greatest(
                           least(if(isFinite(v), v, 0),  999999999.999999999),
                           -999999999.999999999
                         ),
                         9
                       ),
                  quantiles(0.5, 0.9, 0.99)(threads.duration)
                ) AS duration,
                toInt64(0) AS input,
                toInt64(0) AS output,
                toInt64(0) AS metadata,
                toFloat64(0) AS tags,
                avgMap(threads.usage) as usage,
                sumMap(threads.usage) as usage_sum,
                avgMap(threads.feedback_scores) AS feedback_scores,
                toFloat64(0) AS llm_span_count_avg,
                toFloat64(0) AS span_count_avg,
                avgIf(threads.total_estimated_cost, threads.total_estimated_cost > 0) AS total_estimated_cost_,
                toDecimal128(if(isNaN(total_estimated_cost_), 0, total_estimated_cost_), 12) AS total_estimated_cost_avg,
                sumIf(threads.total_estimated_cost, threads.total_estimated_cost > 0) AS total_estimated_cost_sum_,
                toDecimal128(total_estimated_cost_sum_, 12) AS total_estimated_cost_sum,
                toInt64(0) AS guardrails_failed_count,
                toInt64(0) AS error_count
            FROM (
                WITH <if(traces_final_ids)>traces_final_ids AS (
                    SELECT DISTINCT id, thread_id
                    FROM (
                        SELECT *
                        FROM traces
                        WHERE workspace_id = :workspace_id
                        AND project_id = :project_id
                        AND thread_id \\<> ''
                        <if(uuid_from_time)> AND id >= :uuid_from_time AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) <endif>
                        <if(uuid_to_time)> AND id \\<= :uuid_to_time AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) <endif>
                        <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                    )
                    WHERE 1 = 1
                    <if(filters)> AND <filters> <endif>
                    <if(search_text)> AND <search_text> <endif>
                ), <endif>traces_final AS (
                    SELECT
                        id,
                        workspace_id,
                        project_id,
                        thread_id,
                        start_time,
                        end_time,
                        input,
                        output,
                        last_updated_at,
                        last_updated_by,
                        created_by,
                        created_at
                    FROM (
                        SELECT *
                        FROM traces
                        WHERE workspace_id = :workspace_id
                          AND project_id = :project_id
                          AND thread_id \\<> ''
                          <if(traces_final_ids)>
                              AND id IN (SELECT id FROM traces_final_ids)
                              <if(uuid_from_time)> AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) <endif>
                              <if(uuid_to_time)> AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) <endif>
                          <else>
                              <if(uuid_from_time)> AND id >= :uuid_from_time AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) <endif>
                              <if(uuid_to_time)> AND id \\<= :uuid_to_time AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) <endif>
                              <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                          <endif>
                        ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    )
                ), spans_deduped AS (
                    SELECT
                        workspace_id,
                        project_id,
                        trace_id,
                        id,
                        last_updated_at,
                        usage,
                        total_estimated_cost,
                        provider
                    FROM spans
                    WHERE workspace_id = :workspace_id
                      AND project_id = :project_id
                      <if(traces_final_ids)>
                          AND trace_id IN (SELECT id FROM traces_final_ids)
                      <else>
                          <if(uuid_from_time)> AND trace_id >= :uuid_from_time <endif>
                          <if(uuid_to_time)> AND trace_id \\<= :uuid_to_time <endif>
                      <endif>
                    ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ), spans_agg AS (
                    SELECT
                        trace_id,
                        sumMap(usage) as usage,
                        sum(total_estimated_cost) as total_estimated_cost,
                        arraySort(groupUniqArrayIf(provider, provider != '')) as providers
                    FROM spans_deduped
                    GROUP BY workspace_id, project_id, trace_id
                ), trace_threads_final AS (
                    SELECT
                        workspace_id,
                        project_id,
                        thread_id,
                        id as thread_model_id,
                        status,
                        tags,
                        created_by,
                        last_updated_by,
                        created_at,
                        last_updated_at,
                        environment
                    FROM trace_threads
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    <if(uuid_from_time)>
                        AND id >= :uuid_from_time
                        <if(uuid_to_time)>AND id \\<= :uuid_to_time<endif>
                    <else>
                        <if(traces_final_ids)>
                            AND thread_id IN (SELECT thread_id FROM traces_final_ids)
                        <endif>
                    <endif>
                    <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                    ORDER BY (workspace_id, project_id, thread_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ), feedback_scores_deduped AS (
                    SELECT *
                    FROM (
                        SELECT
                            workspace_id,
                            project_id,
                            entity_id,
                            name,
                            category_name,
                            value,
                            reason,
                            source,
                            created_by,
                            last_updated_by,
                            created_at,
                            last_updated_at,
                            feedback_scores.last_updated_by AS author,
                            CAST('' AS FixedString(36)) AS source_queue_id
                        FROM feedback_scores
                        WHERE entity_type = 'thread'
                          AND workspace_id = :workspace_id
                          AND project_id IN :project_id
                          AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                        UNION ALL
                        SELECT
                            workspace_id,
                            project_id,
                            entity_id,
                            name,
                            category_name,
                            value,
                            reason,
                            source,
                            created_by,
                            last_updated_by,
                            created_at,
                            last_updated_at,
                            author,
                            source_queue_id
                        FROM authored_feedback_scores
                        WHERE entity_type = 'thread'
                           AND workspace_id = :workspace_id
                           AND project_id IN :project_id
                           AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                           <if(annotation_queue_id)>AND source_queue_id = :annotation_queue_id<endif>
                    )
                    ORDER BY last_updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
                ), feedback_scores_grouped AS (
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        groupArray(tuple(value, reason, category_name, source, author, created_by, last_updated_by, created_at, last_updated_at, source_queue_id)) AS entries
                    FROM feedback_scores_deduped
                    GROUP BY workspace_id, project_id, entity_id, name
                ), feedback_scores_final AS (
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                        IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                        IF(length(entries) = 1, entries[1].2, arrayStringConcat(arrayMap(e -> if(e.2 = '', '\\<no reason>', e.2), entries), ', ')) AS reason,
                        entries[1].4 AS source,
                        mapFromArrays(
                            arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                            arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, '', '', e.10, e.5), entries)
                        ) AS value_by_author,
                        arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                        arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                        arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                        arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
                    FROM feedback_scores_grouped
                ), feedback_scores_agg AS (
                    SELECT
                        entity_id,
                        mapFromArrays(
                                groupArray(name),
                                groupArray(value)
                        ) AS feedback_scores
                    FROM feedback_scores_final
                    GROUP BY workspace_id, project_id, entity_id
                ), thread_annotation_queue_ids AS (
                     SELECT thread_id,
                            groupArray(id) AS annotation_queue_ids
                     FROM (
                        SELECT DISTINCT aq.id as id, aqi.item_id as thread_id
                        FROM annotation_queue_items aqi
                        JOIN annotation_queues aq ON aq.id = aqi.queue_id
                        WHERE aq.scope = 'thread'
                          AND workspace_id = :workspace_id
                          AND project_id = :project_id
                          <if(uuid_from_time)> AND aqi.item_id >= :uuid_from_time <endif>
                          <if(uuid_to_time)> AND aqi.item_id \\<= :uuid_to_time <endif>
                     ) AS annotation_queue_ids_with_thread_id
                     GROUP BY thread_id
                )
                <if(feedback_scores_empty_filters)>
                 , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                     FROM (
                        SELECT *
                        FROM feedback_scores_final
                        ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                        LIMIT 1 BY entity_id, name
                     )
                     GROUP BY entity_id
                     HAVING <feedback_scores_empty_filters>
                )
                <endif>
                SELECT
                    t.workspace_id as workspace_id,
                    t.project_id as project_id,
                    t.id as id,
                    t.start_time as start_time,
                    t.end_time as end_time,
                    t.duration as duration,
                    t.first_message as first_message,
                    t.last_message as last_message,
                    t.number_of_messages as number_of_messages,
                    t.total_estimated_cost as total_estimated_cost,
                    t.usage as usage,
                    if(tt.created_by = '', t.created_by, tt.created_by) as created_by,
                    if(tt.last_updated_by = '', t.last_updated_by, tt.last_updated_by) as last_updated_by,
                    if(tt.last_updated_at == toDateTime64(0, 6, 'UTC'), t.last_updated_at, tt.last_updated_at) as last_updated_at,
                    if(tt.created_at = toDateTime64(0, 9, 'UTC'), t.created_at, tt.created_at) as created_at,
                    if(tt.status = 'unknown', 'active', tt.status) as status,
                    if(LENGTH(CAST(tt.thread_model_id AS Nullable(String))) > 0, tt.thread_model_id, NULL) as thread_model_id,
                    tt.tags as tags,
                    fsagg.feedback_scores as feedback_scores
                FROM (
                    SELECT
                        t.thread_id as id,
                        t.workspace_id as workspace_id,
                        t.project_id as project_id,
                        min(t.start_time) as start_time,
                        max(t.end_time) as end_time,
                        if(max(t.end_time) IS NOT NULL AND notEquals(max(t.end_time), toDateTime64('1970-01-01 00:00:00.000', 9)) AND min(t.start_time) IS NOT NULL
                               AND notEquals(min(t.start_time), toDateTime64('1970-01-01 00:00:00.000', 9)),
                           (dateDiff('microsecond', min(t.start_time), max(t.end_time)) / 1000.0),
                           NULL) AS duration,
                        argMin(t.input, t.start_time) as first_message,
                        argMax(t.output, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as last_message,
                        count(DISTINCT t.id) * 2 as number_of_messages,
                        sum(s.total_estimated_cost) as total_estimated_cost,
                        sumMap(s.usage) as usage,
                        max(t.last_updated_at) as last_updated_at,
                        argMax(t.last_updated_by, t.last_updated_at) as last_updated_by,
                        argMin(t.created_by, t.created_at) as created_by,
                        min(t.created_at) as created_at
                    FROM traces_final AS t
                        LEFT JOIN spans_agg AS s ON t.id = s.trace_id
                    GROUP BY
                        t.workspace_id, t.project_id, t.thread_id
                ) AS t
                <if(uuid_from_time)>INNER<else>LEFT<endif> JOIN trace_threads_final AS tt ON t.workspace_id = tt.workspace_id
                    AND t.project_id = tt.project_id
                    AND t.id = tt.thread_id
                LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = tt.thread_model_id
                <if(annotation_queue_filters || annotation_queue_id)>
                LEFT JOIN thread_annotation_queue_ids as ttaqi ON ttaqi.thread_id = tt.thread_model_id
                <endif>
                WHERE workspace_id = :workspace_id
                <if(feedback_scores_filters)>
                AND thread_model_id IN (
                    SELECT
                        entity_id
                    FROM (
                        SELECT *
                        FROM feedback_scores_final
                        ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                        LIMIT 1 BY entity_id, name
                    )
                    GROUP BY entity_id
                    HAVING <feedback_scores_filters>
                )
                <endif>
                <if(feedback_scores_empty_filters)>
                AND (
                    thread_model_id IN (SELECT entity_id FROM fsc WHERE fsc.feedback_scores_count = 0)
                        OR
                    thread_model_id NOT IN (SELECT entity_id FROM fsc)
                )
                <endif>
                <if(trace_thread_filters)>AND<trace_thread_filters><endif>
                <if(annotation_queue_filters)> AND <annotation_queue_filters> <endif>
                <if(annotation_queue_id)> AND has(ttaqi.annotation_queue_ids, :annotation_queue_id) <endif>
            ) AS threads
            GROUP BY threads.workspace_id, threads.project_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull TraceThreadSortingFactory traceThreadSortingFactory;
    private final @NonNull OpikConfiguration configuration;

    /**
     * 在 {@code traceColumnsNonNullable} 下应用的排序映射：线程的 {@code end_time} 是
     * {@code max(traces.end_time)}，当每条 trace 都未完成时为 epoch 哨兵值；{@code nullIf} 会将其恢复为
     * {@code NULL}，使其像 Nullable 列那样在 ASC 排序中排在最后。{@code duration} 无需条目——ClickHouse
     * 会将 {@code NaN} 视同 {@code NULL} 进行排序。
     */
    private static final Map<String, String> SORT_FIELD_MAPPING_END_TIME_SENTINEL = Map.of(
            SortableFields.END_TIME, "nullIf(end_time, toDateTime64('1970-01-01 00:00:00.000', 9))");

    /**
     * 确定是否激活 traces_final_ids CTE，以缩小线程列表和计数查询中原始 traces / spans 的扫描范围。
     * 与 {@code TraceDAO#shouldUseTraceIdPrefilter} 保持一致。
     *
     * <p>仅当存在工作区/项目/uuid 范围过滤之外的收窄谓词时才激活：
     * 即自由文本搜索或 TRACE 策略过滤（{@code <filters>}）。当两者都不存在时，
     * 下游 CTE 会直接应用 uuid 范围，跳过预过滤扫描。
     */
    private static boolean shouldUseTracesFinalIdsPrefilter(TraceSearchCriteria criteria, ST template) {
        return criteria.searchText() != null || template.getAttribute("filters") != null;
    }

    /**
     * OPIK-7035：导致 page-pushdown 不安全的模板属性。pushdown 通过一次窄范围的 {@code traces} 扫描
     * （{@code page_thread_ids}：每个线程的 min/max 排序键 + 过滤器，与 {@code trace_threads} 连接以进行
     * {@code last_updated_at} 合并，limit 提前下推）来解析页面，然后仅对该页面进行富化，
     * 从而使宽列 {@code input}/{@code output} 列和 spans 仅针对约一页大小的线程读取，而非整个项目。
     * 仅当页面成员关系和排序完全由那次窄范围的 {@code traces}+{@code trace_threads} 扫描决定时，它才是
     * 输出等价的。出现这些属性中的任何一个，都意味着页面只能由完整的富化查询来解析（需要
     * spans/feedback/annotation 连接的过滤器/排序，或使用不同 INNER 连接的 uuid 时间范围路径），
     * 因此我们会回退。
     */
    private static final List<String> PAGE_PUSHDOWN_DISQUALIFIERS = List.of(
            "sort_fields", "uuid_from_time", "uuid_to_time", "traces_pushdown_filter",
            "feedback_scores_filters", "feedback_scores_empty_filters",
            "span_feedback_scores_filters", "span_feedback_scores_empty_filters",
            "trace_aggregation_filters", "trace_thread_filters", "annotation_queue_filters",
            "annotation_queue_id", "experiment_filters", "guardrails_filters", "last_retrieved_id",
            "stream");

    /**
     * page-pushdown 仅适用于常见的列表场景：对 {@code trace_threads} 的新近度使用默认排序，
     * 且没有任何需要宽列 / spans / feedback / annotation 连接来确定哪些线程落在页面上的过滤器/排序。
     * 否则将按原样运行完整的扫描查询。
     */
    private static boolean isPagePushdownEligible(ST template) {
        return PAGE_PUSHDOWN_DISQUALIFIERS.stream().noneMatch(attr -> template.getAttribute(attr) != null);
    }

    @Override
    @WithSpan
    public Mono<TraceThread.TraceThreadPage> find(int size, int page, @NonNull TraceSearchCriteria criteria) {

        return makeMonoContextAware((userName, workspaceId) -> asyncTemplate
                .nonTransaction(connection -> countThreadTotal(criteria, connection, userName, workspaceId)
                        .flatMap(count -> {

                            int offset = (page - 1) * size;

                            var template = newTraceThreadFindTemplate(SELECT_TRACES_THREADS_BY_PROJECT_IDS,
                                    criteria,
                                    THREAD_SEARCH_CLAUSE,
                                    traceColumnsNonNullable());

                            template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());

                            template = template.add("offset", offset)
                                    .add("log_comment", getLogComment("find_threads_by_project", workspaceId, userName,
                                            "page:" + page + ":size:" + size));

                            var finalTemplate = template;
                            Optional.ofNullable(sortingQueryBuilder.toOrderBySql(
                                    criteria.sortingFields(),
                                    traceColumnsNonNullable() ? SORT_FIELD_MAPPING_END_TIME_SENTINEL : null))
                                    .ifPresent(sortFields -> finalTemplate.add("sort_fields", sortFields));

                            var hasDynamicKeys = sortingQueryBuilder.hasDynamicKeys(criteria.sortingFields());

                            // OPIK-7035：先解析页面（对 traces 做一次窄范围的 filter+order 扫描，
                            // limit 提前下推），然后仅对该页面进行富化，从而使宽列 input/output 列
                            // 和 spans 仅针对约一页大小的线程读取，而非整个项目。页面
                            // 解析器会自行应用过滤器，因此它与 traces_final_ids 预过滤互斥——
                            // 同时启用两者会扫描项目 traces 两次，并在 ClickHouse CTE 重新求值下叠加开销。
                            // 对于任何需要连接来源才能确定页面的过滤器/排序，回退到完整查询。
                            if (isPagePushdownEligible(finalTemplate)) {
                                finalTemplate.add("page_pushdown", true);
                            } else if (shouldUseTracesFinalIdsPrefilter(criteria, finalTemplate)) {
                                finalTemplate.add("traces_final_ids", true);
                            }

                            var statement = connection.createStatement(template.render())
                                    .bind("project_id", criteria.projectId())
                                    .bind("limit", size)
                                    .bind("offset", offset)
                                    .bind("workspace_id", workspaceId);

                            if (hasDynamicKeys) {
                                statement = sortingQueryBuilder.bindDynamicKeys(statement, criteria.sortingFields());
                            }

                            bindTraceThreadSearchCriteria(criteria, statement);

                            InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "findThreads");

                            return Flux.from(statement.execute())
                                    .flatMap(this::mapThreadToDto)
                                    .collectList()
                                    .doFinally(signalType -> endSegment(segment))
                                    .map(threads -> new TraceThread.TraceThreadPage(page, threads.size(), count,
                                            threads,
                                            traceThreadSortingFactory.getSortableFields()))
                                    .defaultIfEmpty(TraceThread.TraceThreadPage.empty(page,
                                            traceThreadSortingFactory.getSortableFields()));
                        })));
    }

    private boolean traceColumnsNonNullable() {
        return configuration.getDatabaseAnalyticsDataModel().traceColumnsNonNullable();
    }

    @Override
    public Mono<TraceThread> findById(@NonNull UUID projectId, @NonNull String threadId, boolean truncate) {
        return makeMonoContextAware((userName, workspaceId) -> asyncTemplate.nonTransaction(connection -> {
            var template = TemplateUtils.newST(SELECT_TRACES_THREAD_BY_ID);
            template.add("truncate", truncate)
                    .add("log_comment",
                            getLogComment("find_thread_by_id", workspaceId, userName, threadId));

            var statement = connection.createStatement(template.render())
                    .bind("project_id", projectId)
                    .bind("thread_id", threadId)
                    .bind("workspace_id", workspaceId);

            InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "findThreadById");

            return firstThreadOrEmpty(Mono.from(statement.execute())
                    .flatMapMany(this::mapThreadToDto))
                    .doFinally(signalType -> endSegment(segment));
        }));
    }

    /**
     * 将按 id 查询的线程流折叠为第一个匹配的线程，未找到时返回空。
     * 有意使用 {@code reduce} 而非 {@code single()}/{@code singleOrEmpty()}：
     * 对于单个 {@code (workspace_id, project_id, thread_id)}，SELECT_TRACES_THREAD_BY_ID
     * 可以合法地返回多行。trace_threads 表的去重/排序键包含内部的 thread_model_id（{@code id}），
     * 因此一个面向用户的 thread_id 可能同时存在多个 thread_model_id；trace_threads_final 为每个 id
     * 保留一行（{@code LIMIT 1 BY id}），而最终的 {@code LEFT JOIN ... ON (workspace_id, project_id, thread_id)}
     * （不是按 id 连接）会将单个聚合的 trace 行展开为每个 thread_model_id 一行。每一行映射到一个
     * TraceThread，因此 {@code singleOrEmpty()} 会抛出 {@code IndexOutOfBoundsException}
     * （"Source emitted more than one item"），并表现为未映射的 HTTP 500。折叠到第一条发射结果可容忍
     * 多行结果，同时保留未找到（空）的约定。
     */
    @VisibleForTesting
    static Mono<TraceThread> firstThreadOrEmpty(Flux<TraceThread> threads) {
        return threads.reduce((existingThread, ignored) -> existingThread);
    }

    @Override
    @WithSpan
    public Flux<TraceThread> search(int limit, @NonNull TraceSearchCriteria criteria) {
        Preconditions.checkArgument(limit > 0, "limit must be greater than 0");

        return makeFluxContextAware((userName, workspaceId) -> asyncTemplate.stream(connection -> {

            var template = newTraceThreadFindTemplate(SELECT_TRACES_THREADS_BY_PROJECT_IDS,
                    criteria,
                    THREAD_SEARCH_CLAUSE,
                    traceColumnsNonNullable());
            template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());

            template.add("limit", limit)
                    .add("stream", true)
                    .add("log_comment", getLogComment("search_threads", workspaceId, userName,
                            "limit:" + limit));

            if (shouldUseTracesFinalIdsPrefilter(criteria, template)) {
                template.add("traces_final_ids", true);
            }

            var statement = connection.createStatement(template.render())
                    .bind("project_id", criteria.projectId())
                    .bind("limit", limit)
                    .bind("workspace_id", workspaceId);

            bindTraceThreadSearchCriteria(criteria, statement);

            InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "threadsSearch");

            return Flux.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        }))
                .flatMap(this::mapThreadToDto)
                .buffer(limit > 100 ? limit / 2 : limit)
                .concatWith(Mono.just(List.of()))
                .filter(CollectionUtils::isNotEmpty)
                .flatMap(Flux::fromIterable);
    }

    @Override
    public Mono<ProjectStats> getThreadStats(@NonNull TraceSearchCriteria criteria) {
        return makeMonoContextAware((userName, workspaceId) -> asyncTemplate.nonTransaction(connection -> {

            var statsSQL = newTraceThreadFindTemplate(SELECT_TRACE_THREADS_STATS,
                    criteria,
                    THREAD_SEARCH_CLAUSE,
                    traceColumnsNonNullable());
            statsSQL.add("log_comment", getLogComment("thread_stats", workspaceId, userName, ""));

            if (shouldUseTracesFinalIdsPrefilter(criteria, statsSQL)) {
                statsSQL.add("traces_final_ids", true);
            }

            var statement = connection.createStatement(statsSQL.render())
                    .bind("project_id", criteria.projectId())
                    .bind("workspace_id", workspaceId);

            bindTraceThreadSearchCriteria(criteria, statement);

            InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "stats");

            return Flux.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(
                            result -> result
                                    .map((row, rowMetadata) -> StatsMapper.mapProjectStats(row, "thread_count")))
                    .singleOrEmpty();
        }));
    }

    private Mono<Long> countThreadTotal(TraceSearchCriteria traceSearchCriteria, Connection connection,
            String userName, String workspaceId) {
        var template = newTraceThreadFindTemplate(SELECT_COUNT_TRACES_THREADS_BY_PROJECT_IDS,
                traceSearchCriteria,
                THREAD_SEARCH_CLAUSE,
                traceColumnsNonNullable());
        template.add("log_comment", getLogComment("count_threads_by_project", workspaceId, userName, ""));

        if (shouldUseTracesFinalIdsPrefilter(traceSearchCriteria, template)) {
            template.add("traces_final_ids", true);
        }

        if (template.getAttribute("feedback_scores_filters") != null
                || template.getAttribute("feedback_scores_empty_filters") != null) {
            template.add("feedback_scores_needed", true);
        }

        var statement = connection.createStatement(template.render())
                .bind("project_id", traceSearchCriteria.projectId())
                .bind("workspace_id", workspaceId);

        bindTraceThreadSearchCriteria(traceSearchCriteria, statement);

        InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "countThreads");

        return Flux.from(statement.execute())
                .doFinally(signalType -> endSegment(segment))
                .flatMap(result -> result.map((row, rowMetadata) -> row.get("count", Long.class)))
                .reduce(0L, Long::sum);
    }

    private Publisher<TraceThread> mapThreadToDto(Result result) {
        return result.map((row, rowMetadata) -> TraceThread.builder()
                .id(row.get("id", String.class))
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(row.get("project_id", UUID.class))
                .startTime(row.get("start_time", Instant.class))
                .endTime(readEpochSentinel(row, "end_time"))
                .duration(row.get("duration", Double.class))
                .firstMessage(Optional.ofNullable(row.get("first_message", String.class))
                        .filter(it -> !it.isBlank())
                        .map(value -> TruncationUtils.getJsonNodeOrTruncatedString(rowMetadata,
                                "first_message_truncated", row, value))
                        .orElse(null))
                .lastMessage(Optional.ofNullable(row.get("last_message", String.class))
                        .filter(it -> !it.isBlank())
                        .map(value -> TruncationUtils.getJsonNodeOrTruncatedString(rowMetadata,
                                "last_message_truncated", row, value))
                        .orElse(null))
                .numberOfMessages(row.get("number_of_messages", Long.class))
                .usage(row.get("usage", Map.class))
                .totalEstimatedCost(Optional.ofNullable(row.get("total_estimated_cost", BigDecimal.class))
                        .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                        .orElse(null))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .createdBy(row.get("created_by", String.class))
                .createdAt(row.get("created_at", Instant.class))
                .status(TraceThreadStatus.fromValue(row.get("status", String.class)).orElse(TraceThreadStatus.ACTIVE))
                .threadModelId(Optional.ofNullable(row.get("thread_model_id", String.class))
                        .filter(StringUtils::isNotBlank)
                        .map(UUID::fromString)
                        .orElse(null))
                .feedbackScores(Optional.ofNullable(row.get("feedback_scores_list", List.class))
                        .filter(not(List::isEmpty))
                        .map(FeedbackScoreMapper::mapFeedbackScores)
                        .orElse(null))
                .comments(Optional
                        .ofNullable(row.get("comments", List[].class))
                        .map(CommentResultMapper::getComments)
                        .orElse(null))
                .tags(Optional
                        .ofNullable(row.get("tags", String[].class))
                        .map(tags -> Arrays.stream(tags).collect(Collectors.toSet()))
                        .filter(set -> !set.isEmpty())
                        .orElse(null))
                .environment(row.get("environment", String.class))
                .build());
    }

    /**
     * 读取 {@code DateTime64} 列，仅当列变为非空时才将 epoch 哨兵值转换为 {@code null}——
     * 与 {@code TraceDAO} 对称，因此在列仍为 {@code Nullable} 时会保留合法的 epoch 值。
     */
    private Instant readEpochSentinel(Row row, String fieldName) {
        var value = row.get(fieldName, Instant.class);
        return traceColumnsNonNullable() ? epochToNull(value) : value;
    }
}
