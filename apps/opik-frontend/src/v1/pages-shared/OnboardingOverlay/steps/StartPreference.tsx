import React from "react";
import { Link } from "@tanstack/react-router";
import { useFeatureFlagVariantKey } from "posthog-js/react";
import { useTranslation } from "react-i18next";
import useAppStore from "@/store/AppStore";
import OnboardingStep from "@/v1/pages-shared/OnboardingOverlay/OnboardingStep";
import { usePermissions } from "@/contexts/PermissionsContext";

const FEATURE_FLAG_KEY = "onboarding-start-exploring-test";

const StartPreference: React.FC = () => {
  const { t } = useTranslation("onboarding");
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const variant = useFeatureFlagVariantKey(FEATURE_FLAG_KEY);

  const {
    permissions: { canViewExperiments },
  } = usePermissions();

  const OPTIONS = {
    TRACE_APP: `${t("startOptions.traceApp")} – ${t("startOptions.traceAppDesc")}`,
    TEST_PROMPTS: `${t("startOptions.testPrompts")} – ${t("startOptions.testPromptsDesc")}`,
    RUN_EVALUATIONS: `${t("startOptions.runEvaluations")} – ${t("startOptions.runEvaluationsDesc")}`,
  } as const;

  // A/B test: control shows "Skip", test shows "Start exploring Opik"
  // Enhanced flow also opens create experiment dialog when clicking "Run evaluations"
  const showEnhancedOnboarding = variant === "test";

  return (
    <OnboardingStep className="max-w-full">
      <OnboardingStep.BackButton />
      <OnboardingStep.Title>{t("howWouldYouLikeToStart")}</OnboardingStep.Title>

      <OnboardingStep.AnswerList className="w-full gap-4 space-y-0 lg:flex-row">
        {showEnhancedOnboarding ? (
          <Link
            to="/$workspaceName/home"
            params={{ workspaceName }}
            search={{ quickstart: 1 }}
            className="w-full"
          >
            <OnboardingStep.AnswerCard option={OPTIONS.TRACE_APP} />
          </Link>
        ) : (
          <OnboardingStep.AnswerCard option={OPTIONS.TRACE_APP} />
        )}

        <Link
          to="/$workspaceName/playground"
          params={{ workspaceName }}
          className="w-full"
        >
          <OnboardingStep.AnswerCard option={OPTIONS.TEST_PROMPTS} />
        </Link>

        {canViewExperiments && (
          <Link
            to="/$workspaceName/experiments"
            params={{ workspaceName }}
            search={
              showEnhancedOnboarding
                ? {
                    new: {
                      experiment: true,
                      datasetName: "Opik Demo Questions",
                    },
                  }
                : undefined
            }
            className="w-full"
          >
            <OnboardingStep.AnswerCard option={OPTIONS.RUN_EVALUATIONS} />
          </Link>
        )}
      </OnboardingStep.AnswerList>

      {showEnhancedOnboarding ? (
        <Link to="/$workspaceName/home" params={{ workspaceName }}>
          <OnboardingStep.StartExploring />
        </Link>
      ) : (
        <OnboardingStep.Skip />
      )}
    </OnboardingStep>
  );
};

export default StartPreference;
