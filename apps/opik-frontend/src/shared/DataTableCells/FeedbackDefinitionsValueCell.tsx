import React from "react";
import { CellContext } from "@tanstack/react-table";
import { useTranslation } from "react-i18next";

import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/shared/DataTableCells/CellTooltipWrapper";

const FeedbackDefinitionsValueCell = (
  context: CellContext<FeedbackDefinition, string>,
) => {
  const { t } = useTranslation();
  const feedbackDefinition = context.row.original;

  let items: React.ReactNode = null;
  let tooltipContent: string | undefined;

  if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.categorical) {
    const categoryList = Object.keys(
      feedbackDefinition.details.categories || [],
    )
      .sort()
      .join(", ");
    items = categoryList;
    tooltipContent = categoryList;
  } else if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.numerical) {
    const numericText = `${t("common:labels.min")}: ${feedbackDefinition.details.min}, ${t("common:labels.max")}: ${feedbackDefinition.details.max}`;
    items = <p>{numericText}</p>;
    tooltipContent = numericText;
  } else if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.boolean) {
    const booleanText = `${feedbackDefinition.details.true_label}, ${feedbackDefinition.details.false_label}`;
    items = booleanText;
    tooltipContent = booleanText;
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <CellTooltipWrapper content={tooltipContent}>
        <div className="min-w-0 truncate">{items}</div>
      </CellTooltipWrapper>
    </CellWrapper>
  );
};

export default FeedbackDefinitionsValueCell;
