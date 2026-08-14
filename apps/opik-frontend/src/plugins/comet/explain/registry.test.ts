import { describe, it, expect, vi } from "vitest";
import { getExplainConfig } from "./registry";
import { ExplainKind, ExplainTarget } from "@/types/assistant-sidebar";

vi.mock("i18next", () => ({
  default: { t: (key: string) => key, getFixedT: () => (key: string) => key },
}));

const t = (kind: ExplainKind): ExplainTarget => ({
  kind,
  entityId: "e1",
  projectId: "p1",
  payload: { exception_type: "ValueError" },
});

describe("AI_EXPLAIN_REGISTRY", () => {
  it("registers a label for every trace/span/thread kind", () => {
    expect(getExplainConfig("trace.error")?.label).toBe(
      "common:comet.explain.explainError",
    );
    expect(getExplainConfig("trace.cost")?.label).toBe(
      "common:comet.explain.explainCost",
    );
    expect(getExplainConfig("trace.duration")?.label).toBe(
      "common:comet.explain.explainDuration",
    );
    expect(getExplainConfig("span.error")?.label).toBe(
      "common:comet.explain.explainError",
    );
    expect(getExplainConfig("span.cost")?.label).toBe(
      "common:comet.explain.explainCost",
    );
    expect(getExplainConfig("span.duration")?.label).toBe(
      "common:comet.explain.explainDuration",
    );
    expect(getExplainConfig("thread.duration")?.label).toBe(
      "common:comet.explain.explainDuration",
    );
    expect(getExplainConfig("thread.cost")?.label).toBe(
      "common:comet.explain.explainCost",
    );
  });
  it("produces a non-empty seed question per kind", () => {
    expect(
      getExplainConfig("trace.error")?.question(t("trace.error")),
    ).toContain("ValueError");
    expect(getExplainConfig("trace.cost")?.question(t("trace.cost"))).toBe(
      "common:comet.explain.explainThisCost",
    );
    expect(
      getExplainConfig("trace.duration")?.question(t("trace.duration")),
    ).toBe("common:comet.explain.explainThisDuration");
    expect(getExplainConfig("span.error")?.question(t("span.error"))).toContain(
      "ValueError",
    );
    expect(getExplainConfig("span.cost")?.question(t("span.cost"))).toBe(
      "common:comet.explain.explainThisCost",
    );
    expect(
      getExplainConfig("span.duration")?.question(t("span.duration")),
    ).toBe("common:comet.explain.explainThisDuration");
    expect(
      getExplainConfig("thread.duration")?.question(t("thread.duration")),
    ).toBe("common:comet.explain.explainThisDuration");
    expect(getExplainConfig("thread.cost")?.question(t("thread.cost"))).toBe(
      "common:comet.explain.explainThisCost",
    );
  });
  it("falls back to a generic error question when exception_type is absent", () => {
    expect(
      getExplainConfig("trace.error")?.question({
        ...t("trace.error"),
        payload: {},
      }),
    ).toBe("common:comet.explain.explainThisError");
  });
});
