"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { usd } from "@/lib/format";

export default function Dashboard() {
  const [health, setHealth] = useState<any>(null);
  const [usage, setUsage] = useState<any>(null);
  const [ping, setPing] = useState<any>(null);
  const [alerts, setAlerts] = useState<any[]>([]);
  const [err, setErr] = useState<string>("");
  useEffect(() => {
    api("/health").then(setHealth).catch((e) => setErr(String(e)));
    api("/api/llm/usage").then(setUsage).catch(() => {});
    api("/api/alerts").then(setAlerts).catch(() => {});
  }, []);
  return (
    <>
      <h1>Dashboard</h1>
      {err && <div className="card err">Backend unreachable: {err}</div>}
      <div className="card" data-testid="health">
        <b>Backend:</b> {health ? <span className={`pill ${health.status === "UP" ? "ok" : "bad"}`}>{health.status}</span> : "…"}{" "}
        {health && <span className="muted">schema v{health.schemaVersion} · LLM key {health.llmKeyConfigured ? "configured" : "MISSING"} · workspace header {health.llmWorkspaceConfigured ? "configured" : "MISSING"}</span>}
        <div style={{ marginTop: 8 }}>
          <button onClick={async () => setPing(await api("/api/llm/ping", { method: "POST" }))}>Test LLM key (≈$0.0001)</button>{" "}
          {ping && (ping.ok ? <span className="pill ok">OK — {ping.model} replied “{ping.reply}”</span> : <span className="pill bad">{ping.error}</span>)}
        </div>
      </div>
      <div className="card" data-testid="alerts">
        <b>Alerts</b> {alerts.length === 0 && <span className="muted">— nothing needs attention.</span>}
        {alerts.map((a, i) => (
          <div key={i} style={{ marginTop: 8 }}>
            <span className={`pill ${a.severity === "HIGH" ? "bad" : a.severity === "MEDIUM" ? "warn" : ""}`}>{a.severity}</span> {a.message}
            <div className="why">{a.why}</div>
          </div>))}
      </div>
      {usage && (
        <div className="card">
          <b>LLM spend {usage.currentMonth}:</b> {usd(usage.currentMonthCostUsd)} of {usd(usage.monthlyCapUsd)} cap
        </div>
      )}
    </>
  );
}
