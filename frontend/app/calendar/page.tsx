"use client";
import { useEffect, useState } from "react";
import { api, jsonBody } from "@/lib/api";

function currentFy() { const d = new Date(); const y = d.getMonth() >= 3 ? d.getFullYear() : d.getFullYear() - 1; return `${y}-${String((y + 1) % 100).padStart(2, "0")}`; }
const TAG: Record<string, string> = { DONE: "ok", NA: "", OVERDUE: "bad", DUE_SOON: "warn", UPCOMING: "" };

export default function Calendar() {
  const [fy, setFy] = useState(currentFy());
  const [cal, setCal] = useState<any>(null);
  const [cat, setCat] = useState("");
  const [hide, setHide] = useState(true);
  const [msg, setMsg] = useState("");
  const [agm, setAgm] = useState("");
  const load = () => api(`/api/compliance/calendar?fy=${fy}`).then((c) => { setCal(c); setAgm(c.agmDate ?? ""); }).catch((e) => setMsg(e.message));
  useEffect(() => { load(); }, [fy]);
  const mark = async (it: any, status: string) => {
    let body: any = { code: it.code, period: it.period, status };
    if (status === "DONE") { body.done_on = prompt("Filed/paid on (YYYY-MM-DD)?", new Date().toISOString().slice(0, 10)) || undefined; body.ref = prompt("ARN / acknowledgement / challan no (optional)?") || undefined; }
    if (status === "NA") { body.note = prompt("Why does this not apply to you?") || ""; if (!body.note) return; }
    try { await api("/api/compliance/mark", jsonBody("POST", body)); load(); } catch (e: any) { setMsg(e.message); }
  };
  const cats = cal ? Array.from(new Set(cal.items.map((i: any) => i.category))) as string[] : [];
  const shown = cal ? cal.items.filter((i: any) => (!cat || i.category === cat) && !(hide && (i.state === "DONE" || i.state === "NA"))) : [];
  return (
    <>
      <h1>Compliance calendar</h1>
      <div className="stub">{cal?.assumptions}</div>
      <div className="card row">
        <div><label>Financial year</label><input size={8} value={fy} onChange={(e) => setFy(e.target.value)} /></div>
        <div><label>Category</label><select value={cat} onChange={(e) => setCat(e.target.value)}><option value="">All</option>{cats.map((c) => <option key={c}>{c}</option>)}</select></div>
        <div><label>AGM date (moves the ROC dates)</label><input type="date" value={agm} onChange={(e) => setAgm(e.target.value)} /> <button className="secondary" onClick={async () => { await api("/api/settings/company.agm_date", jsonBody("PUT", { value: agm })); load(); }}>Save</button></div>
        <label style={{ display: "inline" }}><input type="checkbox" checked={hide} onChange={(e) => setHide(e.target.checked)} /> hide done / not applicable</label>
        <button className="secondary" onClick={async () => { const b = prompt("Mark everything due BEFORE which date as already done? (YYYY-MM-DD)"); if (!b) return; const r = await api("/api/compliance/catch-up", jsonBody("POST", { fy, before: b })); setMsg(`${r.marked} items marked done (audited).`); load(); }}>I was already compliant up to…</button>
      </div>
      {msg && <div className="card">{msg}</div>}
      {cal && <p>{Object.entries(cal.counts).map(([k, v]: any) => <span key={k} className={`pill ${TAG[k]}`} style={{ marginRight: 8 }}>{k.replace("_", " ").toLowerCase()}: {v}</span>)}</p>}
      <table data-testid="calendar">
        <thead><tr><th>Due</th><th>What</th><th>Form</th><th>Status</th><th /></tr></thead>
        <tbody>{shown.map((i: any) => (
          <tr key={i.code + i.period} className={i.state === "OVERDUE" && !i.optional ? "flag" : ""}>
            <td style={{ whiteSpace: "nowrap" }}>{i.due}<div className="muted">{i.daysLeft >= 0 ? `in ${i.daysLeft} d` : `${-i.daysLeft} d ago`}</div></td>
            <td>{i.title}{i.optional && <span className="pill"> if applicable</span>}<div className="muted">{i.ref}</div><div className="why">{i.why}</div></td>
            <td>{i.form}<div className="muted">{i.category}</div></td>
            <td><span className={`pill ${TAG[i.state]}`}>{i.state.replace("_", " ").toLowerCase()}</span>{i.source && <div className="muted">{i.source.replace("_", " ").toLowerCase()} {i.filingRef} {i.doneOn} {i.note}</div>}</td>
            <td>{i.state !== "DONE" && i.state !== "NA" ? <><button onClick={() => mark(i, "DONE")}>Done</button> <button className="secondary" onClick={() => mark(i, "NA")}>N/A</button></> : i.source === "USER" ? <button className="secondary" onClick={() => mark(i, "OPEN")}>Undo</button> : null}</td></tr>))}</tbody>
      </table>
    </>
  );
}
