import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import TrialStatusCard from "./TrialStatusCard";

vi.mock("i18next", () => ({
  default: {
    t: (key: string) => key,
  },
}));

describe("TrialStatusCard", () => {
  it("shows the status label, step tag, and created date", () => {
    render(
      <TrialStatusCard
        status="pruned"
        stepIndex={2}
        createdAt="2026-02-04T14:42:00Z"
      />,
    );
    expect(
      screen.getByText("experiments:optimizationChart.trialStatus.discarded"),
    ).toBeInTheDocument();
    expect(screen.getByText("optimization.trials.stepLabel")).toBeInTheDocument();
    // formatDate output varies by locale/config — assert the year renders.
    expect(screen.getByText(/2026/)).toBeInTheDocument();
  });

  it("labels step 0 as Baseline", () => {
    render(<TrialStatusCard status="baseline" stepIndex={0} />);
    expect(
      screen.getByText("optimization.trialStatus.baseline"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("experiments:optimizationChart.trialStatus.baseline"),
    ).toBeInTheDocument();
  });

  it("renders a dash without a status and omits step/date when absent", () => {
    render(<TrialStatusCard />);
    expect(screen.getByText("-")).toBeInTheDocument();
    expect(screen.queryByText("optimization.trials.stepLabel")).not.toBeInTheDocument();
  });
});
