import React from "react";
import { useTranslation } from "react-i18next";
import { Tag } from "@/ui/tag";
import { RunStatus } from "@/types/test-suites";

const STATUS_VARIANT_MAP: Record<RunStatus, "green" | "red" | "gray"> = {
  [RunStatus.PASSED]: "green",
  [RunStatus.FAILED]: "red",
  [RunStatus.SKIPPED]: "gray",
};

const STATUS_I18N_KEY: Record<RunStatus, string> = {
  [RunStatus.PASSED]: "passed",
  [RunStatus.FAILED]: "failed",
  [RunStatus.SKIPPED]: "skipped",
};

type PassFailBadgeProps = {
  status?: RunStatus;
};

const PassFailBadge: React.FC<PassFailBadgeProps> = ({ status }) => {
  const { t } = useTranslation("experiments");
  if (!status) return null;

  return (
    <Tag variant={STATUS_VARIANT_MAP[status]}>{t(STATUS_I18N_KEY[status])}</Tag>
  );
};

export default PassFailBadge;
