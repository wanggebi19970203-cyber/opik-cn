import React from "react";
import { useTranslation } from "react-i18next";
import { useIsPhone } from "@/hooks/useIsPhone";
import CodeSectionTitle from "@/shared/CodeSectionTitle/CodeSectionTitle";
import CodeBlockWithHeader from "@/shared/CodeBlockWithHeader/CodeBlockWithHeader";
import CodeHighlighter from "@/shared/CodeHighlighter/CodeHighlighter";
import { PIP_INSTALL_OPIK_COMMAND } from "@/constants/shared";

type InstallOpikSectionProps = {
  title: string;
};

const InstallOpikSection: React.FC<InstallOpikSectionProps> = ({ title }) => {
  const { t } = useTranslation();
  const { isPhonePortrait } = useIsPhone();

  return (
    <div>
      <CodeSectionTitle>{title}</CodeSectionTitle>
      {isPhonePortrait ? (
        <CodeBlockWithHeader
          title={t("common.installer.terminal")}
          copyText={PIP_INSTALL_OPIK_COMMAND}
        >
          <CodeHighlighter data={PIP_INSTALL_OPIK_COMMAND} />
        </CodeBlockWithHeader>
      ) : (
        <div className="min-h-7">
          <CodeHighlighter data={PIP_INSTALL_OPIK_COMMAND} />
        </div>
      )}
    </div>
  );
};

export default InstallOpikSection;
