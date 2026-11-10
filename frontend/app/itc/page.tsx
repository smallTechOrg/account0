"use client";
import { useEffect, useState } from "react";
import { api, jsonBody } from "@/lib/api";
import { inr } from "@/lib/format";
import { Why } from "@/lib/Why";
import { ClaimTable } from "@/lib/ClaimTable";

const LABEL: Record<string, [string, string]> = {
  MATCHED: ["Matched", "ok"], AMOUNT_MISMATCH: ["Amount differs", "bad"], POSSIBLE_MATCH: ["Possible match", "warn"],
  IN_2B_NOT_IN_BOOKS: ["In 2B, not in books", "warn"], IN_BOOKS_NOT_IN_2B: ["In books, not in 2B", "bad"],
};

export default function Itc() {
  const [period, setPeriod] = useState(new Date().toISOString().slice(0, 7));
  const [rep, setRep] = useState<any>(null);
  const [imports, setImports] = useState<any[]>([]);
  const [msg, setMsg] = useState("");
  const [claims, setClaims] = useState<any>(null);
  const load = (p = period) => {
    api(`/api/itc/match?period=${p}`).then(setRep).catch((e) => setMsg(e.message)); api("/api/itc/2b/imports").then(setImports);
    api(`/api/gst/3b?period=${p}`).then((g) => setClaims(g.itcClaims)).catch(() => setClaims(null));
  };
  const decide = async (docId: number, decision: string) => {
    const reason = decision === "CLEAR" ? "decision cleared" : window.prompt(decision === "ACCEPTED" ? "Why accept this 2B line? (recorded in the audit trail)" : "Why reject this 2B line? (recorded in the audit trail)") ?? "";
    if (decision !== "CLEAR" && !reason.trim()) return;
    try { await api(`/api/itc/2b/docs/${docId}/decision`, jsonBody("POST", { decision, reason })); setMsg(`Line ${decision.toLowerCase()}.`); load(); } catch (e: any) { setMsg(e.message); }
  };
  useEffect(() => { load(); }, [period]);
  const upload = async (f?: File) => {
    if (!f) return; setMsg("");
    try { const fd = new FormData(); fd.append("file", f); const r = await api("/api/itc/2b/upload", { method: "POST", body: fd }); setPeriod(r.period); load(r.period); setMsg(`Imported ${r.documents} documents for ${r.period}.`); }
    catch (e: any) { setMsg(e.message); }
  };
  return (
    <>
      <h1>GSTR-2B and ITC matching <Why k="2B" /></h1>
      <p className="muted">ITC can be claimed only for invoices that appear in your GSTR-2B (Sec 16(2)(aa) CGST Act). Download the 2B JSON from the portal and import it; Account0 matches it to your confirmed purchase invoices by vendor GSTIN + invoice number + tax amount (within ₹1).</p>
      <div className="card row">
        <div><label>Period</label><input type="month" value={period} onChange={(e) => setPeriod(e.target.value)} /></div>
        <div><label>Import GSTR-2B JSON</label><input type="file" accept=".json" onChange={(e) => upload(e.target.files?.[0])} /></div>
      </div>
      {msg && <div className="card">{msg}</div>}
      {rep && !rep.imported && <div className="card">No GSTR-2B imported for {period} yet.</div>}
      {rep?.imported && (<>
        <div className="card">{Object.entries(rep.summary.counts).map(([k, v]: any) => <span key={k} className={`pill ${LABEL[k]?.[1]}`} style={{ marginRight: 8 }}>{LABEL[k]?.[0] ?? k}: {v}</span>)}
          <b style={{ marginLeft: 8 }}>ITC at risk: {inr(rep.summary.itcAtRiskPaise)}</b></div>
        <table data-testid="itc">
          <thead><tr><th>Status</th><th>Vendor</th><th>GSTIN</th><th>2B doc</th><th>Books invoice</th><th className="num">Tax in 2B</th><th className="num">Tax in books</th><th>Note</th><th>Your decision</th></tr></thead>
          <tbody>{rep.rows.map((r: any, i: number) => (
            <tr key={i}><td><span className={`pill ${LABEL[r.status]?.[1]}`}>{LABEL[r.status]?.[0]}</span></td><td>{r.party}</td><td>{r.gstin}</td><td>{r.docNo}</td>
              <td>{r.invoiceId ? <a href={`/review/${r.invoiceId}`}>{r.invoiceNo}</a> : ""}</td><td className="num">{inr(r.tax2bPaise)}</td><td className="num">{inr(r.taxBooksPaise)}</td><td className="muted">{r.note}</td>
              <td>{r.decidable && (r.decision
                ? <><span className={`pill ${r.decision === "ACCEPTED" ? "ok" : "bad"}`} data-testid="decision">{r.decision.toLowerCase()}</span> <span className="muted">{r.decisionReason}</span> <button className="secondary" onClick={() => decide(r.docId, "CLEAR")}>undo</button></>
                : <><button onClick={() => decide(r.docId, "ACCEPTED")} title="Claim this line in 4A(5) at the 2B tax by head">Accept</button> <button className="secondary" onClick={() => decide(r.docId, "REJECTED")}>Reject</button></>)}</td></tr>))}</tbody>
        </table></>)}
      {claims && claims.rows.length > 0 && (<>
        <h2>ITC claim reconciliation — {period}</h2>
        <div className="why">{claims.rule} <span className="muted">({claims.ref})</span></div>
        <ClaimTable rows={claims.rows} />
      </>)}
      {imports.length > 0 && <p className="muted">Imported: {imports.map((i) => `${i.period} (${i.documents} docs)`).join(", ")}</p>}
    </>
  );
}

