import React from "react";
import { useTranslation } from "react-i18next";

import UploadField from "@/shared/UploadField/UploadField";
import {
  DATASET_UPLOAD_ACCEPTED_TYPES,
  formatToHumanLabel,
  UploadFormat,
} from "@/lib/file";

type DatasetUploadFieldProps = {
  uploadFile: File | undefined;
  uploadFormat: UploadFormat | undefined;
  uploadError: string | undefined;
  onFileSelect: (file: File | undefined) => void;
  disabled?: boolean;
};

const DatasetUploadField: React.FC<DatasetUploadFieldProps> = ({
  uploadFile,
  uploadFormat,
  uploadError,
  onFileSelect,
  disabled,
}) => {
  const { t } = useTranslation("datasets");
  return (
    <UploadField
      disabled={disabled}
      description={t("uploadField.description")}
      accept={DATASET_UPLOAD_ACCEPTED_TYPES}
      onFileSelect={onFileSelect}
      errorText={uploadError}
      successText={
        uploadFile && !uploadError && uploadFormat
          ? t("uploadField.fileReady", {
              format: formatToHumanLabel(uploadFormat),
            })
          : undefined
      }
    />
  );
};

export default DatasetUploadField;
