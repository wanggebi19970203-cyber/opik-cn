import { DateRangePreset, DateRangeValue } from "./types";
import dayjs from "dayjs";

export const PRESET_DATE_RANGES: Record<DateRangePreset, DateRangeValue> = {
  past24hours: {
    from: dayjs().subtract(24, "hours").startOf("hour").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  past3days: {
    from: dayjs().subtract(2, "days").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  past7days: {
    from: dayjs().subtract(6, "days").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  past30days: {
    from: dayjs().subtract(29, "days").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  past60days: {
    from: dayjs().subtract(59, "days").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  alltime: {
    from: dayjs().subtract(5, "years").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
};

export const PRESET_LABEL_KEY_MAP: Record<DateRangePreset, string> = {
  past24hours: "common.dateRange.presets.past24hours",
  past3days: "common.dateRange.presets.past3days",
  past7days: "common.dateRange.presets.past7days",
  past30days: "common.dateRange.presets.past30days",
  past60days: "common.dateRange.presets.past60days",
  alltime: "common.dateRange.presets.alltime",
};
