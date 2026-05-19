export const PROJECT_STATUS_VALUES = ["NOT_STARTED", "ACTIVE", "PAUSED", "ARCHIVED"] as const;
export type ProjectStatusValue = (typeof PROJECT_STATUS_VALUES)[number];
