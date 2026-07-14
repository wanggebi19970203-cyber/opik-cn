import React from "react";
import { useTranslation } from "react-i18next";
import OnboardingStep from "../OnboardingStep";

const AIJourney: React.FC = () => {
  const { t } = useTranslation("onboarding");

  const options = [
    t("journeyOptions.justGettingStarted"),
    t("journeyOptions.exploringIdeas"),
    t("journeyOptions.testingPrototype"),
    t("journeyOptions.runningInProduction"),
  ];

  return (
    <OnboardingStep>
      <OnboardingStep.BackButton />
      <OnboardingStep.Title>{t("aiJourneyQuestion")}</OnboardingStep.Title>

      <OnboardingStep.AnswerList>
        {options.map((option) => (
          <OnboardingStep.AnswerButton key={option} option={option} />
        ))}
      </OnboardingStep.AnswerList>

      <OnboardingStep.Skip />
    </OnboardingStep>
  );
};

export default AIJourney;
