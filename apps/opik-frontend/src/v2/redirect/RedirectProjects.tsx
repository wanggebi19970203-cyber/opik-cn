import React, { useEffect } from "react";
import { useTranslation } from "react-i18next";
import Loader from "@/shared/Loader/Loader";
import { StringParam, useQueryParams } from "use-query-params";
import useAppStore from "@/store/AppStore";
import { Link, Navigate, useNavigate } from "@tanstack/react-router";
import useProjectByName from "@/api/projects/useProjectByName";
import NoData from "@/shared/NoData/NoData";
import { Button } from "@/ui/button";

const RedirectProjects = () => {
  const { t } = useTranslation();
  const [query] = useQueryParams({
    id: StringParam,
    name: StringParam,
  });

  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: projectByName, isPending: isPendingProjectByName } =
    useProjectByName(
      { projectName: query.name || "" },
      { enabled: !!query.name && !query.id },
    );

  useEffect(() => {
    if (projectByName?.id) {
      navigate({
        to: "/$workspaceName/projects/$projectId/home",
        params: {
          projectId: projectByName.id,
          workspaceName,
        },
      });
    }
  }, [projectByName?.id, workspaceName, navigate]);

  if (query.id) {
    return <Navigate to={`/${workspaceName}/projects/${query.id}/home`} />;
  }

  if (!isPendingProjectByName && !projectByName) {
    return (
      <NoData
        icon={<div className="comet-title-m mb-1 text-foreground">404</div>}
        title={t("messages.projectNotFound")}
        message={t("messages.projectNotFoundDescription")}
      >
        <div className="pt-5">
          <Link to="/$workspaceName/home" params={{ workspaceName }}>
            <Button>{t("buttons.backToHome")}</Button>
          </Link>
        </div>
      </NoData>
    );
  }

  if (!query.id && !query.name) {
    return <NoData message={t("messages.noProjectParamsSet")} />;
  }

  return <Loader />;
};

export default RedirectProjects;
