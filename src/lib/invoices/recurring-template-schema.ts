import { z } from "zod";
import { Currency, PaymentMethod, InvoiceTemplate } from "@prisma/client";

/** Line items stored on recurring rules (no task linkage). */
export const recurringTemplateLineSchema = z.object({
  name: z.string().min(1),
  description: z.string().optional(),
  quantity: z.string().min(1),
  qtyType: z.enum(["QTY", "HOURS", "QTY_HOURS"]).default("QTY"),
  unitPrice: z.string().min(1),
  sortOrder: z.number().int().optional(),
});

export const recurringRuleTemplateDataSchema = z.object({
  currency: z.nativeEnum(Currency).default("EUR"),
  vatRate: z.string().optional(),
  vatIncluded: z.boolean().default(false),
  discountType: z.enum(["NONE", "PERCENTAGE", "FIXED"]).default("NONE"),
  discountValue: z.string().optional(),
  discountBeforeTax: z.boolean().default(true),
  notes: z.string().optional(),
  termsAndConditions: z.string().optional(),
  paymentMethod: z.nativeEnum(PaymentMethod).optional(),
  template: z.nativeEnum(InvoiceTemplate).optional(),
  daysUntilDue: z.number().int().min(0).max(3650).default(30),
  /** When MONTHLY + AUTO_BY_ISSUE_DATE, worker sets invoice periodFrom/periodTo from issue date (UTC). */
  billedPeriodMode: z.enum(["NONE", "AUTO_BY_ISSUE_DATE"]).default("NONE"),
  lines: z.array(recurringTemplateLineSchema).min(1),
});

export type RecurringRuleTemplateDataInput = z.infer<typeof recurringRuleTemplateDataSchema>;

export const recurringRuleCreateSchema = z.object({
  customerId: z.string().min(1),
  interval: z.enum(["WEEKLY", "BIWEEKLY", "MONTHLY"]),
  nextRunAt: z.string().min(1),
  endAt: z.string().optional(),
  templateData: recurringRuleTemplateDataSchema,
});

export type RecurringRuleCreateInput = z.infer<typeof recurringRuleCreateSchema>;
