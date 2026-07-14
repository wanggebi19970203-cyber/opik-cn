import { describe, expect, it } from "vitest";
import parseTranslationResource, {
  getTranslationLoadPath,
} from "./parseTranslationResource";

describe("parseTranslationResource", () => {
  it("supports wrapped and namespace-local translation keys", () => {
    const result = parseTranslationResource(
      JSON.stringify({
        common: {
          welcomeWizard: {
            title: "Welcome",
          },
        },
        shared: "value",
      }),
      "en",
      "common",
    );

    expect(result).toEqual({
      common: {
        welcomeWizard: {
          title: "Welcome",
        },
      },
      welcomeWizard: {
        title: "Welcome",
      },
      shared: "value",
    });
  });

  it("uses the first namespace when the backend provides an array", () => {
    const result = parseTranslationResource(
      JSON.stringify({
        navigation: {
          menu: {
            logs: "Logs",
          },
        },
      }),
      ["en"],
      ["navigation"],
    );

    expect(result.menu).toEqual({ logs: "Logs" });
  });

  it("keeps resources unchanged when they are not namespace-wrapped", () => {
    const result = parseTranslationResource(
      JSON.stringify({ title: "Title" }),
      "en",
      "common",
    );

    expect(result).toEqual({ title: "Title" });
  });

  it("unwraps page resources when the namespace includes a directory", () => {
    const result = parseTranslationResource(
      JSON.stringify({
        logs: {
          title: "Logs",
        },
      }),
      "en",
      "pages/logs",
    );

    expect(result.title).toBe("Logs");
  });

  it("unwraps camel-cased roots for kebab-cased page namespaces", () => {
    const result = parseTranslationResource(
      JSON.stringify({
        agentPlayground: {
          content: {
            connected: "Connected",
          },
        },
      }),
      "en",
      "pages/agent-playground",
    );

    expect(result.content).toEqual({ connected: "Connected" });
  });

  it("keeps the page namespace prefix usable for sibling groups", () => {
    const result = parseTranslationResource(
      JSON.stringify({
        dashboards: {
          title: "Dashboards",
        },
        metrics: {
          traces: "Traces",
        },
      }),
      "en",
      "dashboards",
    );

    expect(result.metrics).toEqual({ traces: "Traces" });
    expect((result.dashboards as Record<string, unknown>).metrics).toEqual({
      traces: "Traces",
    });
  });
});

describe("getTranslationLoadPath", () => {
  it("loads shared namespaces from the locale root", () => {
    expect(getTranslationLoadPath(["zh"], ["common"])).toMatch(
      /^\/locales\/zh\/common\.json\?v=.+$/,
    );
  });

  it("loads page namespaces from the pages directory", () => {
    expect(getTranslationLoadPath(["zh"], ["tracing"])).toMatch(
      /^\/locales\/zh\/pages\/tracing\.json\?v=.+$/,
    );
  });

  it("keeps explicit page namespace paths unchanged", () => {
    expect(getTranslationLoadPath(["zh"], ["pages/logs"])).toMatch(
      /^\/locales\/zh\/pages\/logs\.json\?v=.+$/,
    );
  });

  it("maps camel-cased page namespaces to kebab-cased assets", () => {
    expect(getTranslationLoadPath(["zh"], ["promptEngineering"])).toMatch(
      /^\/locales\/zh\/pages\/prompt-engineering\.json\?v=.+$/,
    );
    expect(getTranslationLoadPath(["zh"], ["pages/agentPlayground"])).toMatch(
      /^\/locales\/zh\/pages\/agent-playground\.json\?v=.+$/,
    );
  });
});
