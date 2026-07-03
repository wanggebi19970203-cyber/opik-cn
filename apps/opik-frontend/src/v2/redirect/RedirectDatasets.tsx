import React, { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { StringParam, useQueryParams } from "use-query-params";
import { Link, useNavigate } from "@tanstack/react-router";

import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import Loader from "@/shared/Loader/Loader";
import NoData from "@/shared/NoData/NoData";
import useDatasetItemByName from "@/api/datasets/useDatasetItemByName";
import useDatasetById from "@/api/datasets/useDatasetById";
import { Button } from "@/ui/button";

const RedirectDatasets: React.FC = () => {
  const { t } = useTranslation();
  const [query] = useQueryParams({
    id: StringParam,
    name: StringParam,
  });

  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();

  const { data: datasetById, isPending: isPendingDatasetById } = useDatasetById(
    { datasetId: query.id || "" },
    { enabled: !!query.id },
  );

  const { data: datasetByName, isPending: isPendingDatasetByName } =
    useDatasetItemByName(
      { datasetName: query.name || "" },
      { enabled: !!query.name && !query.id },
    );

  const dataset = datasetById || datasetByName;
  const isPending = query.id ? isPendingDatasetById : isPendingDatasetByName;

  useEffect(() => {
    if (dataset?.id) {
      const projectId = dataset.project_id || activeProjectId;
      if (projectId) {
        navigate({
          to: "/$workspaceName/projects/$projectId/test-suites/$suiteId/items",
          params: {
            suiteId: dataset.id,
            workspaceName,
            projectId,
          },
          replace: true,
        });
      } else {
        // Legacy fallback: dataset exists but has no project_id and no active project
        navigate({
          to: "/$workspaceName/home",
          params: { workspaceName },
          replace: true,
        });
      }
    }
  }, [
    dataset?.id,
    dataset?.project_id,
    activeProjectId,
    workspaceName,
    navigate,
  ]);

  if (!query.id && !query.name) {
    return <NoData message={t("messages.noTestSuiteParamsSet")} />;
  }

  if (!isPending && !dataset) {
    return (
      <NoData
        icon={<div className="comet-title-m mb-1 text-foreground">404</div>}
        title={t("messages.testSuiteNotFound")}
        message={t("messages.testSuiteNotFoundDescription")}
      >
        <div className="pt-5">
          <Link to="/$workspaceName/home" params={{ workspaceName }}>
            <Button>{t("buttons.backToHome")}</Button>
          </Link>
        </div>
      </NoData>
    );
  }

  return <Loader />;
};

export default RedirectDatasets;
