import React from "react";
import { useTranslation } from "react-i18next";
import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import { Group, GroupRowConfig } from "@/types/groups";
import SortDirectionSelector from "@/shared/GroupsContent/SortDirectionSelector";

type DictionaryRowProps = {
  config?: GroupRowConfig;
  group: Group;
  onChange: (group: Group) => void;
  hideSorting?: boolean;
};

export const DictionaryRow: React.FC<DictionaryRowProps> = ({
  config,
  group,
  onChange,
  hideSorting = false,
}) => {
  const { t } = useTranslation("common");
  const keyValueChangeHandler = (value: unknown) =>
    onChange({ ...group, key: value as string });

  const KeyComponent = config?.keyComponent ?? DebounceInput;

  return (
    <>
      <td className="p-1">
        <KeyComponent
          className="w-full min-w-32 max-w-[30vw]"
          placeholder={t("labels.key")}
          value={group.key}
          onValueChange={keyValueChangeHandler}
          {...(config?.keyComponentProps ?? {})}
        />
      </td>
      {!hideSorting && (
        <td className="p-1">
          <SortDirectionSelector
            direction={group.direction}
            onSelect={(d) => onChange({ ...group, direction: d })}
          />
        </td>
      )}
    </>
  );
};

export default DictionaryRow;
