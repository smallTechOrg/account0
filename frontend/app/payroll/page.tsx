"use client";
import { useEffect, useState } from "react";
import { api, jsonBody } from "@/lib/api";
import { inr, fyOptions } from "@/lib/format";
import { Empty } from "@/lib/Empty";

function currentFy() { const d = new Date(); const y = d.getMonth() >= 3 ? d.getFullYear() : d.getFullYear() - 1; return `${y}-${String((y + 1) % 100).padStart(2, "0")}`; }

export default function Payroll() {
  const [fy, setFy] = useState(currentFy());
  const [data, setData] = useState<any>(null);
  const [msg, setMsg] = useState("");
  const load = () => api(`/api/payroll?fy=${fy}`).then(setData);
  useEffect(() => { load(); }, [fy]);
  const upload = async (f?: File) => {
    if (!f) return;
    try { const fd = new FormData(); fd.append("file", f); const r = await api("/api/payroll/import", { method: "POST", body: fd }); setMsg(`Imported ${r.rows} rows for ${r.employees} employees.`); load(); } catch (e: any) { setMsg(e.message); }
  };
  return (
    <>
      <h1>Payroll &amp; salary TDS (Sec 192 / 24Q data)</h1>
      <p className="muted">Upload a CSV with columns <code>employee_id,name,pan,month,gross[,regime]</code> (month = YYYY-MM, gross = taxable monthly salary in ₹). Account0 projects each employee's year, applies the new-regime slabs and spreads the remaining tax over the remaining months.</p>
      <div className="assumed">New regime only (FY 2025-26 slabs assumed for later years). Old-regime employees and incomes above ₹50 lakh are flagged for your CA — never guessed. Other income, perquisites and deductions are not modelled.</div>
      <div className="card row"><div><label>Payroll CSV</label><input type="file" accept=".csv" onChange={(e) => upload(e.target.files?.[0])} /></div>
        <div><label>FY</label><select value={fy} onChange={(e) => setFy(e.target.value)}>{fyOptions().map((o) => <option key={o}>{o}</option>)}</select></div>
        {[1, 2, 3, 4].map((q) => <a key={q} className="btn secondary" href={`/api/payroll/24q.csv?fy=${fy}&quarter=${q}`}>24Q Q{q} CSV</a>)}</div>
      {msg && <div className="card">{msg}</div>}
      {data && data.lines.some((l: any) => l.regime === "OLD") && <div className="stub" data-testid="old-regime">Some employees are on the OLD regime: their TDS is shown as ₹0.00 because Account0 does not compute old-regime tax (it needs 80C/HRA declarations). Ask your CA — do not treat ₹0 as correct.</div>}
      {data && <Empty show={data.lines.length === 0}>No payroll for FY {fy} yet. Upload a payroll CSV above.</Empty>}
      {data && <>
        <h2>By month</h2>
        <table><thead><tr><th>Month</th><th className="num">Employees</th><th className="num">Gross</th><th className="num">TDS to deposit</th><th /></tr></thead>
          <tbody>{data.months.map((m: any) => <tr key={m.month}><td>{m.month}</td><td className="num">{m.employees}</td><td className="num">{inr(m.gross_paise)}</td><td className="num">{inr(m.tds_paise)} {m.needs_ca_review ? <span className="pill warn">CA review</span> : null}</td>
            <td>{m.tds_paise > 0 && (m.deposited_on ? <span className="pill ok">deposited {m.deposited_on}</span> : <button className="secondary" onClick={async () => { const d = prompt("Deposit date (YYYY-MM-DD)?"); if (!d) return; await api("/api/payroll/deposit", jsonBody("POST", { month: m.month, deposited_on: d, challan_no: prompt("Challan number?") || "" })); load(); }}>Record deposit</button>)}</td></tr>)}</tbody></table>
        <h2>By employee-month</h2>
        <table data-testid="payroll"><thead><tr><th>Month</th><th>Employee</th><th className="num">Gross</th><th className="num">Projected annual</th><th className="num">Annual tax</th><th className="num">TDS this month</th><th>Note</th></tr></thead>
          <tbody>{data.lines.map((l: any) => <tr key={l.id}><td>{l.month}</td><td>{l.employee_name} <span className="muted">{l.employee_id}</span></td><td className="num">{inr(l.gross_paise)}</td><td className="num">{inr(l.projected_annual_paise)}</td><td className="num">{inr(l.annual_tax_paise)}</td><td className="num">{l.regime === "OLD" ? <span className="pill bad" title="Not computed">OLD regime — not computed</span> : inr(l.tds_paise)}</td><td className="muted">{l.note}</td></tr>)}</tbody></table>
      </>}
    </>
  );
}
