"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";

export default function Rules() {
  const [rows, setRows] = useState<any[]>([]);
  const load = () => api("/api/rules").then(setRows);
  useEffect(() => { load(); }, []);
  return (
    <>
      <h1>Vendor / customer rules</h1>
      <p className="muted">Learned only from invoices you confirmed. A rule pre-fills treatment and category for the next invoice from the same party — you still confirm each invoice.</p>
      <table>
        <thead><tr><th>Party</th><th>Key</th><th>Direction</th><th>Supply type</th><th>RCM</th><th>ITC</th><th>Rate</th><th>Category</th><th>Used</th><th /></tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td>{r.display_name}</td><td className="muted">{r.match_key}</td><td>{r.direction}</td><td>{r.supply_type}</td>
              <td>{r.reverse_charge ? "Yes" : "No"}</td><td>{r.itc_eligible === null ? "—" : r.itc_eligible ? "Yes" : "No"}</td><td>{r.default_gst_rate ?? "—"}</td>
              <td>{r.category}</td><td>{r.times_applied}×</td>
              <td><button className="secondary" onClick={async () => { await api(`/api/rules/${r.id}`, { method: "DELETE" }); load(); }}>Forget</button></td>
            </tr>
          ))}
          {rows.length === 0 && <tr><td colSpan={10} className="muted">No rules yet.</td></tr>}
        </tbody>
      </table>
    </>
  );
}
