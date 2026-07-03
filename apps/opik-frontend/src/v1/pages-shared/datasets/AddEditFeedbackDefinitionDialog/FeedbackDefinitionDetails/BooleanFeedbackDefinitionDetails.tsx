import React, { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { BooleanFeedbackDefinition } from "@/types/feedback-definitions";

const INVALID_DETAILS_VALUE: BooleanFeedbackDefinition["details"] = {
  true_label: "",
  false_label: "",
};

type BooleanFeedbackDefinitionDetailsProps = {
  onChange: (details: BooleanFeedbackDefinition["details"]) => void;
  details?: BooleanFeedbackDefinition["details"];
};

const BooleanFeedbackDefinitionDetails: React.FunctionComponent<
  BooleanFeedbackDefinitionDetailsProps
> = ({ onChange, details }) => {
  const { t } = useTranslation("datasets");
  const [booleanDetails, setBooleanDetails] = useState<
    BooleanFeedbackDefinition["details"]
  >(details ?? { true_label: t("datasets.feedbackDefinition.passLabel"), false_label: t("datasets.feedbackDefinition.failLabel") });

  useEffect(() => {
    const isValid =
      booleanDetails.true_label.trim() !== "" &&
      booleanDetails.false_label.trim() !== "";

    onChange(
      isValid
        ? (booleanDetails as BooleanFeedbackDefinition["details"])
        : INVALID_DETAILS_VALUE,
    );
  }, [booleanDetails, onChange]);

  return (
    <>
      <div className="flex flex-col gap-2 pb-4">
        <Label htmlFor="feedbackDefinitionBooleanTrueLabel">{t("datasets.feedbackDefinition.trueLabel")}</Label>
        <Input
          id="feedbackDefinitionBooleanTrueLabel"
          placeholder={t("datasets.feedbackDefinition.passLabel")}
          value={booleanDetails.true_label}
          onChange={(event) =>
            setBooleanDetails((details) => ({
              ...details,
              true_label: event.target.value,
            }))
          }
        />
      </div>

      <div className="flex flex-col gap-2 pb-4">
        <Label htmlFor="feedbackDefinitionBooleanFalseLabel">{t("datasets.feedbackDefinition.falseLabel")}</Label>
        <Input
          id="feedbackDefinitionBooleanFalseLabel"
          placeholder={t("datasets.feedbackDefinition.failLabel")}
          value={booleanDetails.false_label}
          onChange={(event) =>
            setBooleanDetails((details) => ({
              ...details,
              false_label: event.target.value,
            }))
          }
        />
      </div>
    </>
  );
};

export default BooleanFeedbackDefinitionDetails;
