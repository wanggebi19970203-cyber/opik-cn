import React, { useState } from "react";
import { useTranslation } from "react-i18next";
import findIndex from "lodash/findIndex";

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { Experiment } from "@/types/datasets";
import { Button } from "@/ui/button";
import { useNavigate } from "@tanstack/react-router";
import { Checkbox } from "@/ui/checkbox";
import ExplainerDescription from "@/shared/ExplainerDescription/ExplainerDescription";

type FilterExperimentsToCompareDialogProps = {
  experiments: Experiment[];
  open: boolean;
  setOpen: (open: boolean) => void;
};

const FilterExperimentsToCompareDialog: React.FunctionComponent<
  FilterExperimentsToCompareDialogProps
> = ({ experiments, open, setOpen }) => {
  const { t } = useTranslation("experiments");
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const [selectedExperiments, setSelectedExperiments] = useState(experiments);
  const isValid = selectedExperiments.every(
    (e) => e.dataset_id === selectedExperiments[0].dataset_id,
  );

  const compareHandler = () => {
    if (!isValid) return;

    navigate({
      to: "/$workspaceName/projects/$projectId/experiments/$datasetId/compare",
      params: {
        datasetId: selectedExperiments[0].dataset_id,
        workspaceName,
        projectId: activeProjectId!,
      },
      search: {
        experiments: selectedExperiments.map((e) => e.id),
      },
    });
  };

  const checkboxChangeHandler = (e: Experiment) => {
    setSelectedExperiments((state) => {
      const localExperiments = state.slice();
      const index = findIndex(localExperiments, (le) => le.id === e.id);

      if (index !== -1) {
        localExperiments.splice(index, 1);
      } else {
        localExperiments.push(e);
      }

      return localExperiments;
    });
  };

  const renderListItems = () => {
    return experiments.map((e) => {
      const checked =
        findIndex(selectedExperiments, (se) => se.id === e.id) !== -1;
      return (
        <label
          key={e.id}
          className="flex cursor-pointer flex-col gap-0.5 py-2.5 pl-3 pr-4"
        >
          <div className="flex flex-col gap-0.5">
            <div className="flex items-center gap-2">
              <Checkbox
                checked={checked}
                onCheckedChange={() => checkboxChangeHandler(e)}
                aria-label={t("selectExperiment")}
                className="mt-0.5"
              />
              <span className="comet-body-s-accented truncate">{e.name}</span>
            </div>
            <div className="comet-body-s truncate pl-6 text-light-slate">
              {t("testSuitePrefix")} {e.dataset_name ?? t("deletedTestSuite")}
            </div>
          </div>
        </label>
      );
    });
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{t("selectExperimentsToCompare")}</DialogTitle>
        </DialogHeader>
        <div className="w-full overflow-hidden">
          <ExplainerDescription
            description={t("compareExperimentsDescription")}
          />
          <div className="my-4 flex max-h-[400px] min-h-36 max-w-full flex-col justify-stretch gap-2.5 overflow-y-auto">
            {renderListItems()}
          </div>
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">{t("common:buttons.cancel")}</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" disabled={!isValid} onClick={compareHandler}>
              {t("compareExperiments")}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default FilterExperimentsToCompareDialog;
