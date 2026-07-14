import React, { Fragment } from "react";
import { useTranslation } from "react-i18next";

import { Tag } from "@/ui/tag";
import { Separator } from "@/ui/separator";
import { AssertionResult } from "@/types/datasets";

type AssertionResultsTableProps = {
  assertions: AssertionResult[];
};

export const AssertionResultsTable: React.FC<AssertionResultsTableProps> = ({
  assertions,
}) => {
  const { t } = useTranslation("experiments");
  if (assertions.length === 0) return null;

  return (
    <div className="flex max-h-full flex-col rounded-md border border-border px-2 py-1.5">
      <div className="grid grid-cols-2 items-center p-1">
        <span className="comet-body-xs-accented text-foreground">
          {t("assertions")}
        </span>
        <span className="comet-body-xs-accented px-2 text-foreground">
          {t("result")}
        </span>
      </div>
      <Separator className="my-1" />
      <div className="min-h-0 flex-1 overflow-auto">
        {assertions.map((a, idx) => (
          <Fragment key={idx}>
            <div className="grid grid-cols-2 items-start">
              <div className="px-2 py-1">
                <div className="pt-1">
                  <span className="comet-body-xs text-muted-slate">
                    {a.value}
                  </span>
                </div>
              </div>
              <div className="flex flex-col gap-0.5 px-2 pb-1 pt-0.5">
                <div>
                  <Tag variant={a.passed ? "green" : "red"}>
                    {a.passed ? t("passed") : t("failed")}
                  </Tag>
                </div>
                {a.reason && (
                  <span className="comet-body-xs overflow-hidden text-ellipsis text-muted-slate">
                    {a.reason}
                  </span>
                )}
              </div>
            </div>
            {idx < assertions.length - 1 && (
              <Separator className="my-1 bg-[var(--separator-light)]" />
            )}
          </Fragment>
        ))}
      </div>
    </div>
  );
};

export default AssertionResultsTable;
