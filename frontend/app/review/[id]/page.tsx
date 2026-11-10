"use client";
import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api, jsonBody } from "@/lib/api";
import { inr, rs, toPaise } from "@/lib/format";

const MONEY = ["taxable_paise", "cgst_paise", "sgst_paise", "igst_paise", "cess_paise", "total_paise"];
const HEADER: [string, string][] = [
  ["invoice_no", "Invoice no"], ["invoice_date", "Invoice date (YYYY-MM-DD)"], ["counterparty_name", "Counterparty name"],
  ["counterparty_gstin", "Counterparty GSTIN"], ["counterparty_country", "Country"], ["place_of_supply", "Place of supply (state code, 96 = abroad)"],
  ["currency", "Currency"], ["foreign_amount", "Foreign amount"], ["fx_rate", "Exchange rate used"], ["original_invoice_no", "Original invoice (credit/debit note)"],
  ["category", "Category / ledger head"],
];
const LINE_MONEY = ["taxable_paise", "cgst_paise", "sgst_paise", "igst_paise"];

export default function ReviewInvoice() {
  const { id } = useParams<{ id: string }>();
  const [inv, setInv] = useState<any>(null);
  const [form, setForm] = useState<any>(null);
  const [types, setTypes] = useState<any[]>([]);
  const [ack, setAck] = useState(false);
  const [saveRule, setSaveRule] = useState(true);
  const [msg, setMsg] = useState("");

  const hydrate = (x: any) => {
    setInv(x);
    const f: any = { ...x };
    MONEY.forEach((k) => (f[k] = rs(x[k])));
    f.lines = x.lines.map((l: any) => { const m: any = { ...l }; LINE_MONEY.forEach((k) => (m[k] = rs(l[k]))); return m; });
    setForm(f);
  };
  useEffect(() => { api(`/api/invoices/${id}`).then(hydrate); api("/api/supply-types").then(setTypes); }, [id]);
  if (!inv || !form) return <p>Loading…</p>;

  const allFlags = [...inv.flags, ...inv.checks];
  const flagged = (field: string) => allFlags.filter((f: any) => f.field === field && f.severity !== "INFO");
  const cls = (field: string) => (flagged(field).length ? "lowconf" : "");
  const editable = inv.status === "DRAFT" && !inv.locked;

  const payload = () => {
    const p: any = {};
    HEADER.forEach(([k]) => (p[k] = form[k] ?? null));
    ["direction", "doc_type", "supply_type", "reverse_charge", "itc_eligible", "notes"].forEach((k) => (p[k] = form[k] ?? null));
    for (const k of MONEY) { const v = toPaise(String(form[k] ?? "")); if (v === null && form[k]) throw new Error(`Bad amount in ${k}`); p[k] = v ?? 0; }
    p.lines = form.lines.map((l: any) => {
      const m: any = { description: l.description, hsn_sac: l.hsn_sac, quantity: l.quantity, gst_rate: l.gst_rate, uqc: l.uqc };
      LINE_MONEY.forEach((k) => (m[k] = toPaise(String(l[k] ?? "")) ?? 0));
      return m;
    });
    return p;
  };
  const run = async (fn: () => Promise<any>, ok: string) => {
    setMsg("");
    try { hydrate(await fn()); setMsg(ok); } catch (e: any) { setMsg(e.message); }
  };
  const save = () => run(() => api(`/api/invoices/${id}`, jsonBody("PUT", payload())), "Saved.");
  const confirm = () => run(async () => {
    await api(`/api/invoices/${id}`, jsonBody("PUT", payload()));
    return api(`/api/invoices/${id}/confirm`, jsonBody("POST", { acknowledgeWarnings: ack, saveRule }));
  }, "Confirmed and posted to the ledger.");
  const setLine = (i: number, k: string, v: string) => { const ls = [...form.lines]; ls[i] = { ...ls[i], [k]: v }; setForm({ ...form, lines: ls }); };
  const st = types.find((t) => t.value === form.supply_type);

  return (
    <>
      <h1>Review invoice #{inv.id} <span className={`pill ${inv.status === "CONFIRMED" ? "ok" : inv.status === "CANCELLED" ? "bad" : "warn"}`}>{inv.status}</span> {inv.locked && <span className="pill bad">period locked</span>}</h1>
      <div className="grid2">
        <div className="card" style={{ minHeight: 700 }}>
          {inv.document_id ? (
            <iframe title="source document" src={`/api/documents/${inv.document_id}/file`} style={{ width: "100%", height: 900, border: 0 }} />
          ) : <p className="muted">Manual entry — no source document.</p>}
        </div>
        <div>
          <div className="card">
            <b>Checks</b>
            {allFlags.length === 0 && <p className="muted">No issues.</p>}
            <ul style={{ paddingLeft: 18 }}>
              {allFlags.map((f: any, i: number) => (
                <li key={i} className={f.severity === "ERROR" ? "err" : f.severity === "WARN" ? "" : "muted"}>
                  <span className={`pill ${f.severity === "ERROR" ? "bad" : f.severity === "WARN" ? "warn" : ""}`}>{f.severity}</span> {f.message}
                </li>
              ))}
            </ul>
          </div>

          <div className="card">
            <b>Tax treatment — you decide</b>
            <div className="row">
              <div><label>Direction</label>
                <select disabled={!editable} value={form.direction ?? ""} onChange={(e) => setForm({ ...form, direction: e.target.value || null, supply_type: null })} className={cls("direction")}>
                  <option value="">—</option><option>SALE</option><option>PURCHASE</option></select></div>
              <div><label>Document type</label>
                <select disabled={!editable} value={form.doc_type} onChange={(e) => setForm({ ...form, doc_type: e.target.value })}>
                  <option>INVOICE</option><option>CREDIT_NOTE</option><option>DEBIT_NOTE</option></select></div>
              <div style={{ flex: 1 }}><label>Supply type</label>
                <select disabled={!editable} style={{ width: "100%" }} value={form.supply_type ?? ""} className={form.supply_type ? "" : "lowconf"}
                  onChange={(e) => { const v = e.target.value || null; const t = types.find((x) => x.value === v); setForm({ ...form, supply_type: v, reverse_charge: t ? (v === "DOMESTIC_RCM" || v === "IMPORT_SERVICE_RCM" ? 1 : 0) : null }); }}>
                  <option value="">— choose —</option>
                  {types.filter((t) => !form.direction || t.direction === form.direction).map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                </select></div>
            </div>
            {st && <div className="why">{st.rule}</div>}
            <div className="row">
              <div><label>Reverse charge?</label>
                <select disabled={!editable} value={form.reverse_charge ?? ""} onChange={(e) => setForm({ ...form, reverse_charge: e.target.value === "" ? null : Number(e.target.value) })}>
                  <option value="">—</option><option value="0">No</option><option value="1">Yes</option></select></div>
              {form.direction === "PURCHASE" && (
                <div><label>ITC eligible?</label>
                  <select disabled={!editable} value={form.itc_eligible ?? ""} onChange={(e) => setForm({ ...form, itc_eligible: e.target.value === "" ? null : Number(e.target.value) })}>
                    <option value="">—</option><option value="1">Yes</option><option value="0">No (blocked u/s 17(5) / personal / exempt use)</option></select></div>
              )}
              <span className="muted">Source: {inv.tax_treatment_source === "RULE" ? "your saved rule" : inv.tax_treatment_source === "USER" ? "you" : "not set"}</span>
            </div>
          </div>

          <div className="card">
            <b>Header</b>
            <div className="row">
              {HEADER.map(([k, label]) => (
                <div key={k} style={{ width: k === "counterparty_name" || k === "category" ? "100%" : 200 }}>
                  <label>{label}{k === "category" && inv.category_suggested ? " (suggested — check)" : ""}</label>
                  <input disabled={!editable} className={cls(k)} style={{ width: "100%" }} value={form[k] ?? ""} onChange={(e) => setForm({ ...form, [k]: e.target.value })} />
                </div>
              ))}
            </div>
            <div className="row">
              {MONEY.map((k) => (
                <div key={k} style={{ width: 130 }}>
                  <label>{k.replace("_paise", "").toUpperCase()} (₹)</label>
                  <input disabled={!editable} className={cls(k)} style={{ width: "100%", textAlign: "right" }} value={form[k] ?? ""} onChange={(e) => setForm({ ...form, [k]: e.target.value })} />
                </div>
              ))}
            </div>
            {form.reverse_charge === 1 && <div className="why">Reverse charge: the vendor does not charge GST — you pay it. Enter the GST rate you have confirmed on each line, then “Compute RCM tax”. The tax fields then hold your liability; the total stays the amount payable to the vendor.</div>}
          </div>

          <div className="card">
            <b>Lines</b>
            <table>
              <thead><tr><th>Description</th><th>HSN/SAC</th><th>Rate %</th><th className="num">Taxable</th><th className="num">CGST</th><th className="num">SGST</th><th className="num">IGST</th><th></th></tr></thead>
              <tbody>
                {form.lines.map((l: any, i: number) => (
                  <tr key={i}>
                    <td><input disabled={!editable} value={l.description ?? ""} onChange={(e) => setLine(i, "description", e.target.value)} /></td>
                    <td><input disabled={!editable} size={7} value={l.hsn_sac ?? ""} onChange={(e) => setLine(i, "hsn_sac", e.target.value)} /></td>
                    <td><input disabled={!editable} size={4} value={l.gst_rate ?? ""} onChange={(e) => setLine(i, "gst_rate", e.target.value)} /></td>
                    {LINE_MONEY.map((k) => <td key={k}><input disabled={!editable} size={10} style={{ textAlign: "right" }} value={l[k] ?? ""} onChange={(e) => setLine(i, k, e.target.value)} /></td>)}
                    <td>{editable && <button className="secondary" onClick={() => setForm({ ...form, lines: form.lines.filter((_: any, j: number) => j !== i) })}>×</button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {editable && <button className="secondary" onClick={() => setForm({ ...form, lines: [...form.lines, { description: "", taxable_paise: "0" }] })}>+ line</button>}
          </div>

          <div className="card">
            {editable ? (
              <>
                <label><input type="checkbox" checked={ack} onChange={(e) => setAck(e.target.checked)} /> I have checked the highlighted fields against the document</label>
                <label><input type="checkbox" checked={saveRule} onChange={(e) => setSaveRule(e.target.checked)} /> Remember this treatment & category for {form.counterparty_name || "this party"}</label>
                <div className="row" style={{ marginTop: 10 }}>
                  <button className="secondary" onClick={save}>Save draft</button>
                  {form.reverse_charge === 1 && <button className="secondary" onClick={async () => { await save(); run(() => api(`/api/invoices/${id}/compute-rcm`, { method: "POST" }), "RCM tax computed."); }}>Compute RCM tax</button>}
                  <button onClick={confirm}>Confirm & post</button>
                  <button className="secondary" onClick={() => run(() => api(`/api/invoices/${id}/reject`, jsonBody("POST", { reason: "rejected in review" })), "Rejected.")}>Reject</button>
                </div>
              </>
            ) : (
              inv.status === "CONFIRMED" && !inv.locked && <div className="row"><button className="secondary" onClick={() => run(() => api(`/api/invoices/${id}/reopen`, { method: "POST" }), "Reopened.")}>Reopen for edit</button>{inv.direction === "SALE" && <button className="secondary" onClick={() => { const r = prompt("This sales document was issued and then cancelled. Reason (kept in the audit trail)?"); if (r) run(() => api(`/api/invoices/${id}/cancel`, jsonBody("POST", { reason: r })), "Marked as cancelled — it is removed from your returns and counted as cancelled in GSTR-1 Table 13."); }}>Mark as cancelled</button>}</div>
            )}
            {msg && <p className={msg.endsWith(".") && !msg.startsWith("Cannot") && !msg.startsWith("Review") ? "" : "err"}>{msg}</p>}
            <p className="muted">Total {inr(inv.total_paise)} · <a href={`/audit?entity=invoice&entityId=${inv.id}`}>change history</a></p>
          </div>
        </div>
      </div>
    </>
  );
}
