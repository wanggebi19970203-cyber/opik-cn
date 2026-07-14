import React from "react";
import { useTranslation } from "react-i18next";
import { Span, Trace } from "@/types/traces";
import { useUnifiedMedia } from "@/hooks/useUnifiedMedia";
import { MediaProvider } from "@/shared/PrettyLLMMessage/llmMessages";
import CollapsibleSection from "@/v2/pages-shared/traces/TraceDetailsPanel/CollapsibleSection";
import CodeBlock from "./CodeBlock";
import AttachmentsList from "./AttachmentsList";
import EventsList from "./EventsList";
import Loader from "@/shared/Loader/Loader";

type DetailsTabProps = {
  data: Trace | Span;
  isLoading: boolean;
  search?: string;
};

const DetailsTab: React.FunctionComponent<DetailsTabProps> = ({
  data,
  isLoading,
  search,
}) => {
  const { t } = useTranslation("tracing");
  const { media, transformedInput, transformedOutput } = useUnifiedMedia(data);

  const hasMetadata = Boolean(data.metadata);
  const hasTokenUsage = Boolean(data.usage);

  return (
    <MediaProvider media={media}>
      <div className="flex flex-col gap-2">
        <AttachmentsList media={media} />
        {isLoading ? (
          <CollapsibleSection
            title={t("detailsTab.input")}
            disabled
            bodyClassName="p-2"
          >
            <Loader />
          </CollapsibleSection>
        ) : (
          <CodeBlock
            title={t("detailsTab.input")}
            data={transformedInput}
            prettifyConfig={{ fieldType: "input" }}
            preserveKey="syntax-highlighter-trace-sidebar-input"
            search={search}
            withSearch
            quickFilterSection="input"
          />
        )}
        {isLoading ? (
          <CollapsibleSection
            title={t("detailsTab.output")}
            disabled
            bodyClassName="p-2"
          >
            <Loader />
          </CollapsibleSection>
        ) : (
          <CodeBlock
            title={t("detailsTab.output")}
            data={transformedOutput}
            prettifyConfig={{ fieldType: "output" }}
            preserveKey="syntax-highlighter-trace-sidebar-output"
            search={search}
            withSearch
            quickFilterSection="output"
          />
        )}
        <EventsList data={data} isLoading={isLoading} search={search} />
        {hasMetadata && (
          <CodeBlock
            title={t("detailsTab.metadata")}
            withSearch
            data={data.metadata}
            search={search}
            quickFilterSection="metadata"
          />
        )}
        {hasTokenUsage && (
          <CodeBlock
            title={t("detailsTab.tokenUsage")}
            data={data.usage as object}
            withSearch
            search={search}
          />
        )}
      </div>
    </MediaProvider>
  );
};

export default DetailsTab;
