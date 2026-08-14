import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import AddToDatasetDialog from "./AddToDatasetDialog";
import { Trace, Span, SPAN_TYPE } from "@/types/traces";
import { ReactNode } from "react";
import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";

// Create mock functions that can be accessed in tests
const mockAddTracesToDataset = vi.fn();
const mockAddSpansToDataset = vi.fn();

// Mock the API hooks
vi.mock("@/api/datasets/useDatasetsList", () => ({
  default: vi.fn(() => ({
    data: {
      content: [
        {
          id: "dataset-1",
          name: "Test Dataset 1",
          description: "First test dataset",
        },
        {
          id: "dataset-2",
          name: "Test Dataset 2",
          description: "Second test dataset",
        },
      ],
      total: 2,
    },
    isPending: false,
  })),
}));

vi.mock("@/api/datasets/useAddTracesToDatasetMutation", () => ({
  default: () => ({
    mutate: mockAddTracesToDataset,
  }),
}));

vi.mock("@/api/datasets/useAddSpansToDatasetMutation", () => ({
  default: () => ({
    mutate: mockAddSpansToDataset,
  }),
}));

// Mock the store
vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) =>
    selector({
      activeWorkspaceName: "test-workspace",
    }),
  ),
}));

// Mock the toast hook
vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({
    toast: vi.fn(),
  }),
}));

// Mock the AddEditTestSuiteDialog component
vi.mock("@/v1/shared/AddEditTestSuiteDialog/AddEditTestSuiteDialog", () => ({
  default: () => <div data-testid="add-edit-test-suite-dialog" />,
}));

describe("AddToDatasetDialog", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    vi.clearAllMocks();
  });

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <PermissionsProvider value={DEFAULT_PERMISSIONS}>
        {children}
      </PermissionsProvider>
    </QueryClientProvider>
  );

  const mockTrace: Trace = {
    id: "trace-1",
    name: "Test Trace",
    input: { prompt: "test input" },
    output: { response: "test output" },
    start_time: "2024-01-01T00:00:00Z",
    end_time: "2024-01-01T00:00:01Z",
    duration: 1000,
    created_at: "2024-01-01T00:00:00Z",
    last_updated_at: "2024-01-01T00:00:01Z",
    tags: ["tag1", "tag2"],
    metadata: {},
    feedback_scores: [],
    comments: [],
    project_id: "project-1",
  };

  const mockSpan: Span = {
    id: "span-1",
    name: "Test Span",
    type: SPAN_TYPE.llm,
    input: { prompt: "span input" },
    output: { response: "span output" },
    start_time: "2024-01-01T00:00:00Z",
    end_time: "2024-01-01T00:00:01Z",
    duration: 1000,
    created_at: "2024-01-01T00:00:00Z",
    last_updated_at: "2024-01-01T00:00:01Z",
    metadata: {},
    feedback_scores: [],
    comments: [],
    tags: [],
    trace_id: "trace-1",
    parent_span_id: "",
    project_id: "project-1",
  };

  const defaultProps = {
    selectedRows: [mockTrace],
    open: true,
    setOpen: vi.fn(),
  };

  it("should render the dialog when open", () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    expect(
      screen.getByText("addToDataset.selectTestSuite"),
    ).toBeInTheDocument();
    expect(screen.getByText("Test Dataset 1")).toBeInTheDocument();
  });

  it("should display enrichment checkboxes when only traces are selected", () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    // Expand the accordion first
    const accordionButton = screen.getByText("addToDataset.traceMetadataConfig");
    fireEvent.click(accordionButton);

    expect(screen.getByLabelText("addToDataset.nestedSpans")).toBeInTheDocument();
    expect(screen.getByLabelText("addToDataset.tags")).toBeInTheDocument();
    expect(screen.getByLabelText("addToDataset.feedbackScores")).toBeInTheDocument();
    expect(screen.getByLabelText("addToDataset.comments")).toBeInTheDocument();
    expect(screen.getByLabelText("addToDataset.usageMetrics")).toBeInTheDocument();
    expect(screen.getByLabelText("addToDataset.metadata")).toBeInTheDocument();
  });

  it("should have all enrichment checkboxes checked by default", () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    // Expand the accordion first
    const accordionButton = screen.getByText("addToDataset.traceMetadataConfig");
    fireEvent.click(accordionButton);

    expect(screen.getByLabelText("addToDataset.nestedSpans")).toBeChecked();
    expect(screen.getByLabelText("addToDataset.tags")).toBeChecked();
    expect(screen.getByLabelText("addToDataset.feedbackScores")).toBeChecked();
    expect(screen.getByLabelText("addToDataset.comments")).toBeChecked();
    expect(screen.getByLabelText("addToDataset.usageMetrics")).toBeChecked();
    expect(screen.getByLabelText("addToDataset.metadata")).toBeChecked();
  });

  it("should allow unchecking enrichment options", async () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    // Expand the accordion first
    const accordionButton = screen.getByText("addToDataset.traceMetadataConfig");
    fireEvent.click(accordionButton);

    const spansCheckbox = screen.getByLabelText("addToDataset.nestedSpans");
    const tagsCheckbox = screen.getByLabelText("addToDataset.tags");

    fireEvent.click(spansCheckbox);
    fireEvent.click(tagsCheckbox);

    await waitFor(() => {
      expect(spansCheckbox).not.toBeChecked();
      expect(tagsCheckbox).not.toBeChecked();
    });
    expect(screen.getByLabelText("addToDataset.feedbackScores")).toBeChecked();
  });

  it("should display span enrichment checkboxes when only spans are selected", () => {
    const propsWithSpan = {
      ...defaultProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    // Expand the accordion first
    const accordionButton = screen.getByText("addToDataset.spanMetadataConfig");
    fireEvent.click(accordionButton);

    // Spans don't have "addToDataset.nestedSpans" option
    expect(screen.queryByLabelText("addToDataset.nestedSpans")).not.toBeInTheDocument();
    // But they have all other options
    expect(screen.getByLabelText("addToDataset.tags")).toBeInTheDocument();
    expect(screen.getByLabelText("addToDataset.feedbackScores")).toBeInTheDocument();
    expect(screen.getByLabelText("addToDataset.comments")).toBeInTheDocument();
    expect(screen.getByLabelText("addToDataset.usageMetrics")).toBeInTheDocument();
    expect(screen.getByLabelText("addToDataset.metadata")).toBeInTheDocument();
  });

  it("should have all span enrichment checkboxes checked by default", () => {
    const propsWithSpan = {
      ...defaultProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    // Expand the accordion first
    const accordionButton = screen.getByText("addToDataset.spanMetadataConfig");
    fireEvent.click(accordionButton);

    // Spans don't have "addToDataset.nestedSpans" option, but all others should be checked
    expect(screen.getByLabelText("addToDataset.tags")).toBeChecked();
    expect(screen.getByLabelText("addToDataset.feedbackScores")).toBeChecked();
    expect(screen.getByLabelText("addToDataset.comments")).toBeChecked();
    expect(screen.getByLabelText("addToDataset.usageMetrics")).toBeChecked();
    expect(screen.getByLabelText("addToDataset.metadata")).toBeChecked();
  });

  it("should allow unchecking span enrichment options", async () => {
    const propsWithSpan = {
      ...defaultProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    // Expand the accordion first
    const accordionButton = screen.getByText("addToDataset.spanMetadataConfig");
    fireEvent.click(accordionButton);

    const tagsCheckbox = screen.getByLabelText("addToDataset.tags");
    const usageCheckbox = screen.getByLabelText("addToDataset.usageMetrics");

    fireEvent.click(tagsCheckbox);
    fireEvent.click(usageCheckbox);

    await waitFor(() => {
      expect(tagsCheckbox).not.toBeChecked();
      expect(usageCheckbox).not.toBeChecked();
    });
    expect(screen.getByLabelText("addToDataset.feedbackScores")).toBeChecked();
  });

  it("should display available datasets", () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    expect(screen.getByText("Test Dataset 1")).toBeInTheDocument();
    expect(screen.getByText("First test dataset")).toBeInTheDocument();
    expect(screen.getByText("Test Dataset 2")).toBeInTheDocument();
    expect(screen.getByText("Second test dataset")).toBeInTheDocument();
  });

  it("should display search input", () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    const searchInput = screen.getByPlaceholderText("placeholders.search");
    expect(searchInput).toBeInTheDocument();
  });

  it("should display create new test suite button", () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    expect(
      screen.getByText("addToDataset.createNewTestSuite"),
    ).toBeInTheDocument();
  });

  it("should show alert when no valid rows are present", () => {
    const propsWithInvalidRows = {
      ...defaultProps,
      selectedRows: [{ ...mockTrace, input: undefined as unknown as object }],
    };

    render(<AddToDatasetDialog {...propsWithInvalidRows} />, { wrapper });

    expect(screen.getByText("addToDataset.noValidRows")).toBeInTheDocument();
  });

  it("should show alert when only some rows are valid", () => {
    const propsWithPartialValid = {
      ...defaultProps,
      selectedRows: [
        mockTrace,
        { ...mockTrace, id: "trace-2", input: undefined as unknown as object },
      ],
    };

    render(<AddToDatasetDialog {...propsWithPartialValid} />, { wrapper });

    expect(
      screen.getByText("addToDataset.partialValidRows"),
    ).toBeInTheDocument();
  });

  it("should disable create new test suite button when no valid rows", () => {
    const propsWithInvalidRows = {
      ...defaultProps,
      selectedRows: [{ ...mockTrace, input: undefined as unknown as object }],
    };

    render(<AddToDatasetDialog {...propsWithInvalidRows} />, { wrapper });

    const createButton = screen.getByText("addToDataset.createNewTestSuite");
    expect(createButton).toBeDisabled();
  });

  it("should call addTracesToDataset mutation when clicking on dataset with only traces", async () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    // Select the dataset
    const dataset = screen.getByText("Test Dataset 1");
    fireEvent.click(dataset);

    // Click the "Add to test suite" button
    const addButton = screen.getByRole("button", {
      name: "addToDataset.addToTestSuite",
    });
    fireEvent.click(addButton);

    await waitFor(() => {
      expect(mockAddTracesToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          datasetId: "dataset-1",
          traceIds: ["trace-1"],
          enrichmentOptions: {
            include_spans: true,
            include_tags: true,
            include_feedback_scores: true,
            include_comments: true,
            include_usage: true,
            include_metadata: true,
          },
          workspaceName: "test-workspace",
        }),
        expect.any(Object),
      );
    });
  });

  it("should call addSpansToDataset mutation when clicking on dataset with only spans", async () => {
    const propsWithSpan = {
      ...defaultProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    // Select the dataset
    const dataset = screen.getByText("Test Dataset 1");
    fireEvent.click(dataset);

    // Click the "Add to test suite" button
    const addButton = screen.getByRole("button", {
      name: "addToDataset.addToTestSuite",
    });
    fireEvent.click(addButton);

    await waitFor(() => {
      expect(mockAddSpansToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          datasetId: "dataset-1",
          spanIds: ["span-1"],
          enrichmentOptions: {
            include_tags: true,
            include_feedback_scores: true,
            include_comments: true,
            include_usage: true,
            include_metadata: true,
          },
          workspaceName: "test-workspace",
        }),
        expect.any(Object),
      );
    });
  });

  it("should respect unchecked enrichment options when adding traces", async () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    // Expand the accordion first
    const accordionButton = screen.getByText("addToDataset.traceMetadataConfig");
    fireEvent.click(accordionButton);

    // Uncheck some options
    fireEvent.click(screen.getByLabelText("addToDataset.nestedSpans"));
    fireEvent.click(screen.getByLabelText("addToDataset.tags"));
    fireEvent.click(screen.getByLabelText("addToDataset.usageMetrics"));

    // Select the dataset
    const dataset = screen.getByText("Test Dataset 1");
    fireEvent.click(dataset);

    // Click the "Add to test suite" button
    const addButton = screen.getByRole("button", {
      name: "addToDataset.addToTestSuite",
    });
    fireEvent.click(addButton);

    await waitFor(() => {
      expect(mockAddTracesToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          enrichmentOptions: {
            include_spans: false,
            include_tags: false,
            include_feedback_scores: true,
            include_comments: true,
            include_usage: false,
            include_metadata: true,
          },
        }),
        expect.any(Object),
      );
    });
  });

  it("should respect unchecked enrichment options when adding spans", async () => {
    const propsWithSpan = {
      ...defaultProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    // Expand the accordion first
    const accordionButton = screen.getByText("addToDataset.spanMetadataConfig");
    fireEvent.click(accordionButton);

    // Uncheck some options
    fireEvent.click(screen.getByLabelText("addToDataset.tags"));
    fireEvent.click(screen.getByLabelText("addToDataset.comments"));
    fireEvent.click(screen.getByLabelText("addToDataset.metadata"));

    // Select the dataset
    const dataset = screen.getByText("Test Dataset 1");
    fireEvent.click(dataset);

    // Click the "Add to test suite" button
    const addButton = screen.getByRole("button", {
      name: "addToDataset.addToTestSuite",
    });
    fireEvent.click(addButton);

    await waitFor(() => {
      expect(mockAddSpansToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          enrichmentOptions: {
            include_tags: false,
            include_feedback_scores: true,
            include_comments: false,
            include_usage: true,
            include_metadata: false,
          },
        }),
        expect.any(Object),
      );
    });
  });
});
