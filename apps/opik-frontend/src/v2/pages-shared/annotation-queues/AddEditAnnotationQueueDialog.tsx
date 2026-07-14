import React, { useCallback, useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import get from "lodash/get";
import { useTranslation } from "react-i18next";

import { cn } from "@/lib/utils";

import { Button } from "@/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import { Input } from "@/ui/input";
import { Textarea } from "@/ui/textarea";
import SelectBox from "@/shared/SelectBox/SelectBox";
import FeedbackDefinitionsSelectBox from "@/v2/pages-shared/annotation-queues/FeedbackDefinitionsSelectBox";

import {
  ANNOTATION_QUEUE_SCOPE,
  AnnotationQueue,
} from "@/types/annotation-queues";
import useAnnotationQueueCreateMutation from "@/api/annotation-queues/useAnnotationQueueCreateMutation";
import useAnnotationQueueUpdateMutation from "@/api/annotation-queues/useAnnotationQueueUpdateMutation";
import { DEFAULT_LOCK_TIMEOUT_SECONDS } from "@/lib/annotation-queues";
import { Separator } from "@/ui/separator";
import { Description } from "@/ui/description";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import { usePermissions } from "@/contexts/PermissionsContext";

const useScopeOptions = () => {
  const { t } = useTranslation("annotation-queues");
  return [
    {
      value: ANNOTATION_QUEUE_SCOPE.TRACE,
      label: t("annotationQueues.scopeOptions.traces"),
    },
    {
      value: ANNOTATION_QUEUE_SCOPE.THREAD,
      label: t("annotationQueues.scopeOptions.threads"),
    },
  ];
};

const createFormSchema = (t: (key: string) => string) =>
  z.object({
    project_id: z
      .string()
      .min(1, t("annotationQueues.validation.projectRequired")),
    name: z
      .string()
      .min(1, t("annotationQueues.validation.nameRequired"))
      .max(255, t("annotationQueues.validation.nameMaxLength")),
    description: z.string().optional(),
    instructions: z.string().optional(),
    scope: z.nativeEnum(ANNOTATION_QUEUE_SCOPE),
    comments_enabled: z.boolean(),
    feedback_definition_names: z.array(z.string()).default([]),
    annotators_per_item: z.coerce.number().int().min(1).default(1),
    lock_timeout_minutes: z.coerce
      .number()
      .int()
      .min(1)
      .max(60)
      .default(DEFAULT_LOCK_TIMEOUT_SECONDS / 60),
  });

type FormData = z.infer<ReturnType<typeof createFormSchema>>;

type AddEditAnnotationQueueDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onQueueCreated?: (queue: Partial<AnnotationQueue>) => void;
  projectId: string;
  scope?: ANNOTATION_QUEUE_SCOPE;
  queue?: AnnotationQueue;
};

const AddEditAnnotationQueueDialog: React.FunctionComponent<
  AddEditAnnotationQueueDialogProps
> = ({
  open,
  setOpen,
  projectId,
  scope,
  onQueueCreated,
  queue: defaultQueue,
}) => {
  const { t } = useTranslation("annotation-queues");
  const SCOPE_OPTIONS = useScopeOptions();
  const formSchema = useMemo(() => createFormSchema(t), [t]);

  const {
    permissions: { canCreateAnnotationQueues, canEditAnnotationQueues },
  } = usePermissions();

  const [isNestedDialogOpen, setIsNestedDialogOpen] = useState(false);

  const form = useForm<FormData>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      name: defaultQueue?.name || "",
      instructions: defaultQueue?.instructions || "",
      project_id: defaultQueue?.project_id || projectId || "",
      scope: defaultQueue?.scope || scope || ANNOTATION_QUEUE_SCOPE.TRACE,
      feedback_definition_names: defaultQueue?.feedback_definition_names || [],
      comments_enabled: defaultQueue?.comments_enabled || true,
      annotators_per_item: defaultQueue?.annotators_per_item || 1,
      lock_timeout_minutes:
        (defaultQueue?.lock_timeout_seconds ?? DEFAULT_LOCK_TIMEOUT_SECONDS) /
        60,
    },
  });

  const { mutate: createMutate } = useAnnotationQueueCreateMutation();
  const { mutate: updateMutate } = useAnnotationQueueUpdateMutation();

  const isEdit = Boolean(defaultQueue);
  const title = isEdit
    ? t("annotationQueues.dialog.editTitle")
    : t("annotationQueues.dialog.createTitle");
  const submitText = isEdit
    ? t("annotationQueues.dialog.updateButton")
    : t("annotationQueues.dialog.createButton");

  const getQueue = useCallback(() => {
    const formData = form.getValues();
    const { lock_timeout_minutes, ...rest } = formData;
    return {
      ...rest,
      project_id: formData.project_id,
      lock_timeout_seconds: lock_timeout_minutes * 60,
    };
  }, [form]);

  const onQueueCreatedEdited = useCallback(
    (queue: Partial<AnnotationQueue>) => {
      if (onQueueCreated) {
        onQueueCreated(queue);
      }
    },
    [onQueueCreated],
  );

  const createQueue = useCallback(() => {
    createMutate(
      {
        annotationQueue: getQueue(),
      },
      { onSuccess: onQueueCreatedEdited },
    );
    setOpen(false);
  }, [createMutate, getQueue, onQueueCreatedEdited, setOpen]);

  const editQueue = useCallback(() => {
    updateMutate(
      {
        annotationQueue: {
          id: defaultQueue?.id || "",
          ...getQueue(),
        },
      },
      { onSuccess: onQueueCreatedEdited },
    );
    setOpen(false);
  }, [updateMutate, defaultQueue?.id, getQueue, onQueueCreatedEdited, setOpen]);

  const onSubmit = useCallback(
    () => (isEdit ? editQueue() : createQueue()),
    [isEdit, editQueue, createQueue],
  );

  return (
    <Dialog
      open={
        open && (isEdit ? canEditAnnotationQueues : canCreateAnnotationQueues)
      }
      onOpenChange={setOpen}
    >
      <DialogContent
        className="max-w-lg sm:max-w-[790px]"
        hideOverlay={isNestedDialogOpen}
      >
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <Form {...form}>
            <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
              <FormField
                control={form.control}
                name="name"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, ["name"]);
                  return (
                    <FormItem>
                      <FormLabel>
                        {t("annotationQueues.dialog.nameLabel")}
                      </FormLabel>
                      <FormControl>
                        <Input
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                          placeholder={t(
                            "annotationQueues.dialog.namePlaceholder",
                          )}
                          {...field}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />
              <div className="flex gap-4">
                <FormField
                  control={form.control}
                  name="scope"
                  render={({ field }) => (
                    <FormItem className="flex-1">
                      <FormLabel>
                        {t("annotationQueues.dialog.scopeLabel")}{" "}
                        <ExplainerIcon
                          className="inline"
                          {...EXPLAINERS_MAP[
                            EXPLAINER_ID.how_to_choose_annotation_queue_type
                          ]}
                        />
                      </FormLabel>
                      <FormControl>
                        <SelectBox
                          placeholder={t("annotationQueues.filters.trace")}
                          value={field.value}
                          onChange={field.onChange}
                          options={SCOPE_OPTIONS}
                          disabled={isEdit || Boolean(scope)}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
              <Separator orientation="horizontal" className="my-4" />
              <div className="space-y-4">
                <div className="comet-body-s text-muted-slate">
                  {t("annotationQueues.dialog.annotationGuidelines")}
                </div>
                <Description>
                  {t("annotationQueues.dialog.annotationGuidelinesDescription")}
                </Description>
              </div>
              <div className="space-y-4">
                <FormField
                  control={form.control}
                  name="instructions"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>
                        {t("annotationQueues.dialog.instructionsLabel")}
                      </FormLabel>
                      <FormControl>
                        <Textarea
                          placeholder={t(
                            "annotationQueues.dialog.instructionsPlaceholder",
                          )}
                          rows={4}
                          {...field}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
              <div className="space-y-4">
                <FormField
                  control={form.control}
                  name="feedback_definition_names"
                  render={({ field, formState }) => {
                    const validationErrors = get(formState.errors, [
                      "feedback_definition_names",
                    ]);

                    return (
                      <FormItem>
                        <FormLabel>
                          {t("annotationQueues.dialog.feedbackScoresLabel")}{" "}
                          <ExplainerIcon
                            className="inline"
                            {...EXPLAINERS_MAP[EXPLAINER_ID.visible_scores]}
                          />
                        </FormLabel>
                        <FormControl>
                          <FeedbackDefinitionsSelectBox
                            value={field.value}
                            onChange={field.onChange}
                            valueField="name"
                            multiselect
                            showSelectAll
                            onInnerDialogOpenChange={setIsNestedDialogOpen}
                            className={cn({
                              "border-destructive": Boolean(
                                validationErrors?.message,
                              ),
                            })}
                          />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    );
                  }}
                />
              </div>
              <div className="space-y-4">
                <FormField
                  control={form.control}
                  name="annotators_per_item"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>
                        {t("annotationQueues.dialog.annotatorsPerItemLabel")}
                      </FormLabel>
                      <Description>
                        {t(
                          "annotationQueues.dialog.annotatorsPerItemDescription",
                        )}
                      </Description>
                      <FormControl>
                        <Input type="number" min={1} {...field} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
              <div className="space-y-4">
                <FormField
                  control={form.control}
                  name="lock_timeout_minutes"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>
                        {t("annotationQueues.dialog.lockTimeoutLabel")}
                      </FormLabel>
                      <Description>
                        {t("annotationQueues.dialog.lockTimeoutDescription")}
                      </Description>
                      <FormControl>
                        <Input type="number" min={1} max={60} {...field} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
              <div className="space-y-4">
                <div className="comet-body-s text-muted-slate">
                  {t("annotationQueues.dialog.shareQueue")}
                </div>
                <Description>
                  {t("annotationQueues.dialog.shareQueueDescription")}
                </Description>
              </div>
            </form>
          </Form>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">
              {t("annotationQueues.dialog.cancel")}
            </Button>
          </DialogClose>
          <Button type="submit" onClick={form.handleSubmit(onSubmit)}>
            {submitText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditAnnotationQueueDialog;
