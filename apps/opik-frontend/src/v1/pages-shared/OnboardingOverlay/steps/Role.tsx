import React from "react";
import { useTranslation } from "react-i18next";
import OnboardingStep from "../OnboardingStep";

const Role: React.FC = () => {
  const { t } = useTranslation("onboarding");

  const options = [
    t("roles.softwareDeveloper"),
    t("roles.mlEngineerDataScientist"),
    t("roles.productManager"),
    t("roles.other"),
  ];

  return (
    <OnboardingStep className="mt-16">
      <OnboardingStep.Title>
        {t("roleQuestion")}
      </OnboardingStep.Title>

      <OnboardingStep.AnswerList>
        {options.map((option) => (
          <OnboardingStep.AnswerButton key={option} option={option} />
        ))}
      </OnboardingStep.AnswerList>

      <OnboardingStep.Skip />
    </OnboardingStep>
  );
};

export default Role;
