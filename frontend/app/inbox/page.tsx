"use client";
import { useEffect, useRef, useState } from "react";
import { api, jsonBody } from "@/lib/api";

const statusClass = (s: string) => (s === "CONFIRMED" ? "ok" : s === "FAILED" || s === "REJECTED" ? "bad" : s === "NEEDS_REVIEW" || s === "DUPLICATE" ? "warn" : "");

export default function Inbox() {
  const [docs, setDocs] = useState<any[]>([]);
  const [batch, setBatch] = useState<any>(null);
  const [sources, setSources] = useState<any>(null);
  const [err, setErr] = useState("");
  const fileRef = useRef<HTMLInputElement>(null);
  const load = () => api("/api/documents").then(setDocs);
  useEffect(() => { load(); api("/api/sources").then(setSources); }, []);
  useEffect(() => {
    if (!batch || batch.complete) return;
    const t = setTimeout(async () => { setBatch(await api(`/api/batches/${batch.batchId}`)); load(); }, 1500);
    return () => clearTimeout(t);
  }, [batch]);

  const upload = async (files: FileList | null) => {
    if (!files || !files.length) return;
    const fd = new FormData();
    Array.from(files).forEach((f) => fd.append("files", f));
    setErr("");
    try {
      const r = await api("/api/documents", { method: "POST", body: fd });
      if (r.errors?.length) setErr(r.errors.join("; "));
      setBatch(await api(`/api/batches/${r.batchId}`));
      load();
    } catch (e: any) { setErr(e.message); }
  };

  return (
    <>
      <h1>Invoice inbox</h1>
      <div className="card"
        onDragOver={(e) => e.preventDefault()}
        onDrop={(e) => { e.preventDefault(); upload(e.dataTransfer.files); }}
        style={{ borderStyle: "dashed", textAlign: "center", padding: 28 }}>
        <p>Drop PDF / PNG / JPG invoices here, or</p>
        <input ref={fileRef} type="file" multiple accept=".pdf,.png,.jpg,.jpeg,.webp" onChange={(e) => upload(e.target.files)} />
        <p className="muted">Each document is read by Claude in one call; numbers are then checked by plain code. Nothing reaches your books until you confirm it in the review queue.</p>
        {err && <p className="err">{err}</p>}
      </div>
      {batch && (
        <div className="card" data-testid="batch">
          <b>Batch #{batch.batchId}:</b> {batch.done} / {batch.total} processed {batch.complete && "— done"}
          <div className="progress" style={{ marginTop: 6 }}><div style={{ width: `${batch.total ? (100 * batch.done) / batch.total : 0}%` }} /></div>
        </div>
      )}
      {sources && (
        <div className="card">
          <b>Other sources</b>
          <p>Watched folder: <code>{sources.watchedFolder}</code> — files dropped there are imported every ~10 s.{" "}
            <button className="secondary" onClick={async () => { await api("/api/sources/folder/scan", { method: "POST" }); load(); }}>Scan now</button></p>
          {sources.external.map((s: any) => (
            <div key={s.name} className={s.connected ? "card" : "stub"}>
              <b>{s.name}</b> <span className="muted">{s.target}</span>
              <div>{s.message}</div>
              <div className="muted">Last sync: {s.lastSync}</div>
              <div className="row" style={{ marginTop: 6 }}>
                {s.configured && !s.connected && <button onClick={async () => { try { const r = await api("/api/google/auth-url"); window.location.href = r.url; } catch (e: any) { setErr(e.message); } }}>Connect Google (read-only)</button>}
                {s.connected && <button className="secondary" onClick={async () => { try { const r = await api(`/api/google/sync/${s.name === "Gmail" ? "gmail" : "drive"}`, { method: "POST" }); setErr(`Imported ${r.imported} new file(s).`); load(); api("/api/sources").then(setSources); } catch (e: any) { setErr(e.message); } }}>Sync now</button>}
                {s.connected && s.name === "Gmail" && <button className="secondary" onClick={async () => { const l = prompt("Gmail label to watch (e.g. Account0/Invoices)?"); if (l) { await api("/api/google/config", jsonBody("PUT", { gmail_label: l })); api("/api/sources").then(setSources); } }}>Change label</button>}
                {s.connected && s.name !== "Gmail" && <button className="secondary" onClick={async () => { const f = prompt("Drive folder id (the last part of the folder URL)?"); if (f !== null) { await api("/api/google/config", jsonBody("PUT", { drive_folder_id: f })); api("/api/sources").then(setSources); } }}>Set folder</button>}
                {s.connected && s.name === "Gmail" && <button className="secondary" onClick={async () => { await api("/api/google/disconnect", { method: "POST" }); api("/api/sources").then(setSources); }}>Disconnect Google</button>}
              </div>
            </div>))}
        </div>
      )}
      <h2>Documents</h2>
      <table>
        <thead><tr><th>#</th><th>File</th><th>Source</th><th>Status</th><th>Uploaded</th><th></th></tr></thead>
        <tbody>
          {docs.map((d) => (
            <tr key={d.id}>
              <td>{d.id}</td><td>{d.filename}</td><td>{d.source}</td>
              <td><span className={`pill ${statusClass(d.status)}`}>{d.status}</span> {d.error && <div className="muted" style={{ fontSize: 12 }}>{d.error}</div>}</td>
              <td className="muted">{d.created_at}</td>
              <td>
                {d.invoice_id && <a href={`/review/${d.invoice_id}`}>Review</a>}{" "}
                {d.status === "FAILED" && <button className="secondary" onClick={async () => { await api(`/api/documents/${d.id}/retry`, { method: "POST" }); load(); }}>Retry</button>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
