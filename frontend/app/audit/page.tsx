"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { Empty } from "@/lib/Empty";

export default function Audit() {
  const [rows, setRows] = useState<any[]>([]);
  useEffect(() => {
    const q = typeof window !== "undefined" ? window.location.search : "";
    api(`/api/audit${q}`).then(setRows);
  }, []);
  return (
    <>
      <h1>Audit trail</h1>
      <p className="muted">Every change to a figure, rule, setting or lock — who, when, old → new.</p>
      <table>
        <thead><tr><th>When</th><th>Actor</th><th>Entity</th><th>Action</th><th>Field</th><th>Old</th><th>New</th><th>Note</th></tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td className="muted">{r.at}</td><td>{r.actor}</td><td>{r.entity} #{r.entity_id}</td><td>{r.action}</td><td>{r.field}</td>
              <td style={{ maxWidth: 260, overflowWrap: "anywhere" }}>{r.old_value}</td><td style={{ maxWidth: 260, overflowWrap: "anywhere" }}>{r.new_value}</td><td>{r.note}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <Empty show={rows.length === 0}>No audit entries yet — every change to a figure, rule or lock will appear here.</Empty>
    </>
  );
}
