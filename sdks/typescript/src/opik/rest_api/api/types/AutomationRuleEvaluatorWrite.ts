// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../index.js";

export type AutomationRuleEvaluatorWrite =
    | OpikApi.AutomationRuleEvaluatorWrite.LlmAsJudge
    | OpikApi.AutomationRuleEvaluatorWrite.UserDefinedMetricPython
    | OpikApi.AutomationRuleEvaluatorWrite.TraceThreadLlmAsJudge
    | OpikApi.AutomationRuleEvaluatorWrite.TraceThreadUserDefinedMetricPython
    | OpikApi.AutomationRuleEvaluatorWrite.SpanLlmAsJudge
    | OpikApi.AutomationRuleEvaluatorWrite.SpanUserDefinedMetricPython;

export namespace AutomationRuleEvaluatorWrite {
    export interface LlmAsJudge extends OpikApi.AutomationRuleEvaluatorLlmAsJudgeWrite, _Base {
        type: "llm_as_judge";
    }

    export interface UserDefinedMetricPython
        extends OpikApi.AutomationRuleEvaluatorUserDefinedMetricPythonWrite,
            _Base {
        type: "user_defined_metric_python";
    }

    export interface TraceThreadLlmAsJudge extends OpikApi.AutomationRuleEvaluatorTraceThreadLlmAsJudgeWrite, _Base {
        type: "trace_thread_llm_as_judge";
    }

    export interface TraceThreadUserDefinedMetricPython
        extends OpikApi.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPythonWrite,
            _Base {
        type: "trace_thread_user_defined_metric_python";
    }

    export interface SpanLlmAsJudge extends OpikApi.AutomationRuleEvaluatorSpanLlmAsJudgeWrite, _Base {
        type: "span_llm_as_judge";
    }

    export interface SpanUserDefinedMetricPython
        extends OpikApi.AutomationRuleEvaluatorSpanUserDefinedMetricPythonWrite,
            _Base {
        type: "span_user_defined_metric_python";
    }

    export interface _Base {
        /** 主项目 ID（用于向后兼容的旧字段） */
        projectId?: string;
        /** 用于写入操作的项目 ID（在创建/更新规则时使用） */
        projectIds?: string[];
        name: string;
        samplingRate?: number;
        enabled?: boolean;
        /** 控制规则是否在生产 trace、实验 trace 或两者上触发。若省略则默认为 'production'。 */
        triggerScope?: OpikApi.AutomationRuleEvaluatorWriteTriggerScope;
        action: OpikApi.AutomationRuleEvaluatorWriteAction;
    }
}
