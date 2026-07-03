import React from "react";
import { useTranslation } from "react-i18next";
import { Tag } from "@/ui/tag";
import { RunStatus } from "@/types/test-suites";

const STATUS_CONFIG: Record<
  RunStatus,
  { labelKey: string; variant: "green" | "red" | "gray" }
> = {
  [RunStatus.PASSED]: {
    labelKey: "passed",
    variant: "green",
  },
  [RunStatus.FAILED]: {
    labelKey: "failed",
    variant: "red",
  },
  [RunStatus.SKIPPED]: {
    labelKey: "skipped",
    variant: "gray",
  },
};

type PassFailBadgeProps = {
  status?: RunStatus;
};

const PassFailBadge: React.FC<PassFailBadgeProps> = ({ status }) => {
  const { t } = useTranslation("experiments");
  if (!status) return null;

  const config = STATUS_CONFIG[status];

  return <Tag variant={config.variant}>{t(config.labelKey)}</Tag>;
};

export default PassFailBadge;
