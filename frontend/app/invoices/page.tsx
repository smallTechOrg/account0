"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { inr } from "@/lib/format";

export default function Invoices() {
  const [rows, setRows] = useState<any[]>([]);
  const [period, setPeriod] = useState("");
  const [status, setStatus] = useState("");
  useEffect(() => {
    const q = new URLSearchParams();
    if (period) q.set("period", period);
    if (status) q.set("status", status);
    api(`/api/invoices?${q}`).then(setRows);
  }, [period, status]);
  return (
    <>
      <h1>Invoices</h1>
      <div className="row" style={{ marginBottom: 10 }}>
        <div><label>Period (YYYY-MM)</label><input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="2026-10" /></div>
        <div><label>Status</label><select value={status} onChange={(e) => setStatus(e.target.value)}><option value="">all</option><option>DRAFT</option><option>CONFIRMED</option><option>REJECTED</option></select></div>
      </div>
      <table>
        <thead><tr><th>#</th><th>Date</th><th>Dir</th><th>Party</th><th>GSTIN</th><th>Invoice</th><th>Supply type</th><th className="num">Taxable</th><th className="num">Tax</th><th className="num">Total</th><th>Status</th></tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td><a href={`/review/${r.id}`}>{r.id}</a></td><td>{r.invoice_date}</td><td>{r.direction}</td><td>{r.counterparty_name}</td><td>{r.counterparty_gstin}</td>
              <td>{r.invoice_no}{r.doc_type !== "INVOICE" && <span className="pill">{r.doc_type}</span>}</td><td>{r.supply_type ?? <span className="pill warn">unconfirmed</span>}</td>
              <td className="num">{inr(r.taxable_paise)}</td><td className="num">{inr(r.cgst_paise + r.sgst_paise + r.igst_paise + r.cess_paise)}</td><td className="num">{inr(r.total_paise)}</td>
              <td><span className={`pill ${r.status === "CONFIRMED" ? "ok" : r.status === "REJECTED" ? "bad" : "warn"}`}>{r.status}</span></td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
