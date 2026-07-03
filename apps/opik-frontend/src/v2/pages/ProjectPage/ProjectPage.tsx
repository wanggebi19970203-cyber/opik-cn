import React, { useEffect } from "react";
import last from "lodash/last";
import { Link, Navigate, Outlet, useLocation } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import useProjectById from "@/api/projects/useProjectById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useActiveProjectId, useActiveWorkspaceName } from "@/store/AppStore";
import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";
import { setActiveProject } from "@/hooks/useActiveProjectInitializer";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import Loader from "@/shared/Loader/Loader";
import NoData from "@/shared/NoData/NoData";
import { Button } from "@/ui/button";

const ProjectPage = () => {
  const { t } = useTranslation("pages/project-home");
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);
  const projectId = useProjectIdFromURL();
  const workspaceName = useActiveWorkspaceName();

  const activeProjectId = useActiveProjectId();
  const projectHomepageEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.PROJECT_HOMEPAGE_ENABLED,
  );

  const { data, isPending, isError } = useProjectById({
    projectId,
  });

  // The URL is the source of truth for the active project when we're on a
  // project route — but only after we've verified the project actually exists.
  // Writing the URL's projectId before verification would briefly point the
  // store at a non-existent id on 404/5xx; skipping the write on error keeps
  // the previously-valid activeProjectId so the sidebar doesn't flicker or
  // end up highlighting nothing real.
  useEffect(() => {
    if (isPending || isError || !data) return;
    if (activeProjectId !== projectId) {
      setActiveProject(workspaceName, projectId);
    }
  }, [isPending, isError, data, projectId, workspaceName, activeProjectId]);

  useEffect(() => {
    if (data?.name) {
      setBreadcrumbParam("projectId", projectId, data.name);
    }
  }, [projectId, data?.name, setBreadcrumbParam]);

  const pathname = useLocation({
    select: (location) => location.pathname,
  });

  if (isPending) {
    return <Loader />;
  }

  if (isError) {
    return (
      <NoData
        title={t("projectPage.somethingWentWrong")}
        message={t("projectPage.failedToLoad")}
      >
        <div className="pt-5">
          <Link to="/$workspaceName/projects" params={{ workspaceName }}>
            <Button>{t("projectPage.backToProjects")}</Button>
          </Link>
        </div>
      </NoData>
    );
  }

  if (!data) {
    return (
      <NoData
        icon={<div className="comet-title-m mb-1 text-foreground">404</div>}
        title={t("projectPage.projectNotFound")}
        message={t("projectPage.projectNotFoundMessage")}
      >
        <div className="pt-5">
          <Link to="/$workspaceName/projects" params={{ workspaceName }}>
            <Button>{t("projectPage.backToProjects")}</Button>
          </Link>
        </div>
      </NoData>
    );
  }

  // Hold the Outlet until the store catches up with the URL. Without this,
  // children read a stale activeProjectId for one render (the sync effect
  // fires after render) and kick off queries against the previous project.
  if (activeProjectId !== projectId) {
    return <Loader />;
  }

  if (last(pathname.split("/")) === projectId) {
    return (
      <Navigate to={pathname + (projectHomepageEnabled ? "/home" : "/logs")} />
    );
  }

  return <Outlet />;
};

export default ProjectPage;
