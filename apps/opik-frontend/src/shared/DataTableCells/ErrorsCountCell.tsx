import { CellContext } from "@tanstack/react-table";
import { useTranslation } from "react-i18next";
import { Tag } from "@/ui/tag";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { TriangleAlert, ZoomIn } from "lucide-react";
import CellTooltipWrapper from "./CellTooltipWrapper";
import { ProjectErrorCount } from "@/types/projects";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";
import { Button } from "@/ui/button";

type CustomMeta = {
  onZoomIn: (row: unknown) => void;
};

const getErrorDeviationCopy = (
  error: ProjectErrorCount,
  t: (key: string, opts?: Record<string, unknown>) => string,
) => {
  if (error.deviation === 0) {
    return `(${t("common:messages.noNewErrorsThisWeek")})`;
  }

  if (error.deviation_percentage < 0) {
    return `(${t("common:messages.decreaseSinceLastWeek", { percentage: error.deviation_percentage })})`;
  }

  if (error.deviation_percentage > 0) {
    return `(${t("common:messages.increaseSinceLastWeek", { percentage: error.deviation_percentage })})`;
  }

  if (error.deviation_percentage === 0) {
    return `(${t("common:messages.noChangeSinceLastWeek")})`;
  }

  return ``;
};

const ErrorsCountCell = (context: CellContext<unknown, ProjectErrorCount>) => {
  const { t } = useTranslation();
  const error = context.getValue();
  const { custom } = context.column.columnDef.meta ?? {};
  const { onZoomIn } = (custom ?? {}) as CustomMeta;
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  if (!error?.count) {
    return null;
  }

  const deviationCopy = getErrorDeviationCopy(error, t);

  const onClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    onZoomIn(context.row.original);
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="group relative"
      stopClickPropagation
    >
      <CellTooltipWrapper content={`${error.count} ${error.count === 1 ? t("common:labels.error") : t("common:labels.errors")} ${deviationCopy}`}>
        <Tag
          onClick={onClick}
          variant="red"
          size={tagSize}
          className="flex cursor-pointer items-center gap-1"
        >
          <TriangleAlert className="size-3 shrink-0" />
          <span>{error.count}</span>
          <span className="truncate">
            {error.count === 1 ? t("common:labels.error") : t("common:labels.errors")}
          </span>
        </Tag>
      </CellTooltipWrapper>

      <Button
        className="absolute right-1 opacity-0 group-hover:opacity-100"
        size="icon-xs"
        variant="outline"
        onClick={onClick}
      >
        <ZoomIn />
      </Button>
    </CellWrapper>
  );
};

export default ErrorsCountCell;
