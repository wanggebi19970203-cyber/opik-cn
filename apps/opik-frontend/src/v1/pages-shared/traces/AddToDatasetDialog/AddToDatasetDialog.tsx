import React, { useCallback, useMemo, useState } from "react";
import isUndefined from "lodash/isUndefined";
import { Database, MessageCircleWarning, Plus } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { Span, Trace } from "@/types/traces";
import useAppStore from "@/store/AppStore";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import Loader from "@/shared/Loader/Loader";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import SearchInput from "@/shared/SearchInput/SearchInput";
import useAddTracesToDatasetMutation from "@/api/datasets/useAddTracesToDatasetMutation";
import useAddSpansToDatasetMutation from "@/api/datasets/useAddSpansToDatasetMutation";
import { Dataset } from "@/types/datasets";
import { Alert, AlertDescription } from "@/ui/alert";
import { Button } from "@/ui/button";
import { Checkbox } from "@/ui/checkbox";
import { Label } from "@/ui/label";
import { cn } from "@/lib/utils";
import { isObjectSpan } from "@/lib/traces";
import { useToast } from "@/ui/use-toast";
import AddEditTestSuiteDialog from "@/v1/pages-shared/datasets/AddEditTestSuiteDialog/AddEditTestSuiteDialog";
import ExplainerDescription from "@/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v1/constants/explainers";
import { usePermissions } from "@/contexts/PermissionsContext";

const DEFAULT_SIZE = 100;

type EnrichmentOptions = {
  includeSpans: boolean;
  includeTags: boolean;
  includeFeedbackScores: boolean;
  includeComments: boolean;
  includeUsage: boolean;
  includeMetadata: boolean;
};

type AddToDatasetDialogProps = {
  selectedRows: Array<Trace | Span>;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddToDatasetDialog: React.FunctionComponent<AddToDatasetDialogProps> = ({
  selectedRows,
  open,
  setOpen,
}) => {
  const { t } = useTranslation("tracing");
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(DEFAULT_SIZE);
  const [openDialog, setOpenDialog] = useState<boolean>(false);
  const [fetching, setFetching] = useState<boolean>(false);
  const [selectedDataset, setSelectedDataset] = useState<Dataset | null>(null);
  const { toast } = useToast();
  const {
    permissions: { canCreateDatasets },
  } = usePermissions();

  // Enrichment options state - all checked by default (opt-out design)
  const [enrichmentOptions, setEnrichmentOptions] = useState({
    includeSpans: true,
    includeTags: true,
    includeFeedbackScores: true,
    includeComments: true,
    includeUsage: true,
    includeMetadata: true,
  });

  const { mutate: addTracesToDataset } = useAddTracesToDatasetMutation();
  const { mutate: addSpansToDataset } = useAddSpansToDatasetMutation();

  const { data, isPending } = useDatasetsList(
    {
      workspaceName,
      search,
      page,
      size,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const datasets = data?.content ?? [];
  const total = data?.total ?? 0;

  const validRows = useMemo(() => {
    return selectedRows.filter((r) => !isUndefined(r.input));
  }, [selectedRows]);

  const validTraces = useMemo(() => {
    return validRows.filter((r) => !isObjectSpan(r));
  }, [validRows]);

  const validSpans = useMemo(() => {
    return validRows.filter((r) => isObjectSpan(r));
  }, [validRows]);

  const hasOnlyTraces = validTraces.length > 0 && validSpans.length === 0;
  const hasOnlySpans = validSpans.length > 0 && validTraces.length === 0;

  const noValidRows = validRows.length === 0;
  const partialValid = validRows.length !== selectedRows.length;

  const onItemsAdded = useCallback(
    (dataset: Dataset, hasTraces: boolean, hasSpans: boolean) => {
      let itemType = t("addToDataset.itemsAddedToTestSuite");
      if (hasTraces && !hasSpans) {
        itemType = t("addToDataset.tracesAddedToTestSuite");
      } else if (hasSpans && !hasTraces) {
        itemType = t("addToDataset.spansAddedToTestSuite");
      }

      const explainer =
        EXPLAINERS_MAP[EXPLAINER_ID.i_added_traces_to_an_test_suite_now_what];

      toast({
        title: itemType,
        description: explainer.description,
      });
    },
    [toast, t],
  );

  const addToDatasetHandler = useCallback(
    (dataset: Dataset) => {
      setFetching(true);
      setOpen(false);

      // If we have only traces, use the enriched endpoint for traces
      if (hasOnlyTraces) {
        addTracesToDataset(
          {
            workspaceName,
            datasetId: dataset.id,
            traceIds: validTraces.map((t) => t.id),
            enrichmentOptions: {
              include_spans: enrichmentOptions.includeSpans,
              include_tags: enrichmentOptions.includeTags,
              include_feedback_scores: enrichmentOptions.includeFeedbackScores,
              include_comments: enrichmentOptions.includeComments,
              include_usage: enrichmentOptions.includeUsage,
              include_metadata: enrichmentOptions.includeMetadata,
            },
          },
          {
            onSuccess: () => {
              onItemsAdded(dataset, true, false);
              setFetching(false);
            },
            onError: () => {
              setFetching(false);
            },
          },
        );
      } else if (hasOnlySpans) {
        // If we have only spans, use the enriched endpoint for spans
        addSpansToDataset(
          {
            workspaceName,
            datasetId: dataset.id,
            spanIds: validSpans.map((s) => s.id),
            enrichmentOptions: {
              include_tags: enrichmentOptions.includeTags,
              include_feedback_scores: enrichmentOptions.includeFeedbackScores,
              include_comments: enrichmentOptions.includeComments,
              include_usage: enrichmentOptions.includeUsage,
              include_metadata: enrichmentOptions.includeMetadata,
            },
          },
          {
            onSuccess: () => {
              onItemsAdded(dataset, false, true);
              setFetching(false);
            },
            onError: () => {
              setFetching(false);
            },
          },
        );
      }
    },
    [
      setOpen,
      addTracesToDataset,
      addSpansToDataset,
      workspaceName,
      onItemsAdded,
      enrichmentOptions,
      hasOnlyTraces,
      hasOnlySpans,
      validTraces,
      validSpans,
    ],
  );

  const renderListItems = () => {
    if (isPending || fetching) {
      return <Loader />;
    }

    if (datasets.length === 0) {
      const text = search
        ? t("addToDataset.noSearchResults")
        : t("addToDataset.noTestSuitesYet");

      return (
        <div className="comet-body-s flex h-32 items-center justify-center text-muted-slate">
          {text}
        </div>
      );
    }

    return datasets.map((d) => {
      const isSelected = selectedDataset?.id === d.id;
      return (
        <div
          key={d.id}
          className={cn(
            "rounded-sm px-4 py-2.5 flex flex-col",
            noValidRows
              ? "cursor-default"
              : "cursor-pointer hover:bg-primary-foreground",
            isSelected && "bg-muted",
          )}
          onClick={() => !noValidRows && setSelectedDataset(d)}
        >
          <div className="flex flex-col gap-0.5">
            <div className="flex items-center gap-2">
              <Database
                className={cn(
                  "size-4 shrink-0",
                  noValidRows ? "text-muted-gray" : "text-muted-slate",
                )}
              />
              <span
                className={cn(
                  "comet-body-s-accented truncate w-full",
                  noValidRows && "text-muted-gray",
                )}
              >
                {d.name}
              </span>
            </div>
            <div
              className={cn(
                "comet-body-s pl-6 whitespace-pre-line break-words",
                noValidRows ? "text-muted-gray" : "text-light-slate",
              )}
            >
              {d.description}
            </div>
          </div>
        </div>
      );
    });
  };

  const renderAlert = () => {
    const text = noValidRows
      ? t("addToDataset.noValidRows")
      : t("addToDataset.partialValidRows");

    if (noValidRows || partialValid) {
      return (
        <Alert className="mt-4">
          <MessageCircleWarning />
          <AlertDescription>{text}</AlertDescription>
        </Alert>
      );
    }

    return null;
  };

  const renderEnrichmentCheckbox = (
    id: string,
    label: string,
    checked: boolean,
    field: keyof EnrichmentOptions,
  ) => (
    <div className="flex items-center space-x-2">
      <Checkbox
        id={id}
        checked={checked}
        onCheckedChange={(checked) =>
          setEnrichmentOptions((prev) => ({
            ...prev,
            [field]: checked === true,
          }))
        }
      />
      <Label htmlFor={id} className="comet-body-s cursor-pointer font-normal">
        {label}
      </Label>
    </div>
  );

  const renderMetadataConfiguration = (
    type: "trace" | "span",
    includeNestedSpans: boolean = false,
  ) => (
    <Accordion type="single" collapsible defaultValue="" className="mb-4">
      <AccordionItem value="metadata" className="border-t">
        <AccordionTrigger>
          {type === "trace"
            ? t("addToDataset.traceMetadataConfig")
            : t("addToDataset.spanMetadataConfig")}
        </AccordionTrigger>
        <AccordionContent className="px-3">
          <div className="grid grid-cols-2 gap-3">
            {includeNestedSpans &&
              renderEnrichmentCheckbox(
                "include-spans",
                t("addToDataset.nestedSpans"),
                enrichmentOptions.includeSpans,
                "includeSpans",
              )}
            {renderEnrichmentCheckbox(
              `include-tags${type === "span" ? "-span" : ""}`,
              t("addToDataset.tags"),
              enrichmentOptions.includeTags,
              "includeTags",
            )}
            {renderEnrichmentCheckbox(
              `include-feedback-scores${type === "span" ? "-span" : ""}`,
              t("addToDataset.feedbackScores"),
              enrichmentOptions.includeFeedbackScores,
              "includeFeedbackScores",
            )}
            {renderEnrichmentCheckbox(
              `include-comments${type === "span" ? "-span" : ""}`,
              t("addToDataset.comments"),
              enrichmentOptions.includeComments,
              "includeComments",
            )}
            {renderEnrichmentCheckbox(
              `include-usage${type === "span" ? "-span" : ""}`,
              t("addToDataset.usageMetrics"),
              enrichmentOptions.includeUsage,
              "includeUsage",
            )}
            {renderEnrichmentCheckbox(
              `include-metadata${type === "span" ? "-span" : ""}`,
              t("addToDataset.metadata"),
              enrichmentOptions.includeMetadata,
              "includeMetadata",
            )}
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );

  return (
    <>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-lg sm:max-w-[560px]">
          <DialogHeader>
            <DialogTitle>{t("addToDataset.title")}</DialogTitle>
          </DialogHeader>
          <DialogAutoScrollBody>
            <ExplainerDescription
              className="mb-4"
              {...EXPLAINERS_MAP[
                EXPLAINER_ID.why_would_i_want_to_add_traces_to_an_test_suite
              ]}
            />
            {hasOnlyTraces && renderMetadataConfiguration("trace", true)}
            {hasOnlySpans && renderMetadataConfiguration("span")}
            <div className="my-2 flex items-center justify-between">
              <h3 className="comet-title-xs">{t("addToDataset.selectTestSuite")}</h3>
              {canCreateDatasets && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    setOpenDialog(true);
                  }}
                  disabled={noValidRows}
                >
                  <Plus className="mr-2 size-4" />
                  {t("addToDataset.createNewTestSuite")}
                </Button>
              )}
            </div>
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              className="w-full"
            />
            {renderAlert()}
            <div className="my-4 flex max-h-[300px] min-h-36 max-w-full flex-col justify-stretch overflow-y-auto sm:max-h-[400px]">
              {renderListItems()}
            </div>
            {total > DEFAULT_SIZE && (
              <div className="pt-4">
                <DataTablePagination
                  page={page}
                  pageChange={setPage}
                  size={size}
                  sizeChange={setSize}
                  total={total}
                ></DataTablePagination>
              </div>
            )}
          </DialogAutoScrollBody>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setOpen(false)}
              disabled={fetching}
            >
              {t("addToDataset.cancel")}
            </Button>
            <Button
              onClick={() => {
                if (selectedDataset) {
                  addToDatasetHandler(selectedDataset);
                }
              }}
              disabled={!selectedDataset || noValidRows || fetching}
            >
              {t("addToDataset.addToTestSuite")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <AddEditTestSuiteDialog
        open={openDialog}
        setOpen={setOpenDialog}
        onDatasetCreated={(dataset) => {
          setSelectedDataset(dataset);
        }}
        hideUpload={true}
      />
    </>
  );
};

export default AddToDatasetDialog;
