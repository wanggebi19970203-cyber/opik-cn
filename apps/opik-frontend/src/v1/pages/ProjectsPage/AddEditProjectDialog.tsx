import React, { useCallback, useState } from "react";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import useProjectCreateMutation from "@/api/projects/useProjectCreateMutation";
import { Project } from "@/types/projects";
import { Textarea } from "@/ui/textarea";
import useProjectUpdateMutation from "@/api/projects/useProjectUpdateMutation";
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v1/constants/explainers";
import ExplainerDescription from "@/shared/ExplainerDescription/ExplainerDescription";
import { useToast } from "@/ui/use-toast";
import { ToastAction } from "@/ui/toast";
import { usePermissions } from "@/contexts/PermissionsContext";
import { useTranslation } from "react-i18next";

type AddEditProjectDialogProps = {
  project?: Project;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddEditProjectDialog: React.FC<AddEditProjectDialogProps> = ({
  project,
  open,
  setOpen,
}) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();

  const { mutate: createMutate } = useProjectCreateMutation();
  const { mutate: updateMutate } = useProjectUpdateMutation();
  const [name, setName] = useState(project ? project.name : "");
  const [description, setDescription] = useState(
    project ? project.description : "",
  );

  const {
    permissions: { canCreateProjects },
  } = usePermissions();

  const isEdit = Boolean(project);
  const isValid = Boolean(name.length);
  const title = isEdit
    ? t("projects:projects.dialog.editTitle")
    : t("projects:projects.dialog.createTitle");
  const buttonText = isEdit
    ? t("projects:projects.dialog.updateButton")
    : t("projects:projects.dialog.createButton");

  const onProjectCreated = useCallback(
    (projectData?: { id?: string }) => {
      const explainer =
        EXPLAINERS_MAP[EXPLAINER_ID.i_created_a_project_now_what];

      toast({
        title: explainer.title,
        description: explainer.description,
        actions: [
          <ToastAction
            variant="link"
            size="sm"
            className="px-0"
            altText={t("projects:projects.dialog.logTracesLink")}
            key="Log traces to your project"
          >
            <a href={explainer.docLink} target="_blank" rel="noreferrer">
              {t("projects:projects.dialog.logTracesLink")}
            </a>
          </ToastAction>,
        ],
      });

      if (projectData?.id) {
        navigate({
          to: "/$workspaceName/projects/$projectId/traces",
          params: {
            projectId: projectData.id,
            workspaceName,
          },
        });
      }
    },
    [navigate, toast, workspaceName],
  );

  const submitHandler = useCallback(() => {
    if (isEdit) {
      updateMutate({
        project: {
          id: project!.id,
          description,
        },
      });
    } else {
      createMutate(
        {
          project: {
            name,
            ...(description && { description }),
          },
        },
        {
          onSuccess: onProjectCreated,
        },
      );
    }
  }, [
    createMutate,
    description,
    isEdit,
    name,
    onProjectCreated,
    project,
    updateMutate,
  ]);

  return (
    <Dialog open={open && canCreateProjects} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        {!isEdit && (
          <ExplainerDescription
            className="mb-4"
            {...EXPLAINERS_MAP[
              EXPLAINER_ID.why_would_i_want_to_create_a_new_project
            ]}
          />
        )}
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="projectName">
            {t("projects:projects.dialog.nameLabel")}
          </Label>
          <Input
            id="projectName"
            placeholder={t("projects:projects.fields.name")}
            disabled={isEdit}
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </div>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="projectDescription">
            {t("projects:projects.dialog.descriptionLabel")}
          </Label>
          <Textarea
            id="projectDescription"
            placeholder={t("projects:projects.fields.description")}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            maxLength={255}
          />
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">
              {t("projects:projects.dialog.cancel")}
            </Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" disabled={!isValid} onClick={submitHandler}>
              {buttonText}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditProjectDialog;
