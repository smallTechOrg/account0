"use client";
import { useEffect, useState } from "react";
import { api, jsonBody } from "@/lib/api";
import { inr, toPaise } from "@/lib/format";
import { Empty } from "@/lib/Empty";

const TAG: Record<string, string> = { MATCHED: "ok", SUGGESTED: "warn", UNMATCHED: "bad", IGNORED: "" };

export default function Bank() {
  const [txns, setTxns] = useState<any[]>([]);
  const [sum, setSum] = useState<any>(null);
  const [prev, setPrev] = useState<any>(null);
  const [file, setFile] = useState<File | null>(null);
  const [account, setAccount] = useState("Main account");
  const [map, setMap] = useState<Record<string, number>>({});
  const [msg, setMsg] = useState("");
  const [cands, setCands] = useState<{ id: number; list: any[] } | null>(null);
  const [filter, setFilter] = useState("");
  const load = () => { api("/api/bank/txns").then(setTxns); api("/api/bank/summary").then(setSum); };
  useEffect(() => { load(); }, []);
  const pick = async (f: File | undefined) => {
    if (!f) return; setFile(f); setMsg("");
    try { const fd = new FormData(); fd.append("file", f); const p = await api("/api/bank/preview", { method: "POST", body: fd }); setPrev(p); setMap(p.mapping); } catch (e: any) { setMsg(e.message); setPrev(null); }
  };
  const doImport = async () => {
    if (!file) return;
    try {
      const fd = new FormData(); fd.append("file", file);
      const q = new URLSearchParams({ account }); Object.entries(map).forEach(([k, v]) => { if (v >= 0) q.set(k, String(v)); });
      const r = await api(`/api/bank/import?${q}`, { method: "POST", body: fd });
      setMsg(`Imported ${r.new} new lines (${r.duplicates} already there). Auto-matched ${r.matching.autoMatched}, suggested ${r.matching.suggested}. ${r.balanceCheck.note}`); setPrev(null); setFile(null); load();
    } catch (e: any) { setMsg(e.message); }
  };
  const act = async (fn: () => Promise<any>) => { try { await fn(); setCands(null); load(); } catch (e: any) { setMsg(e.message); } };
  const shown = txns.filter((t) => !filter || t.status === filter);
  const cols = ["date", "description", "ref", "debit", "credit", "amount", "type", "balance"];
  return (
    <>
      <h1>Bank statements &amp; reconciliation</h1>
      <p className="muted">Import a CSV or Excel statement; Account0 matches each line to a confirmed invoice by amount, date window, invoice number and vendor name. Only unambiguous strong matches are applied automatically — everything else is a suggestion you accept. A customer paying net of TDS is recognised and shown as such.</p>
      <div className="card">
        <div className="row"><div><label>Statement file (.csv / .xlsx)</label><input type="file" accept=".csv,.xls,.xlsx" onChange={(e) => pick(e.target.files?.[0])} /></div>
          <div><label>Account</label><input value={account} onChange={(e) => setAccount(e.target.value)} /></div></div>
        {prev && <>
          <h2>Check the columns</h2>
          <div className="row">{cols.map((c) => <div key={c}><label>{c}</label><select value={map[c] ?? -1} onChange={(e) => setMap({ ...map, [c]: Number(e.target.value) })}><option value={-1}>—</option>{prev.headers.map((h: string, i: number) => <option key={i} value={i}>{h}</option>)}</select></div>)}</div>
          <table><thead><tr>{prev.headers.map((h: string, i: number) => <th key={i}>{h}</th>)}</tr></thead><tbody>{prev.sample.map((r: string[], i: number) => <tr key={i}>{r.map((c, j) => <td key={j}>{c}</td>)}</tr>)}</tbody></table>
          <button onClick={doImport}>Import {prev.rowCount} rows</button></>}
      </div>
      {msg && <div className="card">{msg}</div>}
      {sum && <div className="card">{["MATCHED", "SUGGESTED", "UNMATCHED", "IGNORED"].map((s) => <button key={s} className="secondary" style={{ marginRight: 6 }} onClick={() => setFilter(filter === s ? "" : s)}><span className={`pill ${TAG[s]}`}>{s.toLowerCase()}</span> {sum.byStatus[s]?.count ?? 0}</button>)}
        <button className="secondary" onClick={() => act(() => api("/api/bank/rematch", { method: "POST" }))}>Re-run matching</button></div>}
      <table data-testid="bank-txns">
        <thead><tr><th>Date</th><th>Narration</th><th className="num">Out</th><th className="num">In</th><th>Status</th><th>Invoice</th><th /></tr></thead>
        <tbody>{shown.map((t) => (
          <tr key={t.id}><td>{t.txn_date}</td><td>{t.description}<div className="muted">{t.ref}</div></td><td className="num">{t.debit_paise ? inr(t.debit_paise) : ""}</td><td className="num">{t.credit_paise ? inr(t.credit_paise) : ""}</td>
            <td><span className={`pill ${TAG[t.status]}`}>{t.status.toLowerCase()}</span>{t.match_source === "AUTO" && <span className="muted"> auto</span>}</td>
            <td>{t.invoice_no && <><a href={`/review/${t.matched_invoice_id}`}>{t.invoice_no}</a> <span className="muted">{t.invoice_party}</span></>}{t.customer_tds_paise > 0 && <div className="muted">+ {inr(t.customer_tds_paise)} TDS withheld by customer</div>}<div className="muted">{t.match_note || t.ignore_reason}</div></td>
            <td>
              {t.status === "SUGGESTED" && <button onClick={() => act(() => api(`/api/bank/txns/${t.id}/match`, jsonBody("POST", { invoiceId: t.matched_invoice_id })))}>Accept</button>}{" "}
              {t.status === "MATCHED" && t.credit_paise > 0 && <><button className="secondary" onClick={() => { const v = prompt("TDS the customer withheld from this receipt (₹, 0 if none)?", String((t.customer_tds_paise || 0) / 100)); const p = v === null ? null : toPaise(v); if (p !== null) act(() => api(`/api/bank/txns/${t.id}/customer-tds`, jsonBody("POST", { tdsPaise: p }))); }}>Customer TDS</button>{" "}</>}
              {(t.status === "MATCHED" || t.status === "SUGGESTED" || t.status === "IGNORED") && <button className="secondary" onClick={() => act(() => api(`/api/bank/txns/${t.id}/unmatch`, { method: "POST" }))}>Undo</button>}
              {(t.status === "UNMATCHED" || t.status === "SUGGESTED") && <> <button className="secondary" onClick={async () => setCands({ id: t.id, list: await api(`/api/bank/txns/${t.id}/candidates`) })}>Find invoice</button>{" "}
                <button className="secondary" onClick={() => { const r = prompt("Why ignore? (bank charges, salary, own-account transfer...)"); if (r) act(() => api(`/api/bank/txns/${t.id}/ignore`, jsonBody("POST", { reason: r }))); }}>Ignore</button></>}
            </td></tr>))}</tbody>
      </table>
      <Empty show={txns.length === 0}>No bank lines yet. Choose a CSV/XLSX statement above to import; matching to invoices runs automatically.</Empty>
      {cands && <div className="card"><b>Open invoices for line #{cands.id}</b>
        <table><thead><tr><th>Invoice</th><th>Party</th><th>Date</th><th className="num">Outstanding</th><th>Fit</th><th /></tr></thead>
          <tbody>{cands.list.map((c) => <tr key={c.invoiceId}><td>{c.invoiceNo}</td><td>{c.party}</td><td>{c.date}</td><td className="num">{inr(c.outstandingPaise)}</td><td className="muted">{c.score}% {c.note}</td>
            <td><button onClick={() => act(() => api(`/api/bank/txns/${cands.id}/match`, jsonBody("POST", { invoiceId: c.invoiceId })))}>Match</button></td></tr>)}</tbody></table>
        <button className="secondary" onClick={() => setCands(null)}>Close</button></div>}
      {sum && <><h2>Still unpaid / outstanding</h2>
        <table><thead><tr><th>Type</th><th>Invoice</th><th>Party</th><th>Date</th><th className="num">Outstanding</th><th className="num">Age (days)</th></tr></thead>
          <tbody>{sum.outstanding.map((o: any) => <tr key={o.invoiceId}><td>{o.direction === "SALE" ? "Receivable" : "Payable"}</td><td>{o.invoiceNo}</td><td>{o.party}</td><td>{o.date}</td><td className="num">{inr(o.outstandingPaise)}</td><td className="num">{o.ageDays}</td></tr>)}</tbody></table></>}
    </>
  );
}
