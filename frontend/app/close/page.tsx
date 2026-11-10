"use client";
import { useEffect, useState } from "react";
import { api, jsonBody } from "@/lib/api";

const TAG: Record<string, string> = { DONE: "ok", NA: "", TODO: "warn" };
export default function Close() {
  const [period, setPeriod] = useState(() => { const d = new Date(); d.setMonth(d.getMonth() - 1); return d.toISOString().slice(0, 7); });
  const [data, setData] = useState<any>(null);
  const [err, setErr] = useState("");
  const load = () => api(`/api/close/${period}`).then((d) => { setData(d); setErr(""); }).catch((e) => setErr(e.message));
  useEffect(() => { load(); }, [period]);
  const tick = async (key: string, state: string) => { try { setData(await api(`/api/close/${period}/tick`, jsonBody("POST", { key, state }))); } catch (e: any) { setErr(e.message); } };
  return (
    <>
      <h1>Month-close checklist</h1>
      <p className="muted">Work top to bottom. Items marked <i>auto</i> tick themselves from your data; the rest are your call. When everything is green the month is ready for you to approve what your CA files.</p>
      <div className="card row"><div><label>Month</label><input type="month" value={period} onChange={(e) => setPeriod(e.target.value)} /></div>
        {data && <div><b>{data.progress.done} of {data.progress.total} done</b> {data.readyToClose ? <span className="pill ok">ready to close</span> : <span className="pill warn">{data.open} to do</span>}
          <div className="progress" style={{ width: 240, marginTop: 6 }}><div style={{ width: `${(100 * data.progress.done) / data.progress.total}%` }} /></div></div>}</div>
      {err && <div className="card err">{err}</div>}
      {data?.items.map((i: any) => (
        <div className="card" key={i.key}>
          <div className="row"><span className={`pill ${TAG[i.state]}`}>{i.state === "NA" ? "n/a" : i.state.toLowerCase()}</span> <b>{i.step}. {i.title}</b> {i.auto && <span className="muted">auto</span>}
            <span style={{ flex: 1 }} /><a className="btn secondary" href={i.link}>Open</a>
            {!i.auto && (i.state === "TODO" ? <><button onClick={() => tick(i.key, "DONE")}>Mark done</button><button className="secondary" onClick={() => tick(i.key, "NA")}>Not applicable</button></> : <button className="secondary" onClick={() => tick(i.key, "TODO")}>Undo</button>)}
            {i.key === "PAYROLL" && i.state === "TODO" && <button className="secondary" onClick={() => tick(i.key, "NA")}>No employees</button>}
          </div>
          <div>{i.detail}</div><div className="why">Why: {i.why}</div>
        </div>))}
    </>
  );
}
