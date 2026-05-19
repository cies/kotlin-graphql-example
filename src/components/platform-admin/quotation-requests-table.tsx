import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { QuotationRequestRow } from "@/lib/actions/platform-admin";

function formatWhen(iso: Date) {
  try {
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: "medium",
      timeStyle: "short",
    }).format(iso);
  } catch {
    return String(iso);
  }
}

export function QuotationRequestsTable({ rows }: { rows: QuotationRequestRow[] }) {
  if (rows.length === 0) {
    return (
      <p className="text-sm text-[var(--muted-foreground)] py-4">
        No quotation requests yet.
      </p>
    );
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead className="w-[180px]">Received</TableHead>
          <TableHead>Name</TableHead>
          <TableHead>Email</TableHead>
          <TableHead>Company</TableHead>
          <TableHead className="min-w-[240px]">Message</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((r) => (
          <TableRow key={r.id}>
            <TableCell className="align-top whitespace-nowrap text-[var(--muted-foreground)]">
              {formatWhen(r.createdAt)}
            </TableCell>
            <TableCell className="align-top font-medium">{r.name}</TableCell>
            <TableCell className="align-top">
              <a href={`mailto:${encodeURIComponent(r.email)}`} className="text-[var(--primary)] underline underline-offset-2">
                {r.email}
              </a>
            </TableCell>
            <TableCell className="align-top text-[var(--muted-foreground)]">
              {r.company || "-"}
            </TableCell>
            <TableCell className="align-top max-w-md whitespace-pre-wrap text-sm">
              {r.message}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
