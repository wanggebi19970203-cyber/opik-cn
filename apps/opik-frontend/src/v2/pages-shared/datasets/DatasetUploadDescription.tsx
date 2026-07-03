import React from "react";
import { useTranslation } from "react-i18next";
import { ExternalLink } from "lucide-react";

import { Button } from "@/ui/button";
import { Description } from "@/ui/description";

type DatasetUploadDescriptionProps = {
  fileSizeLimit: number;
  docsUrl: string;
  className?: string;
};

const DatasetUploadDescription: React.FC<DatasetUploadDescriptionProps> = ({
  fileSizeLimit,
  docsUrl,
  className = "tracking-normal",
}) => {
  const { t } = useTranslation("datasets");
  return (
    <Description className={className}>
      {t("uploadDescription.supportedFormats", { fileSizeLimit })}
      <Button variant="link" size="sm" className="h-5 px-1" asChild>
        <a href={docsUrl} target="_blank" rel="noopener noreferrer">
          {t("uploadDescription.learnMore")}
          <ExternalLink className="ml-0.5 size-3 shrink-0" />
        </a>
      </Button>
    </Description>
  );
};

export default DatasetUploadDescription;
