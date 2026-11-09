"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { inr } from "@/lib/format";
import { Why } from "@/lib/Why";

const LABEL: Record<string, [string, string]> = {
  MATCHED: ["Matched", "ok"], AMOUNT_MISMATCH: ["Amount differs", "bad"], POSSIBLE_MATCH: ["Possible match", "warn"],
  IN_2B_NOT_IN_BOOKS: ["In 2B, not in books", "warn"], IN_BOOKS_NOT_IN_2B: ["In books, not in 2B", "bad"],
};

export default function Itc() {
  const [period, setPeriod] = useState(new Date().toISOString().slice(0, 7));
  const [rep, setRep] = useState<any>(null);
  const [imports, setImports] = useState<any[]>([]);
  const [msg, setMsg] = useState("");
  const load = (p = period) => { api(`/api/itc/match?period=${p}`).then(setRep).catch((e) => setMsg(e.message)); api("/api/itc/2b/imports").then(setImports); };
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
          <thead><tr><th>Status</th><th>Vendor</th><th>GSTIN</th><th>2B doc</th><th>Books invoice</th><th className="num">Tax in 2B</th><th className="num">Tax in books</th><th>Note</th></tr></thead>
          <tbody>{rep.rows.map((r: any, i: number) => (
            <tr key={i}><td><span className={`pill ${LABEL[r.status]?.[1]}`}>{LABEL[r.status]?.[0]}</span></td><td>{r.party}</td><td>{r.gstin}</td><td>{r.docNo}</td>
              <td>{r.invoiceId ? <a href={`/review/${r.invoiceId}`}>{r.invoiceNo}</a> : ""}</td><td className="num">{inr(r.tax2bPaise)}</td><td className="num">{inr(r.taxBooksPaise)}</td><td className="muted">{r.note}</td></tr>))}</tbody>
        </table></>)}
      {imports.length > 0 && <p className="muted">Imported: {imports.map((i) => `${i.period} (${i.documents} docs)`).join(", ")}</p>}
    </>
  );
}
