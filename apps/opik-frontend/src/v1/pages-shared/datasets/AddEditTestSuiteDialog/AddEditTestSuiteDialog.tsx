import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { AxiosError, HttpStatusCode } from "axios";
import get from "lodash/get";

import useDatasetCreateMutation from "@/api/datasets/useDatasetCreateMutation";
import useDatasetItemsFromCsvMutation from "@/api/datasets/useDatasetItemsFromCsvMutation";
import useDatasetItemsFromJsonMutation from "@/api/datasets/useDatasetItemsFromJsonMutation";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
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
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Textarea } from "@/ui/textarea";
import { useToast } from "@/ui/use-toast";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import DatasetUploadDescription from "@/v1/pages-shared/datasets/DatasetUploadDescription";
import DatasetUploadField from "@/v1/pages-shared/datasets/DatasetUploadField";
import { buildDocsUrl } from "@/v1/lib/utils";
import { getApiErrorMessage } from "@/lib/api-error";
import {
  formatToHumanLabel,
  getDatasetUploadFilenameWithoutExtension,
  UploadFormat,
  validateDatasetUploadFile,
} from "@/lib/file";
import { Dataset, DATASET_TYPE } from "@/types/datasets";

const FILE_SIZE_LIMIT_IN_MB = 2000;

type AddEditTestSuiteDialogProps = {
  dataset?: Dataset;
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
  hideUpload?: boolean;
  csvRequired?: boolean;
};

const AddEditTestSuiteDialog = ({
  dataset,
  open,
  setOpen,
  onDatasetCreated,
  hideUpload,
  csvRequired = false,
}: AddEditTestSuiteDialogProps) => {
  const { t } = useTranslation("datasets");
  const { toast } = useToast();

  const { mutate: createMutate } = useDatasetCreateMutation();
  const { mutate: updateMutate } = useDatasetUpdateMutation();
  const { mutate: createItemsFromCsvMutate } = useDatasetItemsFromCsvMutation();
  const { mutate: createItemsFromJsonMutate } =
    useDatasetItemsFromJsonMutation();

  const [confirmOpen, setConfirmOpen] = useState<boolean>(false);
  const [uploadFile, setUploadFile] = useState<File | undefined>(undefined);
  const [uploadError, setUploadError] = useState<string | undefined>(undefined);
  const [uploadFormat, setUploadFormat] = useState<UploadFormat | undefined>(
    undefined,
  );

  const [type, setType] = useState<DATASET_TYPE>(DATASET_TYPE.DATASET);
  const [name, setName] = useState<string>(dataset ? dataset.name : "");
  const [nameError, setNameError] = useState<string | undefined>(undefined);
  const [description, setDescription] = useState<string>(
    dataset ? dataset.description || "" : "",
  );

  useEffect(() => {
    setConfirmOpen(false);
    setNameError(undefined);

    if (!open) {
      setUploadFile(undefined);
      setUploadError(undefined);
      setUploadFormat(undefined);
      setType(DATASET_TYPE.DATASET);
      if (!dataset) {
        setName("");
        setDescription("");
      }
    } else if (dataset) {
      setName(dataset.name);
      setDescription(dataset.description || "");
    }
  }, [open, dataset]);

  const isEdit = Boolean(dataset);
  const hasValidUploadFile = uploadFile && !uploadError;
  // Validation: name is required, and CSV is required only if csvRequired is true
  const isValid =
    name.length > 0 &&
    (isEdit || hideUpload || !csvRequired || hasValidUploadFile);

  const typeLabel =
    type === DATASET_TYPE.TEST_SUITE
      ? t("datasets.addEditDialog.typeLabelTestSuite")
      : t("datasets.addEditDialog.typeLabelDataset");
  const title = isEdit
    ? t("datasets.addEditDialog.editTitle")
    : t("datasets.addEditDialog.createTitle");
  const buttonText = isEdit
    ? t("datasets.addEditDialog.updateButton")
    : t("datasets.addEditDialog.createButton");

  const fileSizeLimit = FILE_SIZE_LIMIT_IN_MB;

  const onCreateSuccessHandler = useCallback(
    (newDataset: Dataset) => {
      if (hasValidUploadFile && uploadFile && uploadFormat && newDataset.id) {
        const label = formatToHumanLabel(uploadFormat);
        const handlers = {
          onSuccess: () => {
            toast({
              title: t("datasets.addEditDialog.uploadAccepted", {
                format: label,
              }),
              description: t(
                "datasets.addEditDialog.uploadAcceptedDescription",
                { format: label },
              ),
            });
          },
          onError: (error: unknown) => {
            console.error(`Error uploading ${label} file:`, error);
            toast({
              title: t("datasets.addEditDialog.uploadError", { format: label }),
              description: getApiErrorMessage(
                error,
                t("datasets.addEditDialog.uploadErrorDescription", {
                  format: label,
                }),
              ),
              variant: "destructive",
            });
          },
          onSettled: () => {
            setOpen(false);
            if (onDatasetCreated) {
              onDatasetCreated(newDataset);
            }
          },
        };

        if (uploadFormat === "csv") {
          createItemsFromCsvMutate(
            { datasetId: newDataset.id, csvFile: uploadFile },
            handlers,
          );
        } else {
          createItemsFromJsonMutate(
            {
              datasetId: newDataset.id,
              jsonFile: uploadFile,
              format: uploadFormat,
            },
            handlers,
          );
        }
      } else {
        setOpen(false);
        if (onDatasetCreated) {
          onDatasetCreated(newDataset);
        }
      }
    },
    [
      hasValidUploadFile,
      uploadFile,
      uploadFormat,
      createItemsFromCsvMutate,
      createItemsFromJsonMutate,
      onDatasetCreated,
      setOpen,
      t,
      toast,
    ],
  );

  const handleMutationError = useCallback(
    (error: AxiosError) => {
      const statusCode = get(error, ["response", "status"]);
      const errorMessage =
        get(error, ["response", "data", "message"]) ||
        get(error, ["response", "data", "errors", 0]) ||
        get(error, ["message"]);

      if (statusCode === HttpStatusCode.Conflict) {
        setNameError(t("datasets.addEditDialog.nameAlreadyExists"));
      } else {
        toast({
          title: t("datasets.addEditDialog.errorSaving"),
          description: errorMessage || t("datasets.addEditDialog.failedToSave"),
          variant: "destructive",
        });
        setOpen(false);
      }
    },
    [t, toast, setOpen],
  );

  const submitHandler = useCallback(() => {
    if (isEdit) {
      updateMutate(
        {
          dataset: {
            id: dataset!.id,
            name,
            ...(description && { description }),
          },
        },
        {
          onSuccess: () => {
            setOpen(false);
          },
          onError: (error: AxiosError) => handleMutationError(error),
        },
      );
    } else {
      createMutate(
        {
          dataset: {
            name,
            ...(description && { description }),
            type,
          },
        },
        {
          onSuccess: onCreateSuccessHandler,
          onError: (error: AxiosError) => handleMutationError(error),
        },
      );
    }
  }, [
    isEdit,
    updateMutate,
    dataset,
    name,
    description,
    type,
    createMutate,
    onCreateSuccessHandler,
    setOpen,
    handleMutationError,
  ]);

  const handleFileSelect = useCallback(
    (file?: File) => {
      setUploadError(undefined);
      setUploadFile(undefined);
      setUploadFormat(undefined);

      if (!file) {
        return;
      }

      const result = validateDatasetUploadFile(file, fileSizeLimit);
      if (result.error) {
        setUploadError(result.error);
        return;
      }
      if (!result.file || !result.format) return;

      setUploadFile(result.file);
      setUploadFormat(result.format);

      if (!name.trim()) {
        setName(getDatasetUploadFilenameWithoutExtension(result.file.name));
      }
    },
    [fileSizeLimit, name],
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="testSuiteName">
              {t("datasets.addEditDialog.name")}
            </Label>
            <Input
              id="testSuiteName"
              placeholder={t("datasets.addEditDialog.namePlaceholder")}
              value={name}
              className={
                nameError &&
                "!border-destructive focus-visible:!border-destructive"
              }
              onChange={(event) => {
                setName(event.target.value);
                setNameError(undefined);
              }}
              onKeyDown={(event) => {
                if (event.key === "Enter" && isValid) {
                  event.preventDefault();
                  uploadError ? setConfirmOpen(true) : submitHandler();
                }
              }}
            />
            <span
              className={`comet-body-xs min-h-4 ${
                nameError ? "text-destructive" : "invisible"
              }`}
            >
              {nameError || " "}
            </span>
          </div>
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="testSuiteDescription">
              {t("datasets.addEditDialog.description")}
            </Label>
            <Textarea
              id="testSuiteDescription"
              placeholder={t("datasets.addEditDialog.descriptionPlaceholder")}
              className="min-h-20"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              maxLength={255}
            />
          </div>
          {!isEdit && !hideUpload && (
            <div className="flex flex-col gap-2 pb-4">
              <Label>{t("datasets.addEditDialog.uploadLabel")}</Label>
              <DatasetUploadDescription
                fileSizeLimit={fileSizeLimit}
                docsUrl={buildDocsUrl("/evaluation/manage_datasets")}
              />
              <DatasetUploadField
                uploadFile={uploadFile}
                uploadFormat={uploadFormat}
                uploadError={uploadError}
                onFileSelect={handleFileSelect}
                disabled={isEdit}
              />
            </div>
          )}
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">
              {t("datasets.addEditDialog.cancel")}
            </Button>
          </DialogClose>
          <Button
            type="submit"
            disabled={!isValid}
            onClick={uploadError ? () => setConfirmOpen(true) : submitHandler}
          >
            {buttonText}
          </Button>
        </DialogFooter>
      </DialogContent>
      <ConfirmDialog
        open={confirmOpen}
        setOpen={setConfirmOpen}
        onCancel={submitHandler}
        title={t("datasets.addEditDialog.fileCantBeUploaded")}
        description={t("datasets.addEditDialog.fileCantBeUploadedDescription", {
          typeLabel,
        })}
        cancelText={t("datasets.addEditDialog.createEmpty", { typeLabel })}
        confirmText={t("datasets.addEditDialog.goBack")}
      />
    </Dialog>
  );
};

export default AddEditTestSuiteDialog;
