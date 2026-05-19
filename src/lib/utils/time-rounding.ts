/**
 * Apply per-org time rounding to a duration in minutes.
 * DOWN_* variants floor to a minimum of one increment so very short
 * entries are not zeroed out.
 */
export function applyTimeRounding(minutes: number, mode: string): number {
  if (minutes <= 0) return minutes;
  switch (mode) {
    case "UP_30":   return Math.ceil(minutes / 30) * 30;
    case "UP_60":   return Math.ceil(minutes / 60) * 60;
    case "DOWN_30": return Math.max(30, Math.floor(minutes / 30) * 30);
    case "DOWN_60": return Math.max(60, Math.floor(minutes / 60) * 60);
    default:        return minutes; // NONE
  }
}
