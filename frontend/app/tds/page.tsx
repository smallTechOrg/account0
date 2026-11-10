"use client";
import { useEffect, useState } from "react";
import { api, jsonBody } from "@/lib/api";
import { inr, fyOptions } from "@/lib/format";
import { Empty } from "@/lib/Empty";
import { Why } from "@/lib/Why";

function currentFy() { const d = new Date(); const y = d.getMonth() >= 3 ? d.getFullYear() : d.getFullYear() - 1; return `${y}-${String((y + 1) % 100).padStart(2, "0")}`; }

export default function Tds() {
  const [fy, setFy] = useState(currentFy());
  const [sections, setSections] = useState<any[]>([]);
  const [pending, setPending] = useState<any[]>([]);
  const [reg, setReg] = useState<any>(null);
  const [choice, setChoice] = useState<Record<number, { section?: string; pan?: string; type?: string; save?: boolean }>>({});
  const [msg, setMsg] = useState("");
  const load = () => { api("/api/tds/pending").then(setPending); api(`/api/tds/register?fy=${fy}`).then(setReg); };
  useEffect(() => { api("/api/tds/sections").then(setSections); }, []);
  useEffect(() => { load(); }, [fy]);
  const set = (id: number, k: string, v: any) => setChoice({ ...choice, [id]: { ...choice[id], [k]: v } });
  return (
    <>
      <h1>TDS register (26Q data)</h1>
      <p className="muted">You decide the section for each purchase — Account0 never guesses. It then applies the rate and thresholds with plain code and keeps the year-to-date position per vendor. TDS base = invoice value excluding GST.</p>
      <div className="assumed">Rates/thresholds are as understood for FY 2025-26 onwards (Finance Act 2025) under the Income-tax Act 1961 section numbers. Confirm with your CA, especially after the Income-tax Act 2025 renumbering. Payments to non-residents (Sec 195) are never computed. From FY 2026-27 the returns are Form 140 (was 26Q) and Form 138 (was 24Q), and payments carry s.393 payment codes — the codes shown beside sections are from secondary sources: verify with CA. Rent (194I) is tested per month (₹50,000 per month or part of a month); director fees (194J_DIR) have no threshold; without PAN the rate is 20% (5% for 194Q).</div>
      <h2>Needs a TDS decision ({pending.length}) <Why k="TDS" /></h2>
      {msg && <div className="card err">{msg}</div>}
      <table>
        <thead><tr><th>Invoice</th><th>Vendor</th><th className="num">Value (excl. GST)</th><th>Section</th><th>PAN</th><th>Payee</th><th /></tr></thead>
        <tbody>{pending.map((p) => (
          <tr key={p.id}><td>{p.invoice_no}<div className="muted">{p.invoice_date}</div></td><td>{p.counterparty_name}<div className="muted">{p.category}</div></td><td className="num">{inr(p.taxable_paise)}</td>
            {p.nonResident ? <td colSpan={4}><span className="pill warn">non-resident</span> {p.note} <button className="secondary" onClick={async () => { await api("/api/tds/assign", jsonBody("POST", { invoiceId: p.id, section: "NONE" })); load(); }}>Noted — ask CA</button></td> : <>
              <td><select value={choice[p.id]?.section ?? p.suggestedSection ?? ""} onChange={(e) => set(p.id, "section", e.target.value)}>
                <option value="">— choose —</option><option value="NONE">Not applicable</option>{sections.map((s) => <option key={s.code} value={s.code}>{s.code} — {s.rateBp / 100}%{s.newCode ? ` (s.393 code ${s.newCode})` : ""}</option>)}</select>
                {p.suggestedSection && <div className="muted">{p.suggestionSource}</div>}</td>
              <td><input size={11} placeholder="from GSTIN" value={choice[p.id]?.pan ?? ""} onChange={(e) => set(p.id, "pan", e.target.value)} /></td>
              <td><select value={choice[p.id]?.type ?? ""} onChange={(e) => set(p.id, "type", e.target.value)}><option value="">auto from PAN</option><option value="COMPANY_OR_FIRM">Company/firm</option><option value="INDIVIDUAL_HUF">Individual/HUF</option></select></td>
              <td><label style={{ display: "inline" }}><input type="checkbox" checked={!!choice[p.id]?.save} onChange={(e) => set(p.id, "save", e.target.checked)} /> remember</label>{" "}
                <button disabled={!(choice[p.id]?.section ?? p.suggestedSection)} onClick={async () => { try { await api("/api/tds/assign", jsonBody("POST", { invoiceId: p.id, section: choice[p.id]?.section ?? p.suggestedSection, pan: choice[p.id]?.pan, payeeType: choice[p.id]?.type, saveRule: !!choice[p.id]?.save })); setMsg(""); load(); } catch (e: any) { setMsg(e.message); } }}>Save</button></td></>}
          </tr>))}</tbody>
      </table>
      <h2>Register FY <select value={fy} onChange={(e) => setFy(e.target.value)}>{fyOptions().map((o) => <option key={o}>{o}</option>)}</select></h2>
      {reg && <>
        <p><b>Total TDS to deduct: {inr(reg.totalTdsPaise)}</b> {[1, 2, 3, 4].map((q) => <a key={q} className="btn secondary" style={{ marginLeft: 6 }} href={`/api/tds/26q.csv?fy=${fy}&quarter=${q}`}>{fy >= "2026-27" ? "Form 140" : "26Q"} Q{q} CSV</a>)}</p>
        <table data-testid="tds-register">
          <thead><tr><th>Date</th><th>Invoice</th><th>Payee</th><th>Section</th><th className="num">Base</th><th className="num">Rate</th><th className="num">TDS</th><th>Why</th><th>Deposit</th></tr></thead>
          <tbody>{reg.rows.map((r: any) => (
            <tr key={r.id} className={r.status === "DEDUCT" ? "flag" : ""}><td>{r.payment_date}</td><td>{r.invoice_no}</td><td>{r.payee_name}<div className="muted">{r.payee_pan ?? "no PAN"}</div></td><td>{r.section}</td><td className="num">{inr(r.base_paise)}</td><td className="num">{r.rate_bp / 100}%</td><td className="num">{inr(r.tds_paise)}</td><td className="muted">{r.reason}</td>
              <td>{r.tds_paise > 0 && (r.deposited_on ? <span className="pill ok">{r.deposited_on}</span> : <button className="secondary" onClick={async () => { const d = prompt("Deposit date (YYYY-MM-DD)?"); if (!d) return; const c = prompt("Challan number?") || ""; try { await api(`/api/tds/${r.id}/deposit`, jsonBody("POST", { deposited_on: d, challan_no: c })); load(); } catch (e: any) { setMsg(e.message); } }}>Record deposit</button>)}</td></tr>))}</tbody>
        </table>
        <Empty show={reg.rows.length === 0}>No TDS entries for FY {fy}. Pick a section for a purchase above and it appears here.</Empty>
        <h2>Threshold position</h2>
        <table><thead><tr><th>Payee</th><th>Section</th><th className="num">Total this FY</th><th className="num">Threshold</th><th>Used</th></tr></thead>
          <tbody>{reg.thresholds.map((t: any, i: number) => <tr key={i}><td>{t.payee}</td><td>{t.section}</td><td className="num">{inr(t.total)}</td><td className="num">{inr(t.annualThresholdPaise)}</td><td>{t.crossed ? <span className="pill bad">crossed</span> : <span className="pill">{t.percentOfThreshold}%</span>}</td></tr>)}</tbody></table>
      </>}
    </>
  );
}
