import { TFunction } from "i18next";

// Keep the reason codes in sync with ollie-assist RunFailureCode and the BE.
export type RunFailureCopy = {
  title: string;
  description: string;
};

export const getRunFailureCopy = (
  t: TFunction,
  reason?: string,
): RunFailureCopy => {
  switch (reason) {
    case "out_of_credits":
      return {
        title: t("signals.runFailure.couldNotRun"),
        description: t("signals.runFailure.outOfCredits"),
      };
    case "rate_limited":
      return {
        title: t("signals.runFailure.couldNotRun"),
        description: t("signals.runFailure.rateLimited"),
      };
    case "provider_error":
      return {
        title: t("signals.runFailure.providerError"),
        description: t("signals.runFailure.providerErrorDescription"),
      };
    case "did_not_start":
      return {
        title: t("signals.runFailure.didNotStart"),
        description: t("signals.runFailure.didNotStartDescription"),
      };
    case "permission_denied":
      return {
        title: t("signals.runFailure.permissionDenied"),
        description: t("signals.runFailure.permissionDeniedDescription"),
      };
    case "internal_error":
      return {
        title: t("signals.runFailure.internalError"),
        description: t("signals.runFailure.internalErrorDescription"),
      };
    default:
      return {
        title: t("signals.runFailure.defaultError"),
        description: t("signals.runFailure.defaultErrorDescription"),
      };
  }
};
