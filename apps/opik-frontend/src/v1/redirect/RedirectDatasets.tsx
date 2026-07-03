import React, { useEffect } from "react";
import { useTranslation } from "react-i18next";
import Loader from "@/shared/Loader/Loader";
import { StringParam, useQueryParams } from "use-query-params";
import useAppStore from "@/store/AppStore";
import { Link, Navigate, useNavigate } from "@tanstack/react-router";
import NoData from "@/shared/NoData/NoData";
import useDatasetItemByName from "@/api/datasets/useDatasetItemByName";
import { Button } from "@/ui/button";

const RedirectDatasets = () => {
  const { t } = useTranslation();
  const [query] = useQueryParams({
    id: StringParam,
    name: StringParam,
  });

  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: datasetByName, isPending: isPendingDatasetByName } =
    useDatasetItemByName(
      { datasetName: query.name || "" },
      { enabled: !!query.name && !query.id },
    );

  useEffect(() => {
    if (datasetByName?.id) {
      navigate({
        to: "/$workspaceName/test-suites/$suiteId/items",
        params: {
          suiteId: datasetByName.id,
          workspaceName,
        },
      });
    }
  }, [datasetByName?.id, workspaceName, navigate]);

  if (query.id) {
    return <Navigate to={`/${workspaceName}/test-suites/${query.id}/items`} />;
  }

  if (!isPendingDatasetByName && !datasetByName) {
    return (
      <NoData
        icon={<div className="comet-title-m mb-1 text-foreground">404</div>}
        title={t("redirectDatasets.testSuiteNotFound")}
        message={t("redirectDatasets.testSuiteNotFoundMessage")}
      >
        <div className="pt-5">
          <Link to="/$workspaceName/home" params={{ workspaceName }}>
            <Button>{t("redirectDatasets.backToHome")}</Button>
          </Link>
        </div>
      </NoData>
    );
  }

  if (!query.id && !query.name) {
    return <NoData message={t("redirectDatasets.noTestSuiteParams")} />;
  }

  return <Loader />;
};

export default RedirectDatasets;
