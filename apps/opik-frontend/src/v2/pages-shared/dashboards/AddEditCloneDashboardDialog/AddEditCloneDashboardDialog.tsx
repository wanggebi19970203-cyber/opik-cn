import React, { useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { flushSync } from "react-dom";
import { AxiosError, HttpStatusCode } from "axios";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import get from "lodash/get";
import { ChartLine, FlaskConical, LayoutGridIcon, Loader2 } from "lucide-react";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogDescription,
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
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import { cn } from "@/lib/utils";
import useDashboardCreateMutation from "@/api/dashboards/useDashboardCreateMutation";
import { Dashboard, DASHBOARD_SCOPE, DASHBOARD_TYPE } from "@/types/dashboard";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import {
  generateEmptyDashboard,
  regenerateAllIds,
} from "@/lib/dashboard/utils";
import { useToast } from "@/ui/use-toast";
import { ToastAction } from "@/ui/toast";
import { useDashboardStore } from "@/store/DashboardStore";
import { usePermissions } from "@/contexts/PermissionsContext";
import { DISABLED_EXPERIMENTS_TOOLTIP } from "@/constants/permissions";
import CardSelector, { CardOption } from "@/shared/CardSelector/CardSelector";

const getDashboardTypeOptions = (t: (key: string) => string): CardOption[] => [
  {
    value: DASHBOARD_TYPE.MULTI_PROJECT,
    label: t("dialog.multiProjectDashboard"),
    description: t("dialog.multiProjectDescription"),
    icon: <LayoutGridIcon className="size-4" />,
    iconColor: "text-chart-red",
  },
  {
    value: DASHBOARD_TYPE.EXPERIMENTS,
    label: t("dialog.experimentsDashboard"),
    description: t("dialog.experimentsDescription"),
    icon: <FlaskConical className="size-4" />,
    iconColor: "text-chart-green",
  },
];

const getDashboardFormSchema = (t: (key: string) => string) =>
  z.object({
    name: z
      .string()
      .min(1, t("validation.nameRequired"))
      .max(100, t("validation.nameMaxLength"))
      .trim(),
    description: z
      .string()
      .max(255, t("validation.descriptionMaxLength"))
      .optional()
      .or(z.literal("")),
    dashboardType: z.nativeEnum(DASHBOARD_TYPE),
  });

type DashboardFormData = z.infer<ReturnType<typeof getDashboardFormSchema>>;

export type DashboardDialogMode = "create" | "edit" | "clone";

type AddEditCloneDashboardDialogProps = {
  mode: DashboardDialogMode;
  dashboard?: Dashboard;
  open: boolean;
  setOpen: (open: boolean) => void;
  onCreateSuccess?: (dashboardId: string) => void;
  onEditSuccess?: () => void;
  navigateOnCreate?: boolean;
  dashboardType?: DASHBOARD_TYPE;
  dashboardScope?: DASHBOARD_SCOPE;
};

const AddEditCloneDashboardDialog: React.FC<
  AddEditCloneDashboardDialogProps
> = ({
  mode,
  dashboard,
  open,
  setOpen,
  onCreateSuccess,
  onEditSuccess,
  navigateOnCreate = true,
  dashboardType,
  dashboardScope,
}) => {
  const { t } = useTranslation("dashboards");
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();
  const {
    permissions: { canViewExperiments },
  } = usePermissions();

  const isCreateMode = mode === "create";

  const { mutate: createMutate, isPending: isCreating } =
    useDashboardCreateMutation({
      skipDefaultError: true,
    });
  const { mutate: updateMutate, isPending: isUpdating } =
    useDashboardUpdateMutation({
      skipDefaultError: true,
    });

  const isPending = isCreating || isUpdating;

  const getInitialName = () => {
    if (mode === "clone") {
      return `${dashboard!.name} ${t("dialog.copySuffix")}`;
    }

    return dashboard?.name || "";
  };

  const DashboardFormSchema = useMemo(() => getDashboardFormSchema(t), [t]);

  const form = useForm<DashboardFormData>({
    resolver: zodResolver(DashboardFormSchema),
    mode: "onChange",
    defaultValues: {
      name: getInitialName(),
      description: dashboard?.description || "",
      dashboardType:
        dashboardType ?? dashboard?.type ?? DASHBOARD_TYPE.MULTI_PROJECT,
    },
  });

  const config = {
    create: {
      title: t("dialog.createTitle"),
      description: t("dialog.createDescription"),
      buttonText: t("dialog.createButton"),
      showDescription: true,
    },
    edit: {
      title: t("dialog.editTitle"),
      description: null,
      buttonText: t("dialog.updateButton"),
      showDescription: false,
    },
    clone: {
      title: t("dialog.cloneTitle"),
      description: null,
      buttonText: t("dialog.cloneButton"),
      showDescription: false,
    },
  }[mode];

  const onDashboardCreated = useCallback(
    (dashboardData?: { id?: string }) => {
      const dashboardId = dashboardData?.id;
      if (dashboardId) {
        flushSync(() => {
          onCreateSuccess?.(dashboardId);
        });

        if (navigateOnCreate) {
          navigate({
            to: "/$workspaceName/dashboards/$dashboardId",
            params: {
              dashboardId,
              workspaceName,
            },
          });
        }
      }
    },
    [navigate, workspaceName, onCreateSuccess, navigateOnCreate],
  );

  const showCreatedToast = useCallback(() => {
    toast({
      title: t("dialog.dashboardCreated"),
      description: t("dialog.dashboardCreatedDescription"),
      actions: [
        <ToastAction
          variant="link"
          size="sm"
          className="px-0"
          altText={t("dashboards.widgets.addFirst")}
          key="add-widget"
          onClick={() => {
            const { onAddEditWidgetCallback, sections } =
              useDashboardStore.getState();
            if (onAddEditWidgetCallback && sections.length > 0) {
              onAddEditWidgetCallback({ sectionId: sections[0].id });
            }
          }}
        >
          <ChartLine className="mr-1.5 size-3.5" />
          {t("dashboards.widgets.addFirst")}
        </ToastAction>,
      ],
    });
  }, [toast, t]);

  const handleMutationError = useCallback(
    (error: AxiosError, action: string) => {
      const statusCode = get(error, ["response", "status"]);
      const message = get(
        error,
        ["response", "data", "message"],
        error.message,
      );

      if (statusCode === HttpStatusCode.Conflict) {
        form.setError("name", {
          type: "server",
          message: t("dialog.nameAlreadyExists"),
        });
      } else {
        toast({
          title: t("dialog.errorSaving"),
          description: message || t("dialog.failedToAction", { action }),
          variant: "destructive",
        });
      }
    },
    [form, toast, t],
  );

  const onSubmit = useCallback(
    (values: DashboardFormData) => {
      if (mode === "edit") {
        updateMutate(
          {
            dashboard: {
              id: dashboard!.id,
              name: values.name,
              description: values.description || "",
            },
          },
          {
            onSuccess: () => {
              onEditSuccess?.();
              setOpen(false);
            },
            onError: (error: AxiosError) =>
              handleMutationError(error, "update"),
          },
        );
      } else {
        let dashboardConfig;

        if (mode === "create") {
          dashboardConfig = generateEmptyDashboard();
        } else if (mode === "clone") {
          dashboardConfig = regenerateAllIds(dashboard!.config);
        }

        createMutate(
          {
            dashboard: {
              name: values.name,
              description: values.description || "",
              config: dashboardConfig,
              type: mode === "create" ? values.dashboardType : dashboard?.type,
              scope:
                mode === "create"
                  ? dashboardScope ?? DASHBOARD_SCOPE.WORKSPACE
                  : dashboard?.scope,
            },
          },
          {
            onSuccess: (data) => {
              onDashboardCreated(data);
              setOpen(false);
              if (mode === "create") {
                showCreatedToast();
              }
            },
            onError: (error: AxiosError) => handleMutationError(error, mode),
          },
        );
      }
    },
    [
      mode,
      updateMutate,
      dashboard,
      onEditSuccess,
      setOpen,
      handleMutationError,
      createMutate,
      onDashboardCreated,
      showCreatedToast,
      dashboardScope,
    ],
  );

  const dashboardTypeOptions = useMemo<CardOption[]>(
    () =>
      getDashboardTypeOptions(t).map((option) =>
        option.value === DASHBOARD_TYPE.EXPERIMENTS && !canViewExperiments
          ? {
              ...option,
              disabled: true,
              disabledTooltip: DISABLED_EXPERIMENTS_TOOLTIP,
            }
          : option,
      ),
    [canViewExperiments, t],
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-screen-sm">
        <DialogHeader>
          <DialogTitle>{config.title}</DialogTitle>
          {config.showDescription && (
            <DialogDescription>{config.description}</DialogDescription>
          )}
        </DialogHeader>

        <DialogAutoScrollBody>
          <Form {...form}>
            <form
              onSubmit={form.handleSubmit(onSubmit)}
              id="dashboard-form"
              className="flex flex-col gap-4"
            >
              <FormField
                control={form.control}
                name="name"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, ["name"]);

                  return (
                    <FormItem>
                      <FormLabel>{t("dialog.nameLabel")}</FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          placeholder={t("dialog.namePlaceholder")}
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                          onKeyDown={(e) => {
                            if (e.key === "Enter") {
                              e.preventDefault();
                              form.handleSubmit(onSubmit)();
                            }
                          }}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />

              {isCreateMode && !dashboardType && (
                <FormField
                  control={form.control}
                  name="dashboardType"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>{t("dialog.typeLabel")}</FormLabel>
                      <CardSelector
                        value={field.value}
                        onChange={field.onChange}
                        options={dashboardTypeOptions}
                      />
                    </FormItem>
                  )}
                />
              )}

              <Accordion
                type="multiple"
                defaultValue={!isCreateMode ? ["description"] : []}
                className="border-t"
              >
                <AccordionItem value="description">
                  <AccordionTrigger className="h-11 py-1.5">
                    {t("dialog.descriptionLabel")}
                  </AccordionTrigger>
                  <AccordionContent className="px-3">
                    <Textarea
                      {...form.register("description")}
                      placeholder={t("dialog.descriptionPlaceholder")}
                      maxLength={255}
                    />
                  </AccordionContent>
                </AccordionItem>
              </Accordion>
            </form>
          </Form>
        </DialogAutoScrollBody>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" disabled={isPending}>
              {t("dialog.cancel")}
            </Button>
          </DialogClose>

          <Button type="submit" form="dashboard-form" disabled={isPending}>
            {isPending && <Loader2 className="mr-2 size-4 animate-spin" />}
            {config.buttonText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditCloneDashboardDialog;
