import { useCallback } from "react";
import i18next from "i18next";
import { useServerSync } from "@/contexts/server-sync-provider";
import useWorkspaceConfigMutation from "@/api/workspaces/useWorkspaceConfigMutation";
import { useToast } from "@/ui/use-toast";
import { resolveColor, resolveHexColor } from "@/lib/colorVariants";

export const COLOR_MAP_MAX_ENTRIES = 10000;

const useUpdateColorMapping = () => {
  const { config, previewColor, setPreviewColor } = useServerSync();

  const { mutate: updateWorkspaceConfig, isPending } =
    useWorkspaceConfigMutation();
  const { toast } = useToast();

  const updateColor = useCallback(
    (colorKey: string, hexColor: string) => {
      if (!config)
        return toast({
          title: i18next.t("common.hooks.useUpdateColorMapping.changesNotSaved"),
          description: i18next.t("common.hooks.useUpdateColorMapping.workspaceConfigNotLoaded"),
          variant: "destructive",
        });

      const currentMap = { ...(config?.color_map ?? {}) };
      const isDefault =
        hexColor.toLowerCase() ===
        resolveHexColor(resolveColor(colorKey)).toLowerCase();

      if (isDefault) {
        delete currentMap[colorKey];
      } else {
        const isExisting = colorKey in currentMap;

        if (
          !isExisting &&
          Object.keys(currentMap).length >= COLOR_MAP_MAX_ENTRIES
        ) {
          toast({
            title: i18next.t("common.hooks.useUpdateColorMapping.colorMapLimitReached"),
            description: i18next.t("common.hooks.useUpdateColorMapping.colorMapLimitExceeded", {
              maxEntries: COLOR_MAP_MAX_ENTRIES,
            }),
            variant: "destructive",
          });
          return;
        }

        currentMap[colorKey] = hexColor;
      }

      updateWorkspaceConfig({
        config: {
          timeout_to_mark_thread_as_inactive:
            config?.timeout_to_mark_thread_as_inactive ?? null,
          truncation_on_tables: config?.truncation_on_tables ?? null,
          color_map: Object.keys(currentMap).length > 0 ? currentMap : null,
        },
      });
    },
    [config, updateWorkspaceConfig, toast],
  );

  return { updateColor, previewColor, setPreviewColor, isPending };
};

export default useUpdateColorMapping;
