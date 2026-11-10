"use client";
import { useEffect, useState } from "react";
import { api, jsonBody } from "@/lib/api";
import { inr, toPaise } from "@/lib/format";
import { Empty } from "@/lib/Empty";

const today = () => new Date().toISOString().slice(0, 10);

export default function Advances() {
  const [list, setList] = useState<any[]>([]);
  const [bank, setBank] = useState<any[]>([]);
  const [sales, setSales] = useState<any[]>([]);
  const [msg, setMsg] = useState("");
  const [f, setF] = useState<any>({ receipt_date: today(), customer_name: "", customer_gstin: "", place_of_supply: "", kind: "DOMESTIC", supply_kind: "SERVICES", gst_rate: "18", amount: "", bank_txn_id: "" });
  const load = () => {
    api("/api/advances").then(setList);
    api("/api/bank/txns?status=UNMATCHED").then((t: any[]) => setBank(t.filter((x) => x.credit_paise > 0))).catch(() => setBank([]));
    api("/api/invoices?status=CONFIRMED&direction=SALE").then(setSales);
  };
  useEffect(() => { load(); }, []);
  const set = (k: string, v: string) => setF({ ...f, [k]: v });
  const save = async () => {
    setMsg("");
    const body: any = { ...f };
    delete body.amount;
    if (f.bank_txn_id) body.bank_txn_id = Number(f.bank_txn_id); else { delete body.bank_txn_id; body.gross_paise = toPaise(f.amount); }
    try { await api("/api/advances", jsonBody("POST", body)); setMsg("Advance recorded."); load(); } catch (e: any) { setMsg(e.message); }
  };
  const adjust = async (id: number, invoiceId: string, amount: string) => {
    if (!invoiceId) return;
    try { await api(`/api/advances/${id}/adjust`, jsonBody("POST", { invoiceId: Number(invoiceId), grossPaise: amount ? toPaise(amount) : null })); load(); } catch (e: any) { setMsg(e.message); }
  };
  return (
    <>
      <h1>Advances received (GSTR-1 Table 11)</h1>
      <p className="muted">For <b>services</b>, GST is due when the advance is received (Sec 13(2) CGST Act): the tax inside a domestic advance is reported in Table 11A and 3B 3.1(a) for the month of receipt, and taken back (Table 11B) when the invoice is issued in a later month. Advances for <b>goods</b> carry no tax (Notification 66/2017-CT); <b>export</b> advances under LUT carry no tax. An advance and its invoice in the same month are not reported in Table 11.</p>
      <div className="card">
        <div className="row">
          <div><label>Received on</label><input type="date" value={f.receipt_date} onChange={(e) => set("receipt_date", e.target.value)} /></div>
          <div><label>Customer</label><input value={f.customer_name} onChange={(e) => set("customer_name", e.target.value)} /></div>
          <div><label>Customer GSTIN (optional)</label><input value={f.customer_gstin} onChange={(e) => set("customer_gstin", e.target.value)} /></div>
          <div><label>Place of supply (state code)</label><input value={f.place_of_supply} onChange={(e) => set("place_of_supply", e.target.value)} placeholder="29" /></div>
        </div>
        <div className="row">
          <div><label>Kind</label><select value={f.kind} onChange={(e) => set("kind", e.target.value)}><option value="DOMESTIC">Domestic</option><option value="EXPORT_LUT">Export under LUT (no tax)</option><option value="EXPORT_PAID">Export with IGST</option></select></div>
          <div><label>For</label><select value={f.supply_kind} onChange={(e) => set("supply_kind", e.target.value)}><option value="SERVICES">Services</option><option value="GOODS">Goods (no tax on advance)</option></select></div>
          <div><label>GST rate %</label><input value={f.gst_rate} onChange={(e) => set("gst_rate", e.target.value)} /></div>
          <div><label>From bank receipt</label><select value={f.bank_txn_id} onChange={(e) => set("bank_txn_id", e.target.value)}><option value="">— not linked —</option>{bank.map((t) => <option key={t.id} value={t.id}>{t.txn_date} {inr(t.credit_paise)} {t.description}</option>)}</select></div>
          {!f.bank_txn_id && <div><label>Amount received (₹, incl. GST)</label><input value={f.amount} onChange={(e) => set("amount", e.target.value)} /></div>}
          <button onClick={save}>Record advance</button>
        </div>
      </div>
      {msg && <div className="card">{msg}</div>}
      <Empty show={list.length === 0}>No advances recorded.</Empty>
      {list.length > 0 && <table data-testid="advances">
        <thead><tr><th>Date</th><th>Customer</th><th>Kind</th><th className="num">Received</th><th className="num">Taxable</th><th className="num">Tax</th><th className="num">Unadjusted</th><th>Adjusted against</th><th>Adjust</th></tr></thead>
        <tbody>{list.map((a) => <Row key={a.id} a={a} sales={sales} onAdjust={adjust} />)}</tbody>
      </table>}
    </>
  );
}

function Row({ a, sales, onAdjust }: { a: any; sales: any[]; onAdjust: (id: number, inv: string, amt: string) => void }) {
  const [inv, setInv] = useState(""); const [amt, setAmt] = useState("");
  return (
    <tr><td>{a.receipt_date}</td><td>{a.customer_name}</td><td>{a.kind}{!a.taxedAtReceipt && <span className="pill">no tax</span>}{a.bank_txn_id ? <span className="pill ok">bank</span> : null}</td>
      <td className="num">{inr(a.gross_paise)}</td><td className="num">{inr(a.taxable_paise)}</td><td className="num">{inr(a.igst_paise + a.cgst_paise + a.sgst_paise)}</td><td className="num">{inr(a.balancePaise)}</td>
      <td>{a.adjustments.map((x: any) => <div key={x.id}>{x.invoice_no} {inr(x.gross_paise)} ({x.period})</div>)}</td>
      <td>{a.balancePaise > 0 && <><select value={inv} onChange={(e) => setInv(e.target.value)}><option value="">invoice…</option>{sales.map((s) => <option key={s.id} value={s.id}>{s.invoice_no} {s.counterparty_name}</option>)}</select>
        <input style={{ width: 90 }} placeholder="₹ (all)" value={amt} onChange={(e) => setAmt(e.target.value)} /><button className="secondary" onClick={() => onAdjust(a.id, inv, amt)}>Adjust</button></>}</td></tr>
  );
}
