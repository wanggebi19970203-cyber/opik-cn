import React from "react";
import { useTranslation } from "react-i18next";

import CopyButton from "@/shared/CopyButton/CopyButton";

type CodeSnippetProps = {
  title: string;
  code: string;
  copyText?: string;
};

const CodeSnippet: React.FC<CodeSnippetProps> = ({ title, code, copyText }) => {
  const { t } = useTranslation("common");

  return (
    <div className="overflow-hidden rounded-md border bg-primary-foreground">
      <div className="flex items-center justify-between border-b px-2.5 py-1">
        <span className="comet-body-xs text-muted-slate">{title}</span>
        <CopyButton
          text={copyText ?? code}
          message={t("codeSnippet.copiedToClipboard")}
          tooltipText={t("codeSnippet.copy")}
          size="icon-3xs"
        />
      </div>
      <pre className="whitespace-pre-wrap break-words px-2.5 py-2 font-code text-[13px] leading-snug">
        {code}
      </pre>
    </div>
  );
};

export default CodeSnippet;
