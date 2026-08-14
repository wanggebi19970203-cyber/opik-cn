// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../index.js";

export type AutomationRuleEvaluator =
    | OpikApi.AutomationRuleEvaluator.LlmAsJudge
    | OpikApi.AutomationRuleEvaluator.UserDefinedMetricPython
    | OpikApi.AutomationRuleEvaluator.TraceThreadLlmAsJudge
    | OpikApi.AutomationRuleEvaluator.TraceThreadUserDefinedMetricPython
    | OpikApi.AutomationRuleEvaluator.SpanLlmAsJudge
    | OpikApi.AutomationRuleEvaluator.SpanUserDefinedMetricPython;

export namespace AutomationRuleEvaluator {
    export interface LlmAsJudge extends OpikApi.AutomationRuleEvaluatorLlmAsJudge, _Base {
        type: "llm_as_judge";
    }

    export interface UserDefinedMetricPython extends OpikApi.AutomationRuleEvaluatorUserDefinedMetricPython, _Base {
        type: "user_defined_metric_python";
    }

    export interface TraceThreadLlmAsJudge extends OpikApi.AutomationRuleEvaluatorTraceThreadLlmAsJudge, _Base {
        type: "trace_thread_llm_as_judge";
    }

    export interface TraceThreadUserDefinedMetricPython
        extends OpikApi.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython,
            _Base {
        type: "trace_thread_user_defined_metric_python";
    }

    export interface SpanLlmAsJudge extends OpikApi.AutomationRuleEvaluatorSpanLlmAsJudge, _Base {
        type: "span_llm_as_judge";
    }

    export interface SpanUserDefinedMetricPython
        extends OpikApi.AutomationRuleEvaluatorSpanUserDefinedMetricPython,
            _Base {
        type: "span_user_defined_metric_python";
    }

    export interface _Base {
        id?: string;
        /** 主项目 ID（用于向后兼容的旧字段） */
        projectId?: string;
        /** 主项目名称（用于向后兼容的旧字段） */
        projectName?: string;
        /** 分配给此规则的项目（唯一，按名称字母顺序排序） */
        projects?: OpikApi.ProjectReference[];
        /** 用于写入操作的项目 ID（在创建/更新规则时使用） */
        projectIds?: string[];
        name: string;
        samplingRate?: number;
        enabled?: boolean;
        /** 控制规则是否在生产 trace、实验 trace 或两者上触发。若省略则默认为 'production'。 */
        triggerScope?: OpikApi.AutomationRuleEvaluatorTriggerScope;
        createdAt?: Date;
        createdBy?: string;
        lastUpdatedAt?: Date;
        lastUpdatedBy?: string;
        action: OpikApi.AutomationRuleEvaluatorAction;
    }
}
