"use client";
import { useEffect, useState } from "react";
import { api, jsonBody } from "@/lib/api";

export default function Settings() {
  const [s, setS] = useState<Record<string, string>>({});
  const [cap, setCap] = useState("");
  const [msg, setMsg] = useState("");
  useEffect(() => { api("/api/settings").then((x) => { setS(x); setCap(x["llm.monthly_cap_usd"] ?? ""); }); }, []);
  return (
    <>
      <h1>Settings</h1>
      <div className="card" style={{ maxWidth: 520 }}>
        <label>Monthly LLM spend cap (USD)</label>
        <input value={cap} onChange={(e) => setCap(e.target.value)} />{" "}
        <button onClick={async () => {
          if (!/^\d+(\.\d+)?$/.test(cap)) { setMsg("Enter a number"); return; }
          setS(await api("/api/settings/llm.monthly_cap_usd", jsonBody("PUT", { value: cap }))); setMsg("Saved.");
        }}>Save</button> {msg}
        <p className="muted">When this month’s spend reaches the cap, new extraction calls are refused (logged as BLOCKED_CAP) until you raise it.</p>
      </div>
      <pre className="muted">{JSON.stringify(s, null, 2)}</pre>
    </>
  );
}
