import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import TrialCard from "./TrialCard";
import { AggregatedCandidate } from "@/types/optimizations";

vi.mock("i18next", () => ({
  default: {
    t: (key: string) => key,
  },
}));

const candidate = {
  trialNumber: 20,
  score: 0.9,
  latencyP50: 24800,
  runtimeCost: 0.0008,
  passedCount: 9,
  totalCount: 10,
} as unknown as AggregatedCandidate;

describe("TrialCard", () => {
  it("shows the trial number, a status label in the header, and the core rows", () => {
    render(<TrialCard candidate={candidate} status="passed" />);

    expect(screen.getByText("experiments:trialNumber")).toBeInTheDocument();
    // The status carries no step reference — trial numbers are the chart's one
    // user-facing numbering (OPIK-7589).
    expect(
      screen.getByText("experiments:optimizationChart.trialStatus.passed"),
    ).toBeInTheDocument();
    // Status is a header label now, not a metric row.
    expect(screen.queryByText("Status")).not.toBeInTheDocument();
    expect(screen.getByText("experiments:score")).toBeInTheDocument();
    expect(screen.getByText("experiments:latency")).toBeInTheDocument();
    expect(screen.getByText("experiments:runtimeCost")).toBeInTheDocument();
  });

  it("labels the header 'Best trial' when isBest", () => {
    render(<TrialCard candidate={candidate} status="passed" isBest />);

    expect(screen.getByText("experiments:bestCandidate")).toBeInTheDocument();
    expect(
      screen.queryByText("experiments:optimizationChart.trialStatus.passed"),
    ).not.toBeInTheDocument();
    expect(screen.getByText("experiments:score")).toBeInTheDocument();
  });

  it("uses the Pass rate label for test suites", () => {
    render(<TrialCard candidate={candidate} status="passed" isTestSuite />);

    expect(screen.getByText("experiments:passRate")).toBeInTheDocument();
    expect(screen.queryByText("experiments:score")).not.toBeInTheDocument();
  });

  it("omits latency and cost rows when they are absent", () => {
    const sparse = {
      trialNumber: 1,
      score: 0.5,
      latencyP50: null,
      runtimeCost: null,
    } as unknown as AggregatedCandidate;

    render(<TrialCard candidate={sparse} status="pruned" />);

    expect(
      screen.getByText("experiments:optimizationChart.trialStatus.discarded"),
    ).toBeInTheDocument();
    expect(screen.queryByText("experiments:latency")).not.toBeInTheDocument();
    expect(
      screen.queryByText("experiments:runtimeCost"),
    ).not.toBeInTheDocument();
  });
});
