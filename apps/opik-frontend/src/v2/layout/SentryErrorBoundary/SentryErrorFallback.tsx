import React from "react";
import { FallbackRender } from "@sentry/react";
import { useTranslation, Trans } from "react-i18next";
import somethingWentWrongImage from "/images/something-went-wrong.png";
import { Button } from "@/ui/button";

const SentryErrorFallback: FallbackRender = ({ resetError }) => {
  const { t } = useTranslation();
  return (
    <div className="flex size-full flex-col items-center justify-center gap-5 bg-[url('/images/circle-pattern.png')] bg-cover bg-center">
      <img
        src={somethingWentWrongImage}
        alt={t("navigation.errorBoundary.somethingWentWrong")}
        width="274px"
      />

      <h2 className="comet-title-l">
        {t("navigation.errorBoundary.somethingWentWrong")}
      </h2>

      <div className="comet-body-s flex max-w-xl flex-col gap-4 text-center text-muted-slate">
        <p>
          <Trans
            i18nKey="navigation.errorBoundary.errorMessage1"
            components={{
              contactLink: (
                <Button variant="link" size="sm" asChild className="inline px-0">
                  <a href="mailto:support@comet.com" />
                </Button>
              ),
            }}
          />
        </p>
        <p>
          <Trans
            i18nKey="navigation.errorBoundary.errorMessage2"
            components={{
              githubLink: (
                <Button variant="link" size="sm" asChild className="inline px-0">
                  <a
                    href="https://github.com/comet-ml/opik"
                    target="_blank"
                    rel="noreferrer"
                  />
                </Button>
              ),
              slackLink: (
                <Button variant="link" size="sm" asChild className="inline px-0">
                  <a href="https://chat.comet.com" target="_blank" rel="noreferrer" />
                </Button>
              ),
            }}
          />
        </p>
      </div>

      <Button onClick={resetError}>
        {t("navigation.errorBoundary.continue")}
      </Button>
    </div>
  );
};

export default SentryErrorFallback;
