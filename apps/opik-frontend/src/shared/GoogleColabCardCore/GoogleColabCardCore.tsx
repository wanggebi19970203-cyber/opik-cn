import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import { ExternalLink } from "lucide-react";
import colabLogo from "/images/colab-logo.png";

import { GoogleColabCardCoreProps } from "@/types/shared";

const GoogleColabCardCore: React.FC<GoogleColabCardCoreProps> = ({ link }) => {
  const { t } = useTranslation("common");
  return (
    <div className="flex flex-1 flex-col justify-between gap-4 rounded-md border bg-background p-6">
      <div className="comet-title-xs text-foreground-secondary">
        {t("shared.fullExample")}
      </div>
      <div className="gap-3">
        <div className="comet-body-s mb-4 text-muted-slate">
          {t("shared.tryGoogleColabExample")}
        </div>
        <Button variant="outline" asChild className="w-full justify-between">
          <a href={link} target="_blank" rel="noreferrer">
            <div className="flex items-center gap-1">
              {t("shared.openInColab")}
              <img src={colabLogo} alt={t("shared.colabLogo")} className="h-[27px] w-8" />
            </div>

            <ExternalLink className="ml-2 size-4 shrink-0" />
          </a>
        </Button>
      </div>
    </div>
  );
};

export default GoogleColabCardCore;
