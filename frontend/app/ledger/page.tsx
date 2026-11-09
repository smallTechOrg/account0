"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { inr } from "@/lib/format";

export default function Ledger() {
  const [period, setPeriod] = useState("");
  const [tb, setTb] = useState<any>(null);
  const [journal, setJournal] = useState<any[]>([]);
  useEffect(() => {
    const q = period ? `?period=${period}` : "";
    api(`/api/ledger/trial-balance${q}`).then(setTb);
    api(`/api/ledger/journal${q}`).then(setJournal);
  }, [period]);
  return (
    <>
      <h1>Ledger</h1>
      <div className="row" style={{ marginBottom: 10 }}>
        <div><label>Period (YYYY-MM, blank = all)</label><input value={period} onChange={(e) => setPeriod(e.target.value)} /></div>
        <a className="btn" href={`/api/ledger/journal.csv${period ? `?period=${period}` : ""}`}>Export journal CSV (for CA)</a>
      </div>
      {tb && (
        <div className="card">
          <b>Trial balance</b> {tb.balanced ? <span className="pill ok">balanced</span> : <span className="pill bad">NOT balanced</span>}
          <table>
            <thead><tr><th>Account</th><th className="num">Debit</th><th className="num">Credit</th><th className="num">Balance</th></tr></thead>
            <tbody>
              {tb.accounts.map((a: any) => (
                <tr key={a.account}><td>{a.account}</td><td className="num">{inr(a.debit_paise)}</td><td className="num">{inr(a.credit_paise)}</td><td className="num">{inr(a.debit_paise - a.credit_paise)}</td></tr>
              ))}
              <tr><th>Total</th><th className="num">{inr(tb.totalDebitPaise)}</th><th className="num">{inr(tb.totalCreditPaise)}</th><th /></tr>
            </tbody>
          </table>
        </div>
      )}
      <h2>Journal</h2>
      {journal.map((j) => (
        <div className="card" key={j.id}>
          <b>{j.entry_date}</b> — {j.narration} <span className="muted">(<a href={`/review/${j.ref_id}`}>invoice #{j.ref_id}</a>)</span>
          <table><tbody>
            {j.lines.map((l: any, i: number) => (
              <tr key={i}><td style={{ paddingLeft: l.credit_paise ? 40 : 8 }}>{l.credit_paise ? "To " : ""}{l.account}</td><td className="num">{l.debit_paise ? inr(l.debit_paise) : ""}</td><td className="num">{l.credit_paise ? inr(l.credit_paise) : ""}</td></tr>
            ))}
          </tbody></table>
        </div>
      ))}
    </>
  );
}
