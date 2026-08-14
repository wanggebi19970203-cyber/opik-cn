import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import TrialStatusPill from "./TrialStatusPill";

vi.mock("i18next", () => ({
  default: {
    t: (key: string) => key,
  },
}));

describe("TrialStatusPill", () => {
  it("shows the human status label with the status-coloured dot", () => {
    const { container } = render(<TrialStatusPill status="pruned" />);

    expect(
      screen.getByText("experiments:optimizationChart.trialStatus.discarded"),
    ).toBeInTheDocument();
    const dot = container.querySelector("span.rounded-full");
    // jsdom does not resolve CSS variables, so assert on the raw style attr.
    expect(dot).toHaveAttribute(
      "style",
      expect.stringContaining("var(--trial-pruned)"),
    );
  });

  it("labels the baseline trial", () => {
    render(<TrialStatusPill status="baseline" />);
    expect(
      screen.getByText("experiments:optimizationChart.trialStatus.baseline"),
    ).toBeInTheDocument();
  });

  it("labels a failed trial with a red dot", () => {
    const { container } = render(<TrialStatusPill status="failed" />);

    expect(
      screen.getByText("experiments:optimizationChart.trialStatus.failed"),
    ).toBeInTheDocument();
    const dot = container.querySelector("span.rounded-full");
    expect(dot).toHaveAttribute(
      "style",
      expect.stringContaining("var(--color-red)"),
    );
  });

  it("overrides the status with the two-tone Best treatment", () => {
    const { container } = render(<TrialStatusPill status="passed" isBest />);

    expect(screen.getByText("optimizationChart.best")).toBeInTheDocument();
    expect(
      screen.queryByText("experiments:optimizationChart.trialStatus.passed"),
    ).not.toBeInTheDocument();
    const dot = container.querySelector("span.rounded-full");
    expect(dot).toHaveAttribute(
      "style",
      expect.stringContaining("var(--trial-best)"),
    );
  });
});
