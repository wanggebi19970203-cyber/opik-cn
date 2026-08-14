import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

import TrialPromptSection from "./TrialPromptSection";
import { AggregatedCandidate } from "@/types/optimizations";
import { Experiment } from "@/types/datasets";

vi.mock("i18next", () => ({
  default: {
    t: (key: string) => key,
  },
}));

const makeExperiment = (id: string, prompt: unknown): Experiment =>
  ({ id, metadata: { configuration: { prompt } } }) as unknown as Experiment;

const makeCandidate = (
  candidateId: string,
  stepIndex: number,
  trialNumber: number,
  experimentIds: string[],
  parentCandidateIds: string[] = [],
): AggregatedCandidate =>
  ({
    id: candidateId,
    candidateId,
    stepIndex,
    trialNumber,
    experimentIds,
    parentCandidateIds,
  }) as unknown as AggregatedCandidate;

const baseline = makeCandidate("c0", 0, 1, ["e0"]);
const trial = makeCandidate("c1", 2, 3, ["e1"], ["c0"]);

const experiments = [
  makeExperiment("e0", [{ role: "system", content: "baseline prompt" }]),
  makeExperiment("e1", [{ role: "system", content: "improved prompt" }]),
];

const candidates = [baseline, trial];

describe("TrialPromptSection", () => {
  it("shows the 'Trial prompt' title with the prompt, no diff by default", () => {
    const { container } = render(
      <TrialPromptSection
        candidate={trial}
        candidates={candidates}
        experiments={experiments}
      />,
    );

    expect(
      screen.getByText("optimization.trialPrompt.title"),
    ).toBeInTheDocument();
    expect(container.textContent).toContain("improved prompt");
    expect(screen.getByText("codeDiff.showDiff")).toBeInTheDocument();
    expect(container.textContent).not.toContain("baseline prompt");
  });

  it("toggles the baseline diff from the header action", () => {
    const { container } = render(
      <TrialPromptSection
        candidate={trial}
        candidates={candidates}
        experiments={experiments}
      />,
    );

    fireEvent.click(screen.getByText("codeDiff.showDiff"));

    expect(screen.getByText("codeDiff.hideDiff")).toBeInTheDocument();
    // the plain title is replaced by the compare picker + arrow
    expect(screen.getByText("codeDiff.baseline")).toBeInTheDocument();
    expect(
      screen.getByText("→ optimization.trialPrompt.trial"),
    ).toBeInTheDocument();
    expect(container.textContent).toContain("baseline prompt");
  });

  it("opens straight into the diff when defaultDiff is set", () => {
    render(
      <TrialPromptSection
        candidate={trial}
        candidates={candidates}
        experiments={experiments}
        defaultDiff
      />,
    );

    expect(screen.getByText("codeDiff.hideDiff")).toBeInTheDocument();
    expect(screen.getByText("codeDiff.baseline")).toBeInTheDocument();
  });

  it("hides the diff toggle when the trial is the baseline", () => {
    render(
      <TrialPromptSection
        candidate={baseline}
        candidates={candidates}
        experiments={experiments}
      />,
    );

    expect(
      screen.getByText("optimization.trialPrompt.title"),
    ).toBeInTheDocument();
    expect(screen.queryByText("codeDiff.showDiff")).not.toBeInTheDocument();
  });

  it("renders a placeholder when the trial has no resolvable prompt", () => {
    render(
      <TrialPromptSection
        candidate={makeCandidate("c9", 1, 2, ["missing"])}
        candidates={candidates}
        experiments={experiments}
      />,
    );

    expect(
      screen.getByText("optimization.trialPrompt.noPromptAvailable"),
    ).toBeInTheDocument();
  });
});
