"use client";
import { useEffect, useState } from "react";
import { api, jsonBody } from "@/lib/api";
import { Empty } from "@/lib/Empty";

export default function Filings() {
  const [rows, setRows] = useState<any[]>([]);
  const [f, setF] = useState<any>({ return_type: "GSTR3B", period: "", arn: "", filed_on: "", filed_by: "CA" });
  const [msg, setMsg] = useState("");
  const load = () => api("/api/filings").then(setRows);
  useEffect(() => { load(); }, []);
  return (
    <>
      <h1>Filing history</h1>
      <p className="muted">Recording a filing locks the period: invoices dated in it can no longer be edited or confirmed until you unlock it (with a reason, kept in the audit trail).</p>
      <div className="card">
        <div className="row">
          <div><label>Return</label><select value={f.return_type} onChange={(e) => setF({ ...f, return_type: e.target.value })}><option>GSTR3B</option><option>GSTR1</option><option>GSTR9</option><option>OTHER</option></select></div>
          <div><label>Period</label><input type="month" value={f.period} onChange={(e) => setF({ ...f, period: e.target.value })} /></div>
          <div><label>ARN</label><input value={f.arn} onChange={(e) => setF({ ...f, arn: e.target.value })} /></div>
          <div><label>Filed on</label><input type="date" value={f.filed_on} onChange={(e) => setF({ ...f, filed_on: e.target.value })} /></div>
          <div><label>Filed by</label><input value={f.filed_by} onChange={(e) => setF({ ...f, filed_by: e.target.value })} /></div>
          <button onClick={async () => { try { await api("/api/filings", jsonBody("POST", f)); setMsg("Recorded and locked."); load(); } catch (e: any) { setMsg(e.message); } }}>Record filing</button>
        </div>
        {msg && <p>{msg}</p>}
      </div>
      <table>
        <thead><tr><th>Return</th><th>Period</th><th>ARN</th><th>Filed on</th><th>By</th><th>Lock</th><th /></tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td>{r.return_type}</td><td>{r.period}</td><td>{r.arn}</td><td>{r.filed_on}</td><td>{r.filed_by}</td>
              <td>{r.locked ? <span className="pill bad">locked</span> : <span className="pill warn">unlocked</span>}</td>
              <td>{r.locked
                ? <button className="secondary" onClick={async () => { const reason = prompt("Reason for unlocking (recorded in audit trail)?"); if (!reason) return; await api(`/api/filings/${r.id}/unlock`, jsonBody("POST", { reason })); load(); }}>Unlock</button>
                : <button className="secondary" onClick={async () => { await api(`/api/filings/${r.id}/lock`, { method: "POST" }); load(); }}>Lock</button>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <Empty show={rows.length === 0}>No filings recorded yet. After your CA files a return, record its ARN here to lock that period.</Empty>
    </>
  );
}
