import { csv2json } from "json-2-csv";
import i18next from "i18next";

import { JsonUploadFormat } from "@/types/datasets";

export type UploadFormat = "csv" | JsonUploadFormat;

interface CsvValidationResult {
  data?: Record<string, unknown>[];
  error?: string;
}

export const convertFileToBase64 = (file: File): Promise<string> => {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === "string") {
        resolve(reader.result);
      } else {
        reject(new Error(i18next.t("common:fileValidation.failedToConvertToBase64")));
      }
    };
    reader.onerror = () => {
      reject(new Error(i18next.t("common:fileValidation.failedToReadFile")));
    };
    reader.readAsDataURL(file);
  });
};

export async function validateCsvFile(
  file: File | undefined,
  maxSize: number,
  maxItems: number,
): Promise<CsvValidationResult> {
  if (!file) return {};

  if (file.size > maxSize * 1024 * 1024) {
    return { error: i18next.t("common:fileValidation.fileExceedsMaxSize", { maxSize }) };
  }

  if (!file.name.toLowerCase().endsWith(".csv")) {
    return { error: i18next.t("common:fileValidation.fileMustBeCsvFormat") };
  }

  try {
    const text = await file.text();

    const normalizedText = text.replace(/\r\n|\r/g, "\n");

    const parsed = await csv2json(normalizedText, {
      excelBOM: true,
      trimHeaderFields: true,
      trimFieldValues: true,
    });

    if (!Array.isArray(parsed)) {
      return { error: i18next.t("common:fileValidation.invalidCsvFormat") };
    }

    if (parsed.length === 0) {
      return { error: i18next.t("common:fileValidation.csvFileIsEmpty") };
    }

    if (parsed.length > maxItems) {
      return {
        error: i18next.t("common:fileValidation.fileTooLarge", { maxItems: maxItems.toLocaleString() }),
      };
    }

    return { data: parsed as Record<string, unknown>[] };
  } catch (err) {
    console.error(err);
    return { error: i18next.t("common:fileValidation.failedToProcessCsv") };
  }
}

export interface FileValidationResult {
  valid: boolean;
  error?: string;
}

/**
 * Validates if adding files would exceed the maximum allowed count
 */
export const validateFileCount = (
  currentCount: number,
  newFilesCount: number,
  maxCount: number,
): FileValidationResult => {
  const availableSlots = maxCount - currentCount;

  if (newFilesCount > availableSlots) {
    return {
      valid: false,
      error: i18next.t("common:fileValidation.canOnlyAddFiles", { count: availableSlots }),
    };
  }

  return { valid: true };
};

/**
 * Validates if files exceed the specified size limit in MB
 */
export const validateFileSize = (
  files: File[],
  maxSizeMB: number,
): FileValidationResult => {
  const maxSizeBytes = maxSizeMB * 1024 * 1024;
  const oversizedFiles = files.filter((file) => file.size > maxSizeBytes);

  if (oversizedFiles.length > 0) {
    return {
      valid: false,
      error: i18next.t(
        oversizedFiles.length > 1
          ? "common:fileValidation.filesMustBeSmaller_plural"
          : "common:fileValidation.filesMustBeSmaller",
        { maxSizeMB },
      ),
    };
  }

  return { valid: true };
};

/**
 * Extracts filename without the extension
 */
export const getCsvFilenameWithoutExtension = (filename: string): string => {
  return filename.replace(/\.csv$/i, "");
};

/**
 * Extracts filename without any supported dataset-upload extension
 * (.csv, .json, .jsonl, .ndjson). Used to seed the dataset name from
 * the picked file.
 */
export const getDatasetUploadFilenameWithoutExtension = (
  filename: string,
): string => {
  return filename.replace(/\.(csv|jsonl|ndjson|json)$/i, "");
};

/**
 * Maps a dataset-upload filename to its UploadFormat, or null if the
 * extension is not a supported dataset-upload type.
 */
export const detectUploadFormat = (filename: string): UploadFormat | null => {
  const lower = filename.toLowerCase();
  if (lower.endsWith(".csv")) return "csv";
  if (lower.endsWith(".jsonl") || lower.endsWith(".ndjson")) return "jsonl";
  if (lower.endsWith(".json")) return "json";
  return null;
};

/**
 * Human-readable label for an UploadFormat (shown in UI messages).
 */
export const formatToHumanLabel = (format: UploadFormat): string =>
  format === "csv" ? "CSV" : format === "jsonl" ? "JSONL" : "JSON";

/**
 * Comma-separated list of accepted extensions for dataset upload fields,
 * suitable for the HTML `accept` attribute.
 */
export const DATASET_UPLOAD_ACCEPTED_TYPES = ".csv,.json,.jsonl,.ndjson";

/**
 * Error shown when a picked dataset-upload file has an unsupported extension.
 */
export const getInvalidUploadFormatMessage = () =>
  i18next.t("common:fileValidation.invalidUploadFormat");

export type DatasetUploadValidation = {
  file?: File;
  format?: UploadFormat;
  error?: string;
};

/**
 * Validates a picked dataset-upload file: enforces the size limit and detects
 * its UploadFormat. Returns the trio `{ file, format, error }` so callers can
 * either store the validated file/format or surface the error.
 */
export const validateDatasetUploadFile = (
  file: File | undefined,
  maxSizeMB: number,
): DatasetUploadValidation => {
  if (!file) return {};
  if (file.size > maxSizeMB * 1024 * 1024) {
    return { error: i18next.t("common:fileValidation.fileExceedsMaxSize", { maxSize: maxSizeMB }) };
  }
  const format = detectUploadFormat(file.name);
  if (!format) {
    return { error: getInvalidUploadFormatMessage() };
  }
  return { file, format };
};
