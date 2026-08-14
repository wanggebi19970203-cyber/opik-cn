// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../index.js";

export type AutomationRuleEvaluatorUpdate =
    | OpikApi.AutomationRuleEvaluatorUpdate.LlmAsJudge
    | OpikApi.AutomationRuleEvaluatorUpdate.UserDefinedMetricPython
    | OpikApi.AutomationRuleEvaluatorUpdate.TraceThreadLlmAsJudge
    | OpikApi.AutomationRuleEvaluatorUpdate.TraceThreadUserDefinedMetricPython
    | OpikApi.AutomationRuleEvaluatorUpdate.SpanLlmAsJudge
    | OpikApi.AutomationRuleEvaluatorUpdate.SpanUserDefinedMetricPython;

export namespace AutomationRuleEvaluatorUpdate {
    export interface LlmAsJudge extends OpikApi.AutomationRuleEvaluatorUpdateLlmAsJudge, _Base {
        type: "llm_as_judge";
    }

    export interface UserDefinedMetricPython
        extends OpikApi.AutomationRuleEvaluatorUpdateUserDefinedMetricPython,
            _Base {
        type: "user_defined_metric_python";
    }

    export interface TraceThreadLlmAsJudge extends OpikApi.AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge, _Base {
        type: "trace_thread_llm_as_judge";
    }

    export interface TraceThreadUserDefinedMetricPython
        extends OpikApi.AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython,
            _Base {
        type: "trace_thread_user_defined_metric_python";
    }

    export interface SpanLlmAsJudge extends OpikApi.AutomationRuleEvaluatorUpdateSpanLlmAsJudge, _Base {
        type: "span_llm_as_judge";
    }

    export interface SpanUserDefinedMetricPython
        extends OpikApi.AutomationRuleEvaluatorUpdateSpanUserDefinedMetricPython,
            _Base {
        type: "span_user_defined_metric_python";
    }

    export interface _Base {
        name: string;
        samplingRate?: number;
        enabled?: boolean;
        triggerScope?: OpikApi.AutomationRuleEvaluatorUpdateTriggerScope;
        /** 主项目 ID（旧字段，为向后兼容而保留） */
        projectId?: string;
        /** 多个项目 ID（用于多项目支持的新字段） */
        projectIds?: string[];
        action: OpikApi.AutomationRuleEvaluatorUpdateAction;
    }
}
