"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { inr } from "@/lib/format";

export default function ReviewQueue() {
  const [rows, setRows] = useState<any[]>([]);
  useEffect(() => { api("/api/invoices?status=DRAFT").then(setRows); }, []);
  return (
    <>
      <h1>Review queue</h1>
      <p className="muted">Drafts waiting for you. Account0 never decides tax treatment — you confirm supply type, reverse charge and ITC on each invoice (saved rules pre-fill them).</p>
      <table>
        <thead><tr><th>#</th><th>Party</th><th>Invoice</th><th>Date</th><th>Direction</th><th className="num">Total</th><th>Flags</th><th></th></tr></thead>
        <tbody>
          {rows.map((r) => {
            const errs = r.flags.filter((f: any) => f.severity === "ERROR").length;
            const warns = r.flags.filter((f: any) => f.severity === "WARN").length;
            return (
              <tr key={r.id}>
                <td>{r.id}</td><td>{r.counterparty_name}</td><td>{r.invoice_no}</td><td>{r.invoice_date}</td><td>{r.direction ?? "?"}</td>
                <td className="num">{inr(r.total_paise)}</td>
                <td>{errs > 0 && <span className="pill bad">{errs} error</span>} {warns > 0 && <span className="pill warn">{warns} check</span>} {r.tax_treatment_source === "RULE" && <span className="pill ok">rule</span>}</td>
                <td><a className="btn" href={`/review/${r.id}`}>Review</a></td>
              </tr>
            );
          })}
          {rows.length === 0 && <tr><td colSpan={8} className="muted">Nothing to review. Upload invoices in the Inbox.</td></tr>}
        </tbody>
      </table>
    </>
  );
}
