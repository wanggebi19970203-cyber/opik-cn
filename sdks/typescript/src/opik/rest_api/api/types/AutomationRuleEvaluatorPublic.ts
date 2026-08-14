// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../index.js";

export type AutomationRuleEvaluatorPublic =
    | OpikApi.AutomationRuleEvaluatorPublic.LlmAsJudge
    | OpikApi.AutomationRuleEvaluatorPublic.UserDefinedMetricPython
    | OpikApi.AutomationRuleEvaluatorPublic.TraceThreadLlmAsJudge
    | OpikApi.AutomationRuleEvaluatorPublic.TraceThreadUserDefinedMetricPython
    | OpikApi.AutomationRuleEvaluatorPublic.SpanLlmAsJudge
    | OpikApi.AutomationRuleEvaluatorPublic.SpanUserDefinedMetricPython;

export namespace AutomationRuleEvaluatorPublic {
    export interface LlmAsJudge extends OpikApi.AutomationRuleEvaluatorLlmAsJudgePublic, _Base {
        type: "llm_as_judge";
    }

    export interface UserDefinedMetricPython
        extends OpikApi.AutomationRuleEvaluatorUserDefinedMetricPythonPublic,
            _Base {
        type: "user_defined_metric_python";
    }

    export interface TraceThreadLlmAsJudge extends OpikApi.AutomationRuleEvaluatorTraceThreadLlmAsJudgePublic, _Base {
        type: "trace_thread_llm_as_judge";
    }

    export interface TraceThreadUserDefinedMetricPython
        extends OpikApi.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPythonPublic,
            _Base {
        type: "trace_thread_user_defined_metric_python";
    }

    export interface SpanLlmAsJudge extends OpikApi.AutomationRuleEvaluatorSpanLlmAsJudgePublic, _Base {
        type: "span_llm_as_judge";
    }

    export interface SpanUserDefinedMetricPython
        extends OpikApi.AutomationRuleEvaluatorSpanUserDefinedMetricPythonPublic,
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
        projects?: OpikApi.ProjectReferencePublic[];
        name: string;
        samplingRate?: number;
        enabled?: boolean;
        /** 控制规则是否在生产 trace、实验 trace 或两者上触发。若省略则默认为 'production'。 */
        triggerScope?: OpikApi.AutomationRuleEvaluatorPublicTriggerScope;
        createdAt?: Date;
        createdBy?: string;
        lastUpdatedAt?: Date;
        lastUpdatedBy?: string;
        action: OpikApi.AutomationRuleEvaluatorPublicAction;
    }
}
