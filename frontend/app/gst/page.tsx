"use client";
import { Why } from "@/lib/Why";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { inr, inrPlain } from "@/lib/format";

function lastMonth(): string {
  const d = new Date();
  d.setDate(1);
  d.setMonth(d.getMonth() - 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

function Explain({ fig, onClose }: { fig: any; onClose: () => void }) {
  return (
    <div className="card" style={{ position: "sticky", top: 10 }} data-testid="explain">
      <div className="row" style={{ justifyContent: "space-between" }}><b>{fig.label}</b><button className="secondary" onClick={onClose}>×</button></div>
      <div className="why"><b>Rule applied:</b> {fig.rule}<br /><span className="muted">Law: {fig.ref}</span></div>
      {fig.caveat && <div className="stub">{fig.caveat}</div>}
      <p>Taxable {inr(fig.taxable_paise)} · IGST {inr(fig.igst_paise)} · CGST {inr(fig.cgst_paise)} · SGST {inr(fig.sgst_paise)} · Cess {inr(fig.cess_paise)}</p>
      <b>Source invoices ({fig.sources.length})</b>
      <table>
        <thead><tr><th>Invoice</th><th>Date</th><th>Party</th><th className="num">Taxable</th><th className="num">Tax</th></tr></thead>
        <tbody>
          {fig.sources.map((s: any, i: number) => (
            <tr key={i}><td><a href={`/review/${s.id}`}>{s.invoice_no}</a>{s.doc_type !== "INVOICE" && <span className="pill">{s.doc_type}</span>}</td><td>{s.invoice_date}</td><td>{s.party}</td>
              <td className="num">{inr(s.taxable_paise)}</td><td className="num">{inr(s.tax_paise)}</td></tr>
          ))}
          {fig.sources.length === 0 && <tr><td colSpan={5} className="muted">No invoices contribute to this figure.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

function FigTable({ figs, onPick }: { figs: any[]; onPick: (f: any) => void }) {
  const cell = (f: any, k: string) => <td className="num"><a className="fig" onClick={() => onPick(f)}>{inr(f[k])}</a></td>;
  return (
    <table>
      <thead><tr><th>Table</th><th className="num">Taxable value</th><th className="num">IGST</th><th className="num">CGST</th><th className="num">SGST</th><th className="num">Cess</th></tr></thead>
      <tbody>{figs.map((f) => <tr key={f.key}><td><a className="fig" onClick={() => onPick(f)}>{f.label}</a><Why k={f.key} />{f.status && <div><span className="pill warn" title={f.caveat}>{f.status.toLowerCase()}</span> <span className="muted">{f.caveat}</span></div>}</td>{cell(f, "taxable_paise")}{cell(f, "igst_paise")}{cell(f, "cgst_paise")}{cell(f, "sgst_paise")}{cell(f, "cess_paise")}</tr>)}</tbody>
    </table>
  );
}

export default function Gst() {
  const [period, setPeriod] = useState(lastMonth());
  const [tab, setTab] = useState<"3b" | "1">("3b");
  const [g3, setG3] = useState<any>(null);
  const [g1, setG1] = useState<any>(null);
  const [pick, setPick] = useState<any>(null);
  const [err, setErr] = useState("");
  useEffect(() => {
    if (!/^\d{4}-\d{2}$/.test(period)) return;
    setErr(""); setPick(null);
    api(`/api/gst/3b?period=${period}`).then(setG3).catch((e) => setErr(e.message));
    api(`/api/gst/1?period=${period}`).then(setG1).catch((e) => setErr(e.message));
  }, [period]);
  const g = tab === "3b" ? g3 : g1;
  return (
    <>
      <h1>GST returns — recomputed</h1>
      <div className="row" style={{ marginBottom: 10 }}>
        <div><label>Tax period</label><input type="month" value={period} onChange={(e) => setPeriod(e.target.value)} /></div>
        <button className={tab === "3b" ? "" : "secondary"} onClick={() => setTab("3b")}>GSTR-3B</button>
        <button className={tab === "1" ? "" : "secondary"} onClick={() => setTab("1")}>GSTR-1</button>
        <a className="btn secondary" href={`/api/gst/1/portal.json?period=${period}`}>GSTR-1 JSON</a>
        <a className="btn secondary" href={`/api/gst/workings.xlsx?period=${period}`}>Excel workings</a>
        <a className="btn" href={`/api/gst/ca-pack.zip?period=${period}`}>Download CA pack (zip)</a>
      </div>
      <p className="muted">Computed by plain code from your <b>confirmed</b> invoices — no AI arithmetic. Click any number to see the rule and the invoices behind it.</p>
      {err && <div className="card err">{err}</div>}
      {g?.warnings?.map((w: string, i: number) => <div key={i} className="stub">{w}</div>)}
      <div className="grid2" style={{ gridTemplateColumns: pick ? "3fr 2fr" : "1fr" }}>
        <div>
          {tab === "3b" && g3 && (
            <>
              {g3.errors?.map((e: string, i: number) => <div key={i} className="card err">{e}</div>)}
              <h2>3.1 Outward and reverse-charge inward supplies</h2><FigTable figs={g3.table31} onPick={setPick} />
              {g3.table32.length > 0 && <><h2>3.2 Inter-state supplies to unregistered persons, composition dealers and UIN holders</h2><FigTable figs={g3.table32} onPick={setPick} /></>}
              <h2>4 Eligible ITC</h2><FigTable figs={g3.table4} onPick={setPick} />
              <h2>5 Exempt / nil / non-GST inward</h2><FigTable figs={g3.table5} onPick={setPick} />
              <h2>6.1 Payment of tax</h2>
              <div className="why">{g3.payment61.rule} <span className="muted">({g3.payment61.ref})</span></div>
              <table>
                <thead><tr><th>Head</th><th className="num">Liability</th><th className="num">via IGST ITC</th><th className="num">via CGST ITC</th><th className="num">via SGST ITC</th><th className="num">Cash</th><th className="num">RCM (cash)</th></tr></thead>
                <tbody>{g3.payment61.heads.map((h: any) => (
                  <tr key={h.head}><td>{h.head}</td><td className="num">{inr(h.liabilityPaise)}</td><td className="num">{inr(h.paidByIgstItcPaise)}</td><td className="num">{inr(h.paidByCgstItcPaise)}</td><td className="num">{inr(h.paidBySgstItcPaise)}</td><td className="num">{inr(h.cashPaise)}</td><td className="num">{inr(h.rcmCashPaise)}</td></tr>
                ))}</tbody>
              </table>
              <p><b>Total cash to pay: {inr(g3.payment61.cashTotalPaise)}</b> · ITC carried forward: IGST {inr(g3.payment61.itcCarriedForwardPaise.igst)}, CGST {inr(g3.payment61.itcCarriedForwardPaise.cgst)}, SGST {inr(g3.payment61.itcCarriedForwardPaise.sgst)}</p>
              {g3.notes.map((n: string, i: number) => <p key={i} className="muted">{n}</p>)}
            </>
          )}
          {tab === "1" && g1 && (
            <>
              <h2>GSTR-1 tables</h2><FigTable figs={g1.tables} onPick={setPick} />
              <h2>Table 12 — HSN summary (B2B)</h2><Hsn rows={g1.hsnB2b} />
              <h2>Table 12 — HSN summary (B2C)</h2><Hsn rows={g1.hsnB2c} />
              <h2>Table 13 — Documents issued</h2>
              <p>{g1.documentsIssued.count} documents issued ({g1.documentsIssued.cancelled} cancelled, {g1.documentsIssued.netIssued} net). <span className="muted">{g1.documentsIssued.rule}</span></p>
              <table>
                <thead><tr><th>Nature</th><th>Series</th><th>From</th><th>To</th><th className="num">Total</th><th className="num">Cancelled</th><th className="num">Net issued</th></tr></thead>
                <tbody>{(g1.documentsIssued.series ?? []).map((r: any, i: number) => <tr key={i} className={r.gap ? "flag" : ""}><td>{r.doc_num} {r.nature}</td><td>{r.series}</td><td>{r.from}</td><td>{r.to}</td><td className="num">{r.totnum}</td><td className="num">{r.cancel}</td><td className="num">{r.net_issue}</td></tr>)}
                  {(g1.documentsIssued.series ?? []).length === 0 && <tr><td colSpan={7} className="muted">None.</td></tr>}</tbody>
              </table>
              {g1.blockers?.length > 0 && <div className="card err"><b>The GSTR-1 JSON cannot be exported yet:</b>{Array.from(new Set(g1.blockers as string[])).map((b: string, i: number) => <div key={i}>{b}</div>)}</div>}
              {g1.notes.map((n: string, i: number) => <div key={i} className="stub">{n}</div>)}
              <div className="assumed">The JSON follows the GST offline tool’s shape on a best-effort basis — import it into the offline tool to validate before uploading to the portal.</div>
            </>
          )}
        </div>
        {pick && <Explain fig={pick} onClose={() => setPick(null)} />}
      </div>
    </>
  );
}

function Hsn({ rows }: { rows: any[] }) {
  return (
    <table>
      <thead><tr><th>HSN/SAC</th><th>Description</th><th className="num">Rate</th><th className="num">Taxable</th><th className="num">IGST</th><th className="num">CGST</th><th className="num">SGST</th></tr></thead>
      <tbody>
        {rows.map((r) => <tr key={r.num}><td>{r.hsn_sc}</td><td>{r.desc}</td><td className="num">{r.rt}%</td><td className="num">{inrPlain(r.txval)}</td><td className="num">{inrPlain(r.iamt)}</td><td className="num">{inrPlain(r.camt)}</td><td className="num">{inrPlain(r.samt)}</td></tr>)}
        {rows.length === 0 && <tr><td colSpan={7} className="muted">None.</td></tr>}
      </tbody>
    </table>
  );
}
