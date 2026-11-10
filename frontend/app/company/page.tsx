"use client";
import { useEffect, useState } from "react";
import { api, jsonBody } from "@/lib/api";

const FIELDS: [string, string, string?][] = [
  ["legal_name", "Legal name"], ["trade_name", "Trade name"], ["gstin", "GSTIN", "15 chars; check digit is validated"],
  ["pan", "PAN", "auto-filled from GSTIN"], ["tan", "TAN"], ["cin", "CIN"], ["address", "Registered address"],
  ["lut_arn", "LUT ARN (exports without IGST)"], ["lut_valid_from", "LUT valid from (YYYY-MM-DD)"], ["lut_valid_to", "LUT valid to (YYYY-MM-DD)"],
];

export default function Company() {
  const [c, setC] = useState<any>(null);
  const [msg, setMsg] = useState("");
  useEffect(() => { api("/api/company").then(setC); }, []);
  if (!c) return <p>Loading…</p>;
  const save = async () => {
    try { setC(await api("/api/company", jsonBody("PUT", c))); setMsg("Saved."); }
    catch (e: any) { setMsg(e.message); }
  };
  return (
    <>
      <h1>Company profile</h1>
      <div className="card" style={{ maxWidth: 720 }}>
        {FIELDS.map(([k, label, hint]) => (
          <div key={k}>
            <label>{label} {hint && <span className="muted">— {hint}</span>}</label>
            <input style={{ width: "100%" }} value={c[k] ?? ""} onChange={(e) => setC({ ...c, [k]: e.target.value })} />
          </div>
        ))}
        <div className="row">
          <div><label>State code (from GSTIN)</label><input value={c.state_code ?? ""} disabled /></div>
          <div><label>FY starts (month)</label><input type="number" value={c.fy_start_month ?? 4} onChange={(e) => setC({ ...c, fy_start_month: Number(e.target.value) })} /></div>
          <div><label>GST return frequency</label>
            <select value={c.gst_filing_frequency} onChange={(e) => setC({ ...c, gst_filing_frequency: e.target.value })}>
              <option>MONTHLY</option><option>QUARTERLY</option>
            </select></div>
        </div>
        <div style={{ marginTop: 12 }}><button onClick={save}>Save</button> <span className={msg === "Saved." ? "" : "err"}>{msg}</span></div>
        <div className="why">Why: your GSTIN decides whether an uploaded invoice is a sale (your GSTIN is the supplier) or a purchase (your GSTIN is the recipient), and whether a supply is intra-state (CGST+SGST) or inter-state (IGST) — Sec 7/8 IGST Act.</div>
      </div>
    </>
  );
}
