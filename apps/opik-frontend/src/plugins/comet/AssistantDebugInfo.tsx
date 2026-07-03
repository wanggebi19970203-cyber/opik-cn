import copy from "clipboard-copy";
import { Copy } from "lucide-react";
import { useTranslation } from "react-i18next";
import OllieOwl from "@/icons/ollie-owl.svg?react";
import { toast } from "@/ui/use-toast";
import useAssistantBackend from "@/plugins/comet/useAssistantBackend";
import useAssistantManifest from "@/plugins/comet/useAssistantManifest";

const AssistantDebugInfo = () => {
  const { t } = useTranslation("common");
  const { probeUrl } = useAssistantBackend();
  const meta = useAssistantManifest(probeUrl);

  if (!meta?.version) return null;

  return (
    <div
      className="flex items-center gap-1"
      onClick={() => {
        copy(meta.version);
        toast({ description: t("messages.ollieVersionCopied") });
      }}
    >
      <span className="comet-body-xs-accented flex items-center gap-1 truncate">
        <OllieOwl className="size-4 text-[var(--color-ollie)]" />
        {t("labels.ollieVersion", { version: meta.version })}
      </span>
      <Copy className="size-3 shrink-0" />
    </div>
  );
};

export default AssistantDebugInfo;
