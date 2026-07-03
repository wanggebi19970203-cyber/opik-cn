import React from "react";
import { useTranslation } from "react-i18next";
import { Link } from "@tanstack/react-router";
import { ArrowUpRight } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { Tag } from "@/ui/tag";
import { Button } from "@/ui/button";
import { Filter } from "@/types/filters";
import { LOGS_TYPE, PROJECT_TAB } from "@/constants/traces";

export enum RESOURCE_TYPE {
  project,
  dataset,
  datasetItem,
  prompt,
  experiment,
  experimentItem,
  optimization,
  trial,
  testSuite,
  annotationQueue,
  dashboard,
  traces,
  threads,
}

export const RESOURCE_MAP = {
  [RESOURCE_TYPE.project]: {
    url: "/$workspaceName/projects/$projectId/traces",
    projectUrl: "/$workspaceName/projects/$projectId/home",
    param: "projectId",
    deletedKey: "deletedProject",
    label: "project",
  },
  [RESOURCE_TYPE.dataset]: {
    url: "/$workspaceName/datasets/$datasetId/items",
    projectUrl: "/$workspaceName/projects/$projectId/datasets/$datasetId/items",
    param: "datasetId",
    deletedKey: "deletedDataset",
    label: "dataset",
  },
  [RESOURCE_TYPE.datasetItem]: {
    url: "/$workspaceName/datasets/$datasetId/items",
    projectUrl: "/$workspaceName/projects/$projectId/datasets/$datasetId/items",
    param: "datasetId",
    deletedKey: "deletedDatasetItem",
    label: "dataset item",
  },
  [RESOURCE_TYPE.prompt]: {
    url: "/$workspaceName/prompts/$promptId",
    projectUrl: "/$workspaceName/projects/$projectId/prompts/$promptId",
    param: "promptId",
    deletedKey: "deletedPrompt",
    label: "prompt",
  },
  [RESOURCE_TYPE.experiment]: {
    url: "/$workspaceName/experiments/$datasetId/compare",
    projectUrl:
      "/$workspaceName/projects/$projectId/experiments/$datasetId/compare",
    param: "datasetId",
    deletedKey: "deletedExperiment",
    label: "experiment",
  },
  [RESOURCE_TYPE.experimentItem]: {
    url: "/$workspaceName/experiments/$datasetId/compare",
    projectUrl:
      "/$workspaceName/projects/$projectId/experiments/$datasetId/compare",
    param: "datasetId",
    deletedKey: "deletedExperimentItem",
    label: "experiment item",
  },
  [RESOURCE_TYPE.optimization]: {
    url: "/$workspaceName/optimizations/$optimizationId",
    projectUrl:
      "/$workspaceName/projects/$projectId/optimizations/$optimizationId",
    param: "optimizationId",
    deletedKey: "deletedOptimization",
    label: "optimization run",
  },
  [RESOURCE_TYPE.trial]: {
    url: "/$workspaceName/optimizations/$optimizationId/trials",
    projectUrl:
      "/$workspaceName/projects/$projectId/optimizations/$optimizationId/trials",
    param: "optimizationId",
    deletedKey: "deletedOptimization",
    label: "trial",
  },
  [RESOURCE_TYPE.annotationQueue]: {
    url: "/$workspaceName/annotation-queues/$annotationQueueId",
    projectUrl:
      "/$workspaceName/projects/$projectId/annotation-queues/$annotationQueueId",
    param: "annotationQueueId",
    deletedKey: "deletedAnnotationQueue",
    label: "annotation queue",
  },
  [RESOURCE_TYPE.dashboard]: {
    url: "/$workspaceName/dashboards/$dashboardId",
    param: "dashboardId",
    deletedKey: "deletedDashboard",
    label: "dashboard",
  },
  [RESOURCE_TYPE.traces]: {
    url: "/$workspaceName/projects/$projectId/traces",
    projectUrl: "/$workspaceName/projects/$projectId/logs",
    param: "projectId",
    deletedKey: "deletedTraces",
    label: "traces",
    search: { tab: PROJECT_TAB.logs, logsType: LOGS_TYPE.traces },
    projectSearch: { logsType: LOGS_TYPE.traces },
  },
  [RESOURCE_TYPE.testSuite]: {
    url: "/$workspaceName/test-suites/$suiteId/items",
    projectUrl:
      "/$workspaceName/projects/$projectId/test-suites/$suiteId/items",
    param: "suiteId",
    deletedKey: "deletedTestSuite",
    label: "test suite",
  },
  [RESOURCE_TYPE.threads]: {
    url: "/$workspaceName/projects/$projectId/traces",
    projectUrl: "/$workspaceName/projects/$projectId/logs",
    param: "projectId",
    deletedKey: "deletedThreads",
    label: "threads",
    search: { tab: PROJECT_TAB.logs, logsType: LOGS_TYPE.threads },
    projectSearch: { logsType: LOGS_TYPE.threads },
  },
};

export type ResourceLinkProps = {
  name?: string;
  id: string;
  resource: RESOURCE_TYPE;
  search?: Record<string, string | number | string[] | Filter[]>;
  params?: Record<string, string | number | string[]>;
  tooltipContent?: string | false;
  asTag?: boolean;
  isSmall?: boolean;
  isDeleted?: boolean;
  prefix?: string;
  suffix?: React.ReactNode;
  className?: string;
};

function ResourceLink({
  resource,
  name,
  id,
  search,
  params,
  tooltipContent = "",
  asTag = false,
  isSmall = false,
  isDeleted = false,
  prefix,
  suffix,
  className,
}: ResourceLinkProps): React.ReactElement {
  const { t } = useTranslation();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const props = RESOURCE_MAP[resource];

  const useProjectUrl =
    activeProjectId && "projectUrl" in props && props.projectUrl;
  const url = useProjectUrl ? props.projectUrl : props.url;

  const linkParams: Record<string, string> = {
    workspaceName,
    ...params,
  };
  if (useProjectUrl) {
    linkParams.projectId = activeProjectId;
  }
  linkParams[props.param] = id;

  const deleted = isUndefined(name) || isDeleted;
  const text = deleted ? t(`common.shared.${props.deletedKey}`) : name;

  return (
    <Link
      to={url}
      params={linkParams}
      search={{
        ...(useProjectUrl && "projectSearch" in props
          ? props.projectSearch
          : "search" in props
            ? props.search
            : {}),
        ...search,
      }}
      onClick={(event) => event.stopPropagation()}
      className="max-w-full"
      disabled={deleted}
    >
      {asTag ? (
        <TooltipWrapper
          content={tooltipContent === false ? "" : tooltipContent || text}
          stopClickPropagation
        >
          <Tag
            size="md"
            variant="transparent"
            className={cn(
              "flex items-center gap-1",
              deleted && "opacity-50 cursor-default",
              !deleted &&
                "hover:bg-primary-foreground hover:text-foreground active:bg-primary-100 active:text-foreground",
              isSmall && "size-8 justify-center",
              className,
            )}
          >
            {!isSmall &&
              (deleted ? (
                <div className="comet-body-s-accented truncate text-foreground">
                  {text}
                </div>
              ) : (
                <div className="comet-body-s truncate text-foreground">
                  {prefix ? `${prefix}: ` : ""}
                  <span className="comet-body-s-accented">{text}</span>
                </div>
              ))}
            {!isSmall && !deleted && suffix}
            <ArrowUpRight className="size-3 shrink-0 text-foreground" />
          </Tag>
        </TooltipWrapper>
      ) : (
        <Button
          variant="tableLink"
          size="sm"
          disabled={deleted}
          className="block h-auto truncate px-0 text-[length:inherit] leading-normal"
          asChild
        >
          <span>{text}</span>
        </Button>
      )}
    </Link>
  );
}

export default ResourceLink;
