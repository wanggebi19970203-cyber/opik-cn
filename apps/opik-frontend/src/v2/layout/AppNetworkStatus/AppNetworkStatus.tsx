import { cn } from "@/lib/utils";
import { useTranslation } from "react-i18next";
import CometIcon from "@/icons/comet.svg?react";
import { usePingBackend } from "@/api/debug/useIsAlive";
import useIsNetworkOnline from "@/hooks/useIsNetworkOnline";
import { WifiOffIcon, WifiIcon, SatelliteDishIcon } from "lucide-react";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

const AppNetworkStatus = () => {
  const { t } = useTranslation();
  const isNetworkOnline = useIsNetworkOnline();
  const { data: pingResponse, isError } = usePingBackend(isNetworkOnline);
  const rtt = pingResponse?.rtt;
  const rttInSeconds = rtt ? (rtt / 1000).toFixed(2) : null;
  const isConnectedToBackend =
    isNetworkOnline && !isError && pingResponse?.healthy;

  return (
    <div className="flex items-center gap-2">
      {isConnectedToBackend && (
        <div className="flex items-center gap-1">
          <SatelliteDishIcon className="size-4" />
          <TooltipWrapper content={t("navigation.network.rttTooltip")}>
            <span className="comet-body-xs-accented">RTT: {rttInSeconds}s</span>
          </TooltipWrapper>
        </div>
      )}
      {isNetworkOnline && (
        <div className="relative flex flex-col items-center justify-center">
          <div
            className={cn(
              "absolute -top-2 left-1.5 size-1.5 rounded-full",
              isConnectedToBackend ? "bg-green-500" : "bg-red-500",
            )}
          />
          <TooltipWrapper
            content={
              isConnectedToBackend
                ? t("navigation.network.connectedToBackend")
                : t("navigation.network.notConnectedToBackend")
            }
          >
            <span>
              <CometIcon className="size-4" />
            </span>
          </TooltipWrapper>
        </div>
      )}
      <div className="relative flex flex-col items-center justify-center">
        <div
          className={cn(
            "absolute -top-2 left-1/2 size-1.5 -translate-x-1/2 rounded-full",
            isNetworkOnline ? "bg-green-500" : "bg-red-500",
          )}
        />
        <TooltipWrapper
          content={
            isNetworkOnline
              ? t("navigation.network.connectedToNetwork")
              : t("navigation.network.notConnectedToNetwork")
          }
        >
          {isNetworkOnline ? (
            <WifiIcon className="size-4" />
          ) : (
            <WifiOffIcon className="size-4" />
          )}
        </TooltipWrapper>
      </div>
    </div>
  );
};

export default AppNetworkStatus;
