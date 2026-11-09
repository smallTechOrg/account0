"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { usd } from "@/lib/format";

export default function Usage() {
  const [u, setU] = useState<any>(null);
  useEffect(() => { api("/api/llm/usage").then(setU); }, []);
  if (!u) return <p>Loading…</p>;
  return (
    <>
      <h1>LLM spend</h1>
      <div className="card">
        <b>{u.currentMonth}:</b> {usd(u.currentMonthCostUsd)} of {usd(u.monthlyCapUsd)} monthly cap (change in <a href="/settings">Settings</a>)
        <div className="progress" style={{ marginTop: 6 }}><div style={{ width: `${Math.min(100, (100 * u.currentMonthCostUsd) / u.monthlyCapUsd)}%` }} /></div>
      </div>
      <h2>By month</h2>
      <table><thead><tr><th>Month</th><th className="num">Calls</th><th className="num">Input tokens</th><th className="num">Output tokens</th><th className="num">Cost</th></tr></thead>
        <tbody>{u.months.map((m: any) => <tr key={m.month}><td>{m.month}</td><td className="num">{m.calls}</td><td className="num">{m.input_tokens}</td><td className="num">{m.output_tokens}</td><td className="num">{usd(m.cost_usd)}</td></tr>)}</tbody></table>
      <h2>Recent calls</h2>
      <table><thead><tr><th>When</th><th>Model</th><th>Purpose</th><th>Ref</th><th className="num">In</th><th className="num">Out</th><th className="num">Cost</th><th>Status</th></tr></thead>
        <tbody>{u.recent.map((c: any) => <tr key={c.id}><td className="muted">{c.created_at}</td><td>{c.model}</td><td>{c.purpose}</td><td>{c.ref}</td><td className="num">{c.input_tokens}</td><td className="num">{c.output_tokens}</td><td className="num">{usd(c.cost_usd)}</td><td>{c.status}{c.error && <div className="err" style={{ fontSize: 12 }}>{c.error}</div>}</td></tr>)}</tbody></table>
    </>
  );
}
