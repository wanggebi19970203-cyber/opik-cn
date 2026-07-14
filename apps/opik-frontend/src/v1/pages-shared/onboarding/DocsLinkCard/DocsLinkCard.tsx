import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import { ExternalLink } from "lucide-react";

export type DocsLinkCardProps = {
  link: string;
  title?: string;
  description: string;
  buttonText?: string;
};

const DocsLinkCard: React.FC<DocsLinkCardProps> = ({
  link,
  title,
  description,
  buttonText,
}) => {
  const { t } = useTranslation();
  const displayTitle = title ?? t("integrationExplorer.needHelp");
  const displayButtonText = buttonText ?? t("integrationExplorer.goToDocs");
  return (
    <div className="flex flex-1 flex-col justify-between gap-3 rounded-md border bg-background p-6">
      <div className="comet-title-xs text-foreground-secondary">
        {displayTitle}
      </div>
      <div className="gap-3">
        <div className="comet-body-s mb-4 text-muted-slate">{description}</div>
        <Button variant="secondary" asChild className="w-full justify-center">
          <a href={link} target="_blank" rel="noreferrer">
            <span className="flex items-center gap-1">
              {displayButtonText}
              <ExternalLink className="ml-2 size-4 shrink-0" />
            </span>
          </a>
        </Button>
      </div>
    </div>
  );
};

export default DocsLinkCard;
