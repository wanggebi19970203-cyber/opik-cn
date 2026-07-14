import React from "react";
import { useTranslation } from "react-i18next";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import opikLogoUrl from "/images/opik-logo.png";
import opikLogoInvertedUrl from "/images/opik-logo-inverted.png";

export type PairingStatus = "loading" | "success" | "error";
export type RunnerVariant = "connect" | "endpoint";
export type PairingErrorKind =
  | "invalid_link"
  | "tampered_link"
  | "expired_link"
  | "unreachable"
  | "insecure_context"
  | "v1_workspace";

export interface PairingStatusScreenProps {
  status: PairingStatus;
  runnerVariant?: RunnerVariant;
  errorKind?: PairingErrorKind;
  // Workspace the CLI generated the pairing link for (from `?workspace=`).
  expectedWorkspace?: string | null;
  // Project name the CLI was pairing into (from `?project=`).
  expectedProject?: string | null;
  // Opik API base URL the CLI was talking to (from `?url=`). Used for
  // diagnostic display only — does not affect activation.
  expectedBaseUrl?: string | null;
}

function getCopy(
  props: PairingStatusScreenProps,
  t: (key: string) => string,
): {
  headline: string;
  subtitle: string;
} {
  if (props.status === "loading") {
    const headline =
      props.runnerVariant === "endpoint"
        ? t("common.shared.connectingYourAgent")
        : props.runnerVariant === "connect"
          ? t("common.shared.connectingToCodebase")
          : t("common.shared.connecting");
    return {
      headline,
      subtitle: t("common.shared.finalizingPairing"),
    };
  }

  if (props.status === "success") {
    const headline =
      props.runnerVariant === "endpoint"
        ? t("common.shared.agentConnectedToOpik")
        : t("common.shared.opikConnectedToCodebase");
    return {
      headline,
      subtitle: t("common.shared.pairingSuccessful"),
    };
  }

  switch (props.errorKind) {
    case "tampered_link":
      return {
        headline: t("common.shared.pairingLinkNotTrusted"),
        subtitle: t("common.shared.linkModified"),
      };
    case "expired_link":
      return {
        headline: t("common.shared.pairingLinkExpired"),
        subtitle: t("common.shared.runCliAgain"),
      };
    case "unreachable":
      return {
        headline: t("common.shared.couldNotReachOpik"),
        subtitle: t("common.shared.checkConnection"),
      };
    case "insecure_context":
      return {
        headline: t("common.shared.secureConnectionRequired"),
        subtitle: t("common.shared.pairingRequiresHttps"),
      };
    case "v1_workspace":
      return {
        headline: t("common.shared.workspaceUpgradeRequired"),
        subtitle: t("common.shared.opikConnectRequiresUpgrade"),
      };
    default:
      return {
        headline: t("common.shared.pairingLinkInvalid"),
        subtitle: t("common.shared.runCliAgain"),
      };
  }
}

// `expectedBaseUrl` is supplied by the CLI via `?url=` and goes straight
// into <a href>. Allow only http/https so a crafted pairing link (e.g.
// `?url=javascript:alert(1)`) can't produce a clickable script URL.
function safeHttpHref(raw: string | null | undefined): string | null {
  if (!raw) return null;
  try {
    const parsed = new URL(raw);
    return parsed.protocol === "https:" || parsed.protocol === "http:"
      ? raw
      : null;
  } catch {
    return null;
  }
}

export const PairingStatusScreen: React.FC<PairingStatusScreenProps> = (
  props,
) => {
  const { t } = useTranslation();
  const { headline, subtitle } = getCopy(props, t);
  const { themeMode } = useTheme();

  const showWorkspaceContext =
    props.status === "error" &&
    !!(
      props.expectedWorkspace ||
      props.expectedProject ||
      props.expectedBaseUrl
    );
  const safeBaseUrlHref = safeHttpHref(props.expectedBaseUrl);

  return (
    <main
      aria-label={t("common.shared.pairingStatus")}
      className="flex min-h-screen flex-col items-center justify-center p-6"
    >
      <img
        src={themeMode === THEME_MODE.DARK ? opikLogoInvertedUrl : opikLogoUrl}
        alt="Opik"
        className="mb-10 h-10"
      />
      <div className="flex flex-col items-center gap-2">
        <h1 className="comet-title-s text-center">{headline}</h1>
        <p className="comet-body text-center text-muted-slate">{subtitle}</p>
        {showWorkspaceContext && (
          <div className="mt-6 flex flex-col items-center gap-2">
            <p className="comet-body-s text-muted-slate">
              {t("common.shared.cliTriedPairingWith")}
            </p>
            <dl
              aria-label={t("common.shared.pairingStatus")}
              className="comet-body-s grid grid-cols-[auto_1fr] items-center gap-x-6 gap-y-1.5 rounded-md border border-border bg-soft-background px-5 py-3 text-left"
            >
              {props.expectedBaseUrl ? (
                <>
                  <dt className="text-muted-slate">
                    {t("common.shared.opikUrl")}
                  </dt>
                  <dd className="break-all font-medium">
                    {safeBaseUrlHref ? (
                      <a
                        href={safeBaseUrlHref}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-primary underline-offset-2 hover:underline"
                      >
                        {props.expectedBaseUrl}
                      </a>
                    ) : (
                      // Unsafe / non-http(s) scheme — show as plain text so
                      // the user can still see what the CLI tried, without
                      // a clickable script URL.
                      <span>{props.expectedBaseUrl}</span>
                    )}
                  </dd>
                </>
              ) : null}
              {props.expectedWorkspace ? (
                <>
                  <dt className="text-muted-slate">
                    {t("common.shared.workspace")}
                  </dt>
                  <dd className="font-medium">{props.expectedWorkspace}</dd>
                </>
              ) : null}
              {props.expectedProject ? (
                <>
                  <dt className="text-muted-slate">
                    {t("common.shared.project")}
                  </dt>
                  <dd className="break-all font-medium">
                    {props.expectedProject}
                  </dd>
                </>
              ) : null}
            </dl>
          </div>
        )}
      </div>
    </main>
  );
};

export default PairingStatusScreen;
