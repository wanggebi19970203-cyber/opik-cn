import { type ReactNode, useState } from "react";
import { useParams } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { AlertTriangle, Loader2, Play, Pause, Settings2 } from "lucide-react";
import briefingBulbIcon from "@/icons/briefing-bulb.svg";
import briefingBubbleIcon from "@/icons/briefing-bubble.svg";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import {
  formatRelativeDateTime,
  formatUtcTimeAsLocal,
  formatLocalTimeAsUtc,
  parseUtcTimeToLocalDate,
} from "@/lib/date";
import { Button } from "@/ui/button";
import { Skeleton } from "@/ui/skeleton";
import useReports from "@/api/projects/useReports";
import useReportPreference from "@/api/projects/useReportPreference";
import useGenerateReportMutation from "@/api/projects/useGenerateReportMutation";
import useUpdateReportPreferenceMutation from "@/api/projects/useUpdateReportPreferenceMutation";
import {
  OllieReport,
  RecommendedAction,
  ReportPreferenceSettings,
  ReportStatus,
} from "@/types/ollie-reports";
import ReportPanel from "./ReportPanel";
import TurnOnDialog from "./TurnOnDialog";
import SettingsDialog from "./SettingsDialog";

function getNextRunLabel(scheduleTimeUtc: string, t: (key: string, opts?: Record<string, unknown>) => string) {
  const day =
    parseUtcTimeToLocalDate(scheduleTimeUtc) > new Date()
      ? t("dailyBriefing.today")
      : t("dailyBriefing.tomorrow");
  return t("dailyBriefing.dayAtTime", { day, time: formatUtcTimeAsLocal(scheduleTimeUtc) });
}

function LoadingSkeleton() {
  return (
    <div className="flex flex-col gap-2">
      {Array.from({ length: 3 }).map((_, i) => (
        <Skeleton key={i} className="h-12 w-full rounded-md" />
      ))}
    </div>
  );
}

function StatusBadge({ enabled }: { enabled: boolean }) {
  const { t } = useTranslation("pages/project-home");
  if (enabled) {
    return (
      <span className="flex items-center gap-1.5 text-xs">
        <span className="size-1.5 rounded-full bg-emerald-400" />
        {t("dailyBriefing.active")}
      </span>
    );
  }

  return (
    <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
      <span className="size-1.5 rounded-full bg-chart-red" />
      {t("dailyBriefing.inactive")}
    </span>
  );
}

function PausedBadge() {
  const { t } = useTranslation("pages/project-home");
  return (
    <span className="flex items-center gap-1.5 text-xs">
      <Pause className="size-3 text-chart-red" />
      {t("dailyBriefing.paused")}
    </span>
  );
}

function EmptyState({
  icon,
  title,
  description,
  actionLabel,
  onAction,
}: {
  icon: string;
  title: string;
  description: string;
  actionLabel: string;
  onAction: () => void;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-1 rounded-lg border bg-background p-10">
      <img src={icon} alt="" className="mb-4 w-7" />
      <p className="comet-body-s font-medium">{title}</p>
      <p className="comet-body-xs mt-2 text-center text-muted-foreground">
        {description}
      </p>
      <button
        className="comet-body-xs mt-3 underline underline-offset-4 hover:text-primary"
        onClick={onAction}
      >
        {actionLabel}
      </button>
    </div>
  );
}

function BriefingRow({
  children,
  dashed,
  onClick,
}: {
  children: ReactNode;
  dashed?: boolean;
  onClick?: () => void;
}) {
  const Tag = onClick ? "button" : "div";
  return (
    <Tag
      className={`comet-body-xs flex w-full items-center justify-between rounded-md border px-3 py-2 ${
        dashed ? "border-dashed" : "bg-background"
      } ${onClick ? "text-left hover:border-primary hover:bg-primary/5" : ""}`}
      onClick={onClick}
    >
      {children}
    </Tag>
  );
}

function RunningRow() {
  const { t } = useTranslation("pages/project-home");
  return (
    <BriefingRow>
      <span>
        <span className="font-medium">{t("dailyBriefing.runningReport")}</span>{" "}
        <span className="text-muted-foreground">
          {t("dailyBriefing.runningReportHint")}
        </span>
      </span>
      <Loader2 className="size-4 animate-spin text-primary" />
    </BriefingRow>
  );
}

function ScheduledRow({
  nextRunLabel,
  onRunNow,
}: {
  nextRunLabel: string;
  onRunNow: () => void;
}) {
  const { t } = useTranslation("pages/project-home");
  return (
    <BriefingRow dashed>
      <span className="text-light-slate">{t("dailyBriefing.scheduled", { time: nextRunLabel })}</span>
      <Button
        variant="ghost"
        size="2xs"
        className="h-auto gap-1 p-0 text-foreground"
        onClick={onRunNow}
      >
        <Play className="size-3" />
        {t("dailyBriefing.runNow")}
      </Button>
    </BriefingRow>
  );
}

function FailedRow({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation("pages/project-home");
  return (
    <BriefingRow>
      <span className="flex items-center gap-2 text-destructive">
        <AlertTriangle className="size-3.5" />
        {t("dailyBriefing.briefingFailed")}
      </span>
      <Button
        variant="ghost"
        size="2xs"
        className="h-auto gap-1 p-0"
        onClick={onRetry}
      >
        <Play className="size-3" />
        {t("dailyBriefing.runAgain")}
      </Button>
    </BriefingRow>
  );
}

function PausedRow({ onReactivate }: { onReactivate: () => void }) {
  const { t } = useTranslation("pages/project-home");
  return (
    <BriefingRow dashed>
      <span className="text-light-slate">{t("dailyBriefing.reactivateDailyBriefing")}</span>
      <Button
        variant="ghost"
        size="2xs"
        className="h-auto gap-1 p-0 text-muted-slate"
        onClick={onReactivate}
      >
        <Play className="size-3" />
        {t("dailyBriefing.reactivate")}
      </Button>
    </BriefingRow>
  );
}

function ReportRow({
  report,
  onSelect,
}: {
  report: OllieReport;
  onSelect: (report: OllieReport) => void;
}) {
  const { t } = useTranslation("pages/project-home");
  const actionCount = report.recommended_actions?.length ?? 0;
  return (
    <BriefingRow onClick={() => onSelect(report)}>
      <span className="flex items-center gap-2">
        <span className="text-foreground">
          {formatRelativeDateTime(report.created_at)}
        </span>
        {actionCount > 0 && (
          <span className="text-muted-slate">
            {t("dailyBriefing.actionCount", { count: actionCount })}
          </span>
        )}
      </span>
    </BriefingRow>
  );
}

const POLLING_INTERVAL = 10_000;
const REPORT_PREVIEW_COUNT = 3;
const DEFAULT_SCHEDULE_TIME = formatLocalTimeAsUtc("07:00:00");

export default function DailyBriefingSection() {
  const { t } = useTranslation("pages/project-home");
  const { projectId } = useParams({ strict: false }) as {
    projectId: string;
  };

  const [showTurnOnDialog, setShowTurnOnDialog] = useState(false);
  const [showSettingsDialog, setShowSettingsDialog] = useState(false);
  const [showMore, setShowMore] = useState(false);
  const [selectedReport, setSelectedReport] = useState<OllieReport | null>(
    null,
  );
  const { data: preference, isPending: isPreferencePending } =
    useReportPreference({ projectId });
  const isEnabled = preference?.enabled ?? false;
  const scheduleTimeUtc = preference?.schedule_time ?? DEFAULT_SCHEDULE_TIME;
  const nextRunLabel = getNextRunLabel(scheduleTimeUtc, t);

  const { data: reportsData, isPending: isReportsPending } = useReports(
    { projectId },
    {
      enabled: !isPreferencePending,
      refetchInterval: (query) => {
        const hasRunning = query.state.data?.content?.some(
          (r) => r.status === ReportStatus.PENDING,
        );
        return hasRunning ? POLLING_INTERVAL : false;
      },
    },
  );

  const generateMutation = useGenerateReportMutation();
  const updatePreferenceMutation = useUpdateReportPreferenceMutation();

  const reports = reportsData?.content ?? [];
  const latestReport = reports[0];
  const hasRunning = latestReport?.status === ReportStatus.PENDING;
  const hasFailed = latestReport?.status === ReportStatus.FAILED;
  const isPaused =
    preference != null && !preference.enabled && reports.length > 0;
  const completedReports = reports.filter(
    (r) => r.status === ReportStatus.COMPLETED,
  );
  const displayReports = showMore
    ? completedReports
    : completedReports.slice(0, REPORT_PREVIEW_COUNT);

  const handleRunNow = () => {
    generateMutation.mutate({ projectId });
  };

  const handleTurnOn = (runImmediately: boolean) => {
    setShowTurnOnDialog(false);
    updatePreferenceMutation.mutate(
      {
        projectId,
        enabled: true,
        schedule_time: DEFAULT_SCHEDULE_TIME,
      },
      {
        onSuccess: () => {
          if (runImmediately) {
            generateMutation.mutate({ projectId });
          }
        },
      },
    );
  };

  const handleSaveSettings = (settings: ReportPreferenceSettings) => {
    setShowSettingsDialog(false);
    updatePreferenceMutation.mutate({
      projectId,
      enabled: settings.enabled,
      schedule_time: settings.scheduleTime,
      custom_prompt: settings.customPrompt,
    });
  };

  const handleReactivate = () => {
    updatePreferenceMutation.mutate({
      projectId,
      enabled: true,
      schedule_time: preference?.schedule_time ?? DEFAULT_SCHEDULE_TIME,
    });
  };

  const handleSelectReport = (report: OllieReport) => {
    setSelectedReport(report);
  };

  const handleStartConversation = (action: RecommendedAction) => {
    setSelectedReport(null);
    window.opikBridge?.startConversation(action.prompt);
  };

  const isLoading = isPreferencePending || isReportsPending;

  return (
    <section>
      <div className="mb-1.5 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <h2 className="comet-body-s-accented">{t("dailyBriefing.title")}</h2>
          {!isLoading &&
            (isPaused ? <PausedBadge /> : <StatusBadge enabled={isEnabled} />)}
        </div>
        {isEnabled && (
          <TooltipWrapper content={t("dailyBriefing.settings")}>
            <button
              className="text-foreground hover:text-foreground"
              onClick={() => setShowSettingsDialog(true)}
            >
              <Settings2 className="size-4" />
            </button>
          </TooltipWrapper>
        )}
      </div>

      {isLoading && <LoadingSkeleton />}

      {!isLoading && !isEnabled && !isPaused && (
        <EmptyState
          icon={briefingBulbIcon}
          title={t("dailyBriefing.emptyTitle")}
          description={t("dailyBriefing.emptyDescription")}
          actionLabel={t("dailyBriefing.turnOn")}
          onAction={() => setShowTurnOnDialog(true)}
        />
      )}

      {!isLoading && isEnabled && reports.length === 0 && (
        <EmptyState
          icon={briefingBubbleIcon}
          title={t("dailyBriefing.noBriefingsYet")}
          description={t("dailyBriefing.nextBriefingScheduled", { time: nextRunLabel })}
          actionLabel={t("dailyBriefing.runNow")}
          onAction={handleRunNow}
        />
      )}

      {(isEnabled || isPaused) && reports.length > 0 && (
        <div className="flex flex-col gap-1.5">
          {isPaused && <PausedRow onReactivate={handleReactivate} />}

          {isEnabled && hasRunning && <RunningRow />}

          {isEnabled && hasFailed && <FailedRow onRetry={handleRunNow} />}

          {isEnabled && !hasRunning && !hasFailed && (
            <ScheduledRow nextRunLabel={nextRunLabel} onRunNow={handleRunNow} />
          )}

          {displayReports.map((report) => (
            <ReportRow
              key={report.id}
              report={report}
              onSelect={handleSelectReport}
            />
          ))}

          {!showMore && completedReports.length > REPORT_PREVIEW_COUNT && (
            <Button
              variant="ghost"
              size="2xs"
              className="mt-1 h-auto self-start p-0 text-muted-slate"
              onClick={() => setShowMore(true)}
            >
              {t("dailyBriefing.showMore")}
            </Button>
          )}
        </div>
      )}

      <TurnOnDialog
        open={showTurnOnDialog}
        onOpenChange={setShowTurnOnDialog}
        onConfirm={handleTurnOn}
        scheduleTimeLocal={formatUtcTimeAsLocal(DEFAULT_SCHEDULE_TIME)}
      />

      {preference != null && (
        <SettingsDialog
          open={showSettingsDialog}
          onOpenChange={setShowSettingsDialog}
          enabled={preference.enabled}
          scheduleTime={preference.schedule_time}
          customPrompt={preference.custom_prompt ?? ""}
          onSave={handleSaveSettings}
        />
      )}

      <ReportPanel
        report={selectedReport}
        onClose={() => setSelectedReport(null)}
        onStartConversation={handleStartConversation}
      />
    </section>
  );
}
