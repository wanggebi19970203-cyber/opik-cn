import { describe, it, expect, vi } from "vitest";

import {
  buildPromptComparisonTargets,
  ComparisonCandidate,
} from "./promptComparisonTargets";

// i18next.t() returns the raw key in tests (mirrors the react-i18next mock).
vi.mock("i18next", () => ({
  default: {
    t: (key: string) => key,
  },
}));

const createCandidate = (
  overrides: Partial<ComparisonCandidate> = {},
): ComparisonCandidate => ({
  id: "c-1",
  stepIndex: 1,
  parentCandidateIds: [],
  trialNumber: 1,
  ...overrides,
});

// Prompt resolver keyed by candidate id; unknown ids resolve to null.
const promptResolver =
  (prompts: Record<string, unknown>) => (candidate: ComparisonCandidate) =>
    candidate.id in prompts ? prompts[candidate.id] : null;

describe("buildPromptComparisonTargets", () => {
  describe("baseline target", () => {
    it("offers the baseline (step 0) candidate for a non-baseline candidate", () => {
      const baseline = createCandidate({ id: "base", stepIndex: 0 });
      const candidate = createCandidate({ id: "c-2", trialNumber: 2 });

      const targets = buildPromptComparisonTargets({
        candidate,
        candidates: [baseline, candidate],
        getPrompt: promptResolver({
          base: "baseline prompt",
          "c-2": "current",
        }),
      });

      expect(targets).toEqual([
        { id: "base", label: "codeDiff.baseline", prompt: "baseline prompt" },
      ]);
    });

    it("returns no targets when the candidate is itself the baseline", () => {
      const baseline = createCandidate({ id: "base", stepIndex: 0 });

      const targets = buildPromptComparisonTargets({
        candidate: baseline,
        candidates: [baseline],
        getPrompt: promptResolver({ base: "baseline prompt" }),
      });

      expect(targets).toEqual([]);
    });

    it("omits the baseline when no step-0 candidate exists", () => {
      const candidate = createCandidate({ id: "c-2", trialNumber: 2 });

      const targets = buildPromptComparisonTargets({
        candidate,
        candidates: [candidate],
        getPrompt: promptResolver({ "c-2": "current" }),
      });

      expect(targets).toEqual([]);
    });
  });

  describe("parent targets", () => {
    it("offers a single parent labelled simply 'Parent', baseline first", () => {
      const baseline = createCandidate({ id: "base", stepIndex: 0 });
      const parent = createCandidate({
        id: "p-1",
        stepIndex: 1,
        trialNumber: 4,
      });
      const candidate = createCandidate({
        id: "c-3",
        stepIndex: 2,
        trialNumber: 7,
        parentCandidateIds: ["p-1"],
      });

      const targets = buildPromptComparisonTargets({
        candidate,
        candidates: [baseline, parent, candidate],
        getPrompt: promptResolver({
          base: "baseline prompt",
          "p-1": "parent prompt",
          "c-3": "current prompt",
        }),
      });

      expect(targets).toEqual([
        { id: "base", label: "codeDiff.baseline", prompt: "baseline prompt" },
        {
          id: "p-1",
          label: "codeDiff.parent",
          caption: "codeDiff.trialNumber",
          prompt: "parent prompt",
        },
      ]);
    });

    it("disambiguates multiple parents by trial number, preserving order", () => {
      const parentA = createCandidate({ id: "p-a", trialNumber: 2 });
      const parentB = createCandidate({ id: "p-b", trialNumber: 3 });
      const candidate = createCandidate({
        id: "c-3",
        trialNumber: 5,
        parentCandidateIds: ["p-b", "p-a"],
      });

      const targets = buildPromptComparisonTargets({
        candidate,
        candidates: [parentA, parentB, candidate],
        getPrompt: promptResolver({
          "p-a": "a",
          "p-b": "b",
          "c-3": "current",
        }),
      });

      expect(targets.map((t) => t.id)).toEqual(["p-b", "p-a"]);
      // Both keep the plain "Parent" label; the trial tag distinguishes them.
      expect(targets.map((t) => t.label)).toEqual([
        "codeDiff.parent",
        "codeDiff.parent",
      ]);
      expect(targets.map((t) => t.caption)).toEqual([
        "codeDiff.trialNumber",
        "codeDiff.trialNumber",
      ]);
    });

    it("gives an unnumbered (baseline) parent no trial tag", () => {
      // v2 leaves the baseline unnumbered (OPIK-7589); a caption of
      // "Trial #null" must never render.
      const parent = createCandidate({
        id: "p-base",
        stepIndex: 1,
        trialNumber: null,
      });
      const candidate = createCandidate({
        id: "c-2",
        trialNumber: 1,
        parentCandidateIds: ["p-base"],
      });

      const targets = buildPromptComparisonTargets({
        candidate,
        candidates: [parent, candidate],
        getPrompt: promptResolver({ "p-base": "parent", "c-2": "current" }),
      });

      expect(targets).toEqual([
        {
          id: "p-base",
          label: "codeDiff.parent",
          caption: undefined,
          prompt: "parent",
        },
      ]);
    });

    it("skips parent ids that are not present in the candidate list", () => {
      const candidate = createCandidate({
        id: "c-3",
        parentCandidateIds: ["missing"],
      });

      const targets = buildPromptComparisonTargets({
        candidate,
        candidates: [candidate],
        getPrompt: promptResolver({ "c-3": "current" }),
      });

      expect(targets).toEqual([]);
    });
  });

  describe("deduplication & exclusions", () => {
    it("does not duplicate the baseline when it is also a parent", () => {
      const baseline = createCandidate({ id: "base", stepIndex: 0 });
      const candidate = createCandidate({
        id: "c-2",
        parentCandidateIds: ["base"],
      });

      const targets = buildPromptComparisonTargets({
        candidate,
        candidates: [baseline, candidate],
        getPrompt: promptResolver({
          base: "baseline prompt",
          "c-2": "current",
        }),
      });

      expect(targets).toEqual([
        { id: "base", label: "codeDiff.baseline", prompt: "baseline prompt" },
      ]);
    });

    it("never offers the candidate as its own comparison target", () => {
      const baseline = createCandidate({ id: "base", stepIndex: 0 });
      const candidate = createCandidate({
        id: "c-2",
        parentCandidateIds: ["c-2"],
      });

      const targets = buildPromptComparisonTargets({
        candidate,
        candidates: [baseline, candidate],
        getPrompt: promptResolver({ base: "baseline", "c-2": "current" }),
      });

      expect(targets.map((t) => t.id)).toEqual(["base"]);
    });

    it("skips targets whose prompt cannot be resolved", () => {
      const baseline = createCandidate({ id: "base", stepIndex: 0 });
      const parent = createCandidate({ id: "p-1", trialNumber: 3 });
      const candidate = createCandidate({
        id: "c-2",
        parentCandidateIds: ["p-1"],
      });

      const targets = buildPromptComparisonTargets({
        candidate,
        candidates: [baseline, parent, candidate],
        // baseline prompt missing -> baseline target dropped; parent kept
        getPrompt: promptResolver({ "p-1": "parent prompt", "c-2": "current" }),
      });

      expect(targets).toEqual([
        {
          id: "p-1",
          label: "codeDiff.parent",
          caption: "codeDiff.trialNumber",
          prompt: "parent prompt",
        },
      ]);
    });
  });
});
