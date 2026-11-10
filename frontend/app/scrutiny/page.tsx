"use client";
import { useEffect, useState } from "react";
import { api, jsonBody } from "@/lib/api";
import { inr } from "@/lib/format";

const LINES: [string, string][] = [["3.1a", "3.1(a) Outward taxable"], ["3.1b", "3.1(b) Zero-rated"], ["3.1c", "3.1(c) Nil/exempt"], ["3.1d", "3.1(d) Reverse charge"], ["3.1e", "3.1(e) Non-GST"],
  ["4A1", "4A(1) Import goods ITC"], ["4A2", "4A(2) Import services ITC"], ["4A3", "4A(3) RCM ITC"], ["4A5", "4A(5) Other ITC"], ["4B1", "4B(1) ITC reversed"]];
const HEADS = ["taxable", "igst", "cgst", "sgst", "cess"];

export default function Scrutiny() {
  const [list, setList] = useState<any[]>([]);
  const [rep, setRep] = useState<any>(null);
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);
  const [period, setPeriod] = useState("");
  const [typed, setTyped] = useState<Record<string, Record<string, string>>>({});
  const [expl, setExpl] = useState("");
  const load = () => api("/api/scrutiny").then(setList);
  useEffect(() => { load(); }, []);
  const open = async (id: number) => { setRep(await api(`/api/scrutiny/${id}`)); setExpl(""); };
  const upload = async (f: File | undefined) => {
    if (!f) return; setBusy(true); setMsg("");
    try { const fd = new FormData(); fd.append("file", f); const r = await api("/api/scrutiny/upload", { method: "POST", body: fd }); setRep(r); setExpl(""); load(); }
    catch (e: any) { setMsg(e.message); } finally { setBusy(false); }
  };
  const submitTyped = async () => {
    try { setRep(await api("/api/scrutiny/manual", jsonBody("POST", { period, figures: typed }))); load(); setMsg(""); } catch (e: any) { setMsg(e.message); }
  };
  return (
    <>
      <h1>CA scrutiny — filed GSTR-3B vs your books</h1>
      <p className="muted">Give Account0 the 3B your CA filed. It recomputes the month from your confirmed invoices (plain code, no AI maths) and lists every difference with questions to ask your CA.</p>
      <div className="grid2">
        <div className="card">
          <b>Upload filed 3B</b>
          <p className="muted">Portal JSON (best), or the filed PDF (Claude transcribes it once; you must confirm the figures).</p>
          <input type="file" accept=".json,.pdf" disabled={busy} onChange={(e) => upload(e.target.files?.[0])} /> {busy && <span className="muted">reading…</span>}
        </div>
        <div className="card">
          <b>Or type the figures</b> (rupees)
          <div className="row"><div><label>Period</label><input type="month" value={period} onChange={(e) => setPeriod(e.target.value)} /></div></div>
          <table><thead><tr><th>Line</th>{HEADS.map((h) => <th key={h}>{h}</th>)}</tr></thead><tbody>
            {LINES.map(([k, l]) => <tr key={k}><td>{l}</td>{HEADS.map((h) => <td key={h}><input size={9} disabled={k.startsWith("4") && h === "taxable"} value={typed[k]?.[h] ?? ""} onChange={(e) => setTyped({ ...typed, [k]: { ...typed[k], [h]: e.target.value } })} /></td>)}</tr>)}
          </tbody></table>
          <button disabled={!period} onClick={submitTyped}>Compare</button>
        </div>
      </div>
      {msg && <div className="card err">{msg}</div>}
      {list.length > 0 && <div className="card"><b>Previous uploads:</b> {list.map((r) => <button key={r.id} className="secondary" style={{ marginLeft: 6 }} onClick={() => open(r.id)}>{r.period} · {r.source}{r.confirmed ? "" : " (unconfirmed)"}</button>)}</div>}
      {rep && (
        <>
          <h2>{rep.filedReturn.period} — {rep.mismatches} difference(s)</h2>
          {rep.unconfirmedNotice && <div className="stub">{rep.unconfirmedNotice} <button onClick={async () => setRep(await api(`/api/scrutiny/${rep.filedReturn.id}/confirm`, { method: "POST" }))}>I have checked — confirm</button>
            {rep.filedReturn.document_id && <> <a href={`/api/documents/${rep.filedReturn.document_id}/file`} target="_blank">open PDF</a></>}</div>}
          {rep.warnings?.length > 0 && <div className="card">{rep.warnings.map((w: string, i: number) => <div key={i} className="muted">⚠ {w}</div>)}</div>}
          <table data-testid="diff">
            <thead><tr><th>Line</th><th>Head</th><th className="num">CA filed</th><th className="num">Your books</th><th className="num">Difference</th><th /></tr></thead>
            <tbody>{rep.rows.map((r: any, i: number) => (
              <tr key={i} className={r.status === "DIFF" ? "flag" : ""}><td>{r.label}</td><td>{r.head}</td><td className="num">{inr(r.filed)}</td><td className="num">{inr(r.books)}</td><td className="num">{inr(r.diff)}</td>
                <td>{r.status === "DIFF" ? <span className="pill bad">differs</span> : <span className="pill ok">matches</span>}</td></tr>))}</tbody>
          </table>
          <h2>Questions to ask your CA</h2>
          {rep.questions.length === 0 ? <p className="muted">No differences — nothing to ask.</p> :
            rep.questions.map((q: any, i: number) => (
              <div className="card" key={i}><b>{q.about}</b><div className="muted">{q.facts}</div><p>❓ {q.question}</p><div className="why">Why it matters: {q.why}</div></div>))}
          <h2>Plain-language explanation</h2>
          <button className="secondary" onClick={async () => { try { setExpl((await api(`/api/scrutiny/${rep.filedReturn.id}/explain`, { method: "POST" })).explanation); } catch (e: any) { setMsg(e.message); } }}>Explain with Claude (≈$0.02)</button>
          {(expl || rep.explanation) && <div className="card" style={{ whiteSpace: "pre-wrap" }}>{expl || rep.explanation}</div>}
        </>
      )}
    </>
  );
}
