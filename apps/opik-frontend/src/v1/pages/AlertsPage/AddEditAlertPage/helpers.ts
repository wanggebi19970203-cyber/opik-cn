import i18next from "i18next";
import cloneDeep from "lodash/cloneDeep";
import get from "lodash/get";
import set from "lodash/set";
import isEmpty from "lodash/isEmpty";
import isString from "lodash/isString";
import isNil from "lodash/isNil";
import { WebhookIcon } from "lucide-react";

import {
  ALERT_EVENT_TYPE,
  ALERT_TYPE,
  AlertTrigger,
  ALERT_TRIGGER_CONFIG_TYPE,
  AlertTriggerConfig,
  Alert,
} from "@/types/alerts";
import { TriggerFormType, FeedbackScoreConditionType } from "./schema";
import SlackIcon from "@/icons/slack.svg?react";
import PagerDutyIcon from "@/icons/pagerduty.svg?react";

export interface TriggerConfig {
  title: string;
  description: string;
  hasScope: boolean;
}

export const ALERT_TYPE_LABELS: Record<ALERT_TYPE, string> = {
  [ALERT_TYPE.general]: i18next.t("alerts.typeGeneral"),
  [ALERT_TYPE.slack]: "Slack",
  [ALERT_TYPE.pagerduty]: "PagerDuty",
};

export const ALERT_TYPE_ICONS = {
  [ALERT_TYPE.general]: WebhookIcon,
  [ALERT_TYPE.slack]: SlackIcon,
  [ALERT_TYPE.pagerduty]: PagerDutyIcon,
};

export const TRIGGER_CONFIG: Record<ALERT_EVENT_TYPE, TriggerConfig> = {
  [ALERT_EVENT_TYPE.trace_errors]: {
    title: i18next.t("alerts.trigger.traceErrorsTitle"),
    description: i18next.t("alerts.trigger.traceErrorsDescription"),
    hasScope: true,
  },
  [ALERT_EVENT_TYPE.trace_guardrails_triggered]: {
    title: i18next.t("alerts.trigger.guardrailTriggeredTitle"),
    description: i18next.t("alerts.trigger.guardrailTriggeredDescription"),
    hasScope: true,
  },
  [ALERT_EVENT_TYPE.trace_feedback_score]: {
    title: i18next.t("alerts.trigger.traceFeedbackScoreTitle"),
    description: i18next.t("alerts.trigger.traceFeedbackScoreDescription"),
    hasScope: true,
  },
  [ALERT_EVENT_TYPE.trace_thread_feedback_score]: {
    title: i18next.t("alerts.trigger.threadFeedbackScoreTitle"),
    description: i18next.t("alerts.trigger.threadFeedbackScoreDescription"),
    hasScope: true,
  },
  [ALERT_EVENT_TYPE.prompt_created]: {
    title: i18next.t("alerts.trigger.promptCreatedTitle"),
    description: i18next.t("alerts.trigger.promptCreatedDescription"),
    hasScope: false,
  },
  [ALERT_EVENT_TYPE.prompt_committed]: {
    title: i18next.t("alerts.trigger.promptCommittedTitle"),
    description: i18next.t("alerts.trigger.promptCommittedDescription"),
    hasScope: false,
  },
  [ALERT_EVENT_TYPE.prompt_deleted]: {
    title: i18next.t("alerts.trigger.promptDeletedTitle"),
    description: i18next.t("alerts.trigger.promptDeletedDescription"),
    hasScope: false,
  },
  [ALERT_EVENT_TYPE.experiment_finished]: {
    title: i18next.t("alerts.trigger.experimentFinishedTitle"),
    description: i18next.t("alerts.trigger.experimentFinishedDescription"),
    hasScope: false,
  },
  [ALERT_EVENT_TYPE.trace_cost]: {
    title: i18next.t("alerts.trigger.costThresholdTitle"),
    description: i18next.t("alerts.trigger.costThresholdDescription"),
    hasScope: true,
  },
  [ALERT_EVENT_TYPE.trace_latency]: {
    title: i18next.t("alerts.trigger.latencyThresholdTitle"),
    description: i18next.t("alerts.trigger.latencyThresholdDescription"),
    hasScope: true,
  },
};

const getProjectIdsFromTriggerConfigs = (
  triggerConfigs?: AlertTriggerConfig[],
): string[] => {
  const projectConfig = triggerConfigs?.find(
    (config) => config.type === ALERT_TRIGGER_CONFIG_TYPE["scope:project"],
  );

  if (projectConfig) {
    const projectIds = projectConfig.config_value?.project_ids;
    if (!projectIds || projectIds.trim() === "") {
      return [];
    }
    try {
      // Parse JSON array format
      return JSON.parse(projectIds);
    } catch {
      // Fallback to comma-separated format for backwards compatibility
      return projectIds.split(",");
    }
  }

  return [];
};

const getThresholdFromTriggerConfigs = (
  configType: ALERT_TRIGGER_CONFIG_TYPE,
  triggerConfigs?: AlertTriggerConfig[],
): {
  threshold?: string;
  window?: string;
  name?: string;
  operator?: string;
} => {
  const thresholdConfig = triggerConfigs?.find(
    (config) => config.type === configType,
  );

  if (thresholdConfig?.config_value) {
    return {
      threshold: thresholdConfig.config_value.threshold,
      window: thresholdConfig.config_value.window,
      name: thresholdConfig.config_value.name,
      operator: thresholdConfig.config_value.operator,
    };
  }

  return {};
};

const getAllThresholdConditionsFromTriggerConfigs = (
  configType: ALERT_TRIGGER_CONFIG_TYPE,
  triggerConfigs?: AlertTriggerConfig[],
): FeedbackScoreConditionType[] => {
  if (!triggerConfigs) return [];

  const conditions = triggerConfigs
    .filter((config) => config.type === configType)
    .map((config) => ({
      threshold: config.config_value?.threshold || "",
      window: config.config_value?.window || "",
      name: config.config_value?.name || "",
      operator: config.config_value?.operator || ">",
    }))
    .filter(
      (condition) => condition.threshold && condition.window && condition.name,
    );

  return conditions;
};

const createProjectScopeTriggerConfig = (
  projectIds: string[],
): AlertTriggerConfig[] => {
  if (projectIds.length === 0) return [];

  return [
    {
      type: ALERT_TRIGGER_CONFIG_TYPE["scope:project"],
      config_value: {
        project_ids: JSON.stringify(projectIds),
      },
    },
  ];
};

const createThresholdTriggerConfig = (
  configType: ALERT_TRIGGER_CONFIG_TYPE,
  threshold?: string,
  window?: string,
  name?: string,
  operator?: string,
): AlertTriggerConfig[] => {
  if (!threshold || !window) return [];

  const config_value: Record<string, string> = {
    threshold,
    window,
  };

  // Add name and operator for feedback score triggers
  if (name) {
    config_value.name = name;
  }
  if (operator) {
    config_value.operator = operator;
  }

  return [
    {
      type: configType,
      config_value,
    },
  ];
};

export const alertTriggersToFormTriggers = (
  triggers: AlertTrigger[],
  allProjectIds: string[],
): TriggerFormType[] => {
  if (!triggers || triggers.length === 0) return [];

  return triggers.map((trigger) => {
    const triggerProjectIds = getProjectIdsFromTriggerConfigs(
      trigger.trigger_configs,
    );

    // Extract threshold and window for cost/latency/errors triggers
    let thresholdData = {};
    if (trigger.event_type === ALERT_EVENT_TYPE.trace_cost) {
      thresholdData = getThresholdFromTriggerConfigs(
        ALERT_TRIGGER_CONFIG_TYPE["threshold:cost"],
        trigger.trigger_configs,
      );
    } else if (trigger.event_type === ALERT_EVENT_TYPE.trace_latency) {
      thresholdData = getThresholdFromTriggerConfigs(
        ALERT_TRIGGER_CONFIG_TYPE["threshold:latency"],
        trigger.trigger_configs,
      );
    } else if (trigger.event_type === ALERT_EVENT_TYPE.trace_errors) {
      thresholdData = getThresholdFromTriggerConfigs(
        ALERT_TRIGGER_CONFIG_TYPE["threshold:errors"],
        trigger.trigger_configs,
      );
    }

    // Extract multiple conditions for feedback score triggers
    let conditions: FeedbackScoreConditionType[] = [];
    if (
      trigger.event_type === ALERT_EVENT_TYPE.trace_feedback_score ||
      trigger.event_type === ALERT_EVENT_TYPE.trace_thread_feedback_score
    ) {
      conditions = getAllThresholdConditionsFromTriggerConfigs(
        ALERT_TRIGGER_CONFIG_TYPE["threshold:feedback_score"],
        trigger.trigger_configs,
      );
    }

    return {
      eventType: trigger.event_type,
      projectIds:
        triggerProjectIds.length > 0 ? triggerProjectIds : allProjectIds,
      ...thresholdData,
      ...(conditions.length > 0 ? { conditions } : {}),
    };
  });
};

export const formTriggersToAlertTriggers = (
  triggers: TriggerFormType[],
  allProjectIds: string[],
): AlertTrigger[] => {
  return triggers.map((trigger) => {
    const configs: AlertTriggerConfig[] = [];

    // Add project scope config if needed
    if (trigger.projectIds.length !== allProjectIds.length) {
      configs.push(...createProjectScopeTriggerConfig(trigger.projectIds));
    }

    // Add threshold config for cost/latency/errors triggers
    if (trigger.eventType === ALERT_EVENT_TYPE.trace_cost) {
      configs.push(
        ...createThresholdTriggerConfig(
          ALERT_TRIGGER_CONFIG_TYPE["threshold:cost"],
          trigger.threshold,
          trigger.window,
        ),
      );
    } else if (trigger.eventType === ALERT_EVENT_TYPE.trace_latency) {
      configs.push(
        ...createThresholdTriggerConfig(
          ALERT_TRIGGER_CONFIG_TYPE["threshold:latency"],
          trigger.threshold,
          trigger.window,
        ),
      );
    } else if (trigger.eventType === ALERT_EVENT_TYPE.trace_errors) {
      configs.push(
        ...createThresholdTriggerConfig(
          ALERT_TRIGGER_CONFIG_TYPE["threshold:errors"],
          trigger.threshold,
          trigger.window,
        ),
      );
    } else if (
      trigger.eventType === ALERT_EVENT_TYPE.trace_feedback_score ||
      trigger.eventType === ALERT_EVENT_TYPE.trace_thread_feedback_score
    ) {
      // Add multiple threshold configs for feedback scores (one per condition)
      if (trigger.conditions && trigger.conditions.length > 0) {
        trigger.conditions.forEach((condition) => {
          configs.push(
            ...createThresholdTriggerConfig(
              ALERT_TRIGGER_CONFIG_TYPE["threshold:feedback_score"],
              condition.threshold,
              condition.window,
              condition.name,
              condition.operator,
            ),
          );
        });
      }
    }

    return {
      event_type: trigger.eventType,
      trigger_configs: configs,
    };
  });
};

// Field mapping configuration for webhook examples
export interface FieldMapping {
  sourceField: string; // Path to field in alert object (e.g., 'name' or 'metadata.routing_key')
  targetPath: string; // Path to replace in webhook example (e.g., 'alert_name' or 'payload.routing_key')
  fallbackValue?: string; // Optional fallback if field is empty
}

type AlertTypeMappings = {
  [key in ALERT_TYPE]: FieldMapping[];
};

export const ALERT_FIELD_MAPPINGS: AlertTypeMappings = {
  [ALERT_TYPE.general]: [
    {
      sourceField: "name",
      targetPath: "alert_name",
    },
  ],
  [ALERT_TYPE.pagerduty]: [
    {
      sourceField: "name",
      targetPath: "payload.summary",
    },
    {
      sourceField: "metadata.routing_key",
      targetPath: "routing_key",
    },
  ],
  [ALERT_TYPE.slack]: [
    {
      sourceField: "name",
      targetPath: "blocks[0].text.text",
    },
  ],
};

/**
 * Checks if a value is considered valid for field replacement.
 * A value is invalid if it's null, undefined, empty, or a whitespace-only string.
 */
function isValidFieldValue(value: unknown): boolean {
  // Use lodash isNil to check for null/undefined
  if (isNil(value)) {
    return false;
  }

  // Use lodash isEmpty for strings, arrays, objects
  if (isEmpty(value)) {
    return false;
  }

  // Use lodash isString and check for whitespace-only strings
  if (isString(value) && value.trim() === "") {
    return false;
  }

  return true;
}

export function applyFieldReplacements(
  examplePayload: unknown,
  alert: Partial<Alert>,
  alertType: ALERT_TYPE,
): unknown {
  const mappings = ALERT_FIELD_MAPPINGS[alertType];

  // Use lodash isEmpty to check if mappings array is empty
  if (isEmpty(mappings)) {
    return examplePayload;
  }

  try {
    // Use lodash cloneDeep to avoid mutating original
    const payload = cloneDeep(examplePayload) as Record<string, unknown>;

    mappings.forEach((mapping) => {
      try {
        // Use lodash get to safely retrieve nested values
        const sourceValue = get(alert, mapping.sourceField);

        if (isValidFieldValue(sourceValue)) {
          // Use lodash set to safely set nested values (supports dot and bracket notation)
          set(payload, mapping.targetPath, sourceValue);
        } else if (!isNil(mapping.fallbackValue)) {
          set(payload, mapping.targetPath, mapping.fallbackValue);
        }
        // If source value is invalid and no fallback, keep original example value
      } catch (error) {
        console.error(
          `Failed to apply field mapping for ${mapping.sourceField} -> ${mapping.targetPath}:`,
          error,
        );
      }
    });

    return payload;
  } catch (error) {
    console.error(
      "Failed to apply field replacements to webhook example:",
      error,
    );
    return examplePayload;
  }
}
