import { AlignLeft } from "lucide-react";
import { useTranslation } from "react-i18next";

interface PlaygroundNoRunsYetProps {
  color: string;
}

const PlaygroundNoRunsYet = ({ color }: PlaygroundNoRunsYetProps) => {
  const { t } = useTranslation();
  return (
    <div className="flex size-full flex-col items-center justify-center gap-2">
      <AlignLeft className="size-5" style={{ color }} />
      <p className="comet-body-s-accented">{t("common.playground.noRunsYet")}</p>
      <p className="comet-body-s text-light-slate">
        {t("common.playground.noRunsYetDescription")}
      </p>
    </div>
  );
};

export default PlaygroundNoRunsYet;
