"use client";
import { useEffect, useState } from "react";
import { api, jsonBody } from "@/lib/api";
import { inr } from "@/lib/format";

const defaultPeriod = () => { const d = new Date(); d.setMonth(d.getMonth() - 1); return d.toISOString().slice(0, 7); };

function Citations({ list }: { list: any[] }) {
  return <div className="muted">{list.map((c) => <details key={c.id}><summary><b>{c.citation}</b> — {c.title}</summary><div className="why">{c.text}</div></details>)}</div>;
}

export default function Learn() {
  const [tab, setTab] = useState<"ask" | "walk" | "diy" | "library">("ask");
  const [q, setQ] = useState("");
  const [ans, setAns] = useState<any>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const [period, setPeriod] = useState(defaultPeriod());
  const [topic, setTopic] = useState("gstr3b");
  const [walk, setWalk] = useState<any>(null);
  const [narr, setNarr] = useState("");
  const [diy, setDiy] = useState<any>(null);
  const [lib, setLib] = useState<any[]>([]);
  const [filter, setFilter] = useState("");
  const [hist, setHist] = useState<any[]>([]);
  useEffect(() => { api("/api/learn/library").then(setLib); api("/api/learn/history").then(setHist); }, []);
  useEffect(() => { if (tab === "walk") api(`/api/learn/walkthrough/${topic}?period=${period}`).then((w) => { setWalk(w); setNarr(""); setErr(""); }).catch((e) => setErr(e.message)); }, [tab, topic, period]);
  useEffect(() => { if (tab === "diy") api(`/api/learn/diy/${period}`).then((d) => { setDiy(d); setErr(""); }).catch((e) => setErr(e.message)); }, [tab, period]);
  const ask = async () => { setBusy(true); setErr(""); try { setAns(await api("/api/learn/ask", jsonBody("POST", { question: q }))); api("/api/learn/history").then(setHist); } catch (e: any) { setErr(e.message); } finally { setBusy(false); } };
  const Figs = ({ f }: { f: any[] }) => <>{f.map((x, i) => <span key={i} className="pill" style={{ marginRight: 6 }}>{x.label}: {inr(x.paise)}</span>)}</>;
  return (
    <>
      <h1>Learn</h1>
      <p className="muted">Learn the process on your own data. Everything here is explained from the same deterministic numbers as your returns; the Q&amp;A answers only from a curated reference library and shows its sources.</p>
      <div className="card row">
        {([["ask", "Ask a question"], ["walk", "Walkthrough of my month"], ["diy", "File it myself (guided)"], ["library", "Reference library"]] as const).map(([k, l]) => <button key={k} className={tab === k ? "" : "secondary"} onClick={() => setTab(k)}>{l}</button>)}
        {(tab === "walk" || tab === "diy") && <div><label>Month</label><input type="month" value={period} onChange={(e) => setPeriod(e.target.value)} /></div>}
        {tab === "walk" && <div><label>Topic</label><select value={topic} onChange={(e) => setTopic(e.target.value)}><option value="gstr3b">How my GSTR-3B is built</option><option value="rcm">Reverse charge on my purchases</option><option value="itc">My input tax credit</option><option value="tds">TDS on my payments</option></select></div>}
      </div>
      {err && <div className="card err">{err}</div>}
      {tab === "ask" && <>
        <div className="card"><textarea rows={3} style={{ width: "100%" }} placeholder="e.g. Why do I pay GST on my US software subscription? · What happens if a vendor hasn't filed GSTR-1? · Explain my GSTR-3B for October 2026" value={q} onChange={(e) => setQ(e.target.value)} />
          <button disabled={busy || !q.trim()} onClick={ask}>{busy ? "Thinking…" : "Ask (≈$0.01)"}</button></div>
        {ans && <div className="card" data-testid="answer">
          {!ans.answered && <div className="stub">{ans.answer}</div>}
          {ans.answered && <><div style={{ whiteSpace: "pre-wrap" }}>{ans.answer}</div>
            {ans.warning && <div className="stub">{ans.warning}</div>}
            <h2>Sources</h2><Citations list={ans.citations} />
            {ans.usedFacts && <details><summary className="muted">Your figures used (computed by code)</summary>{ans.usedFacts.map((f: string, i: number) => <div key={i} className="muted">{f}</div>)}</details>}
            <p className="muted">{ans.disclaimer}</p></>}
        </div>}
        {hist.length > 0 && <details><summary className="muted">Earlier questions</summary>{hist.map((h) => <div key={h.id} className="card"><b>{h.question}</b><div style={{ whiteSpace: "pre-wrap" }}>{h.answer}</div></div>)}</details>}
      </>}
      {tab === "walk" && walk && <>
        <h2>{walk.title}</h2>
        {walk.empty && <div className="stub">No data for this month yet, so this is the generic explanation. Upload and confirm invoices to see your own numbers.</div>}
        {walk.steps.map((s: any, i: number) => <div className="card" key={i}><b>{i + 1}. {s.title}</b><p>{s.text}</p><Figs f={s.figures} /><Citations list={s.citations} />{s.link && <a href={s.link}>Open in Account0 →</a>}</div>)}
        <button className="secondary" onClick={async () => { try { setNarr((await api(`/api/learn/walkthrough/${topic}/narrate?period=${period}`, { method: "POST" })).narration); } catch (e: any) { setErr(e.message); } }}>Explain it more simply with Claude (≈$0.02)</button>
        {narr && <div className="card" style={{ whiteSpace: "pre-wrap" }}>{narr}</div>}
      </>}
      {tab === "diy" && diy && <>
        <h2>{diy.title}</h2>
        <div className="card"><b>Am I ready?</b> {diy.readiness.verdict}<div className="muted">Months compared with your CA's filed 3B: {diy.readiness.comparedMonths}; identical: {diy.readiness.agreeingMonths}.</div></div>
        <div className="progress" style={{ width: 300 }}><div style={{ width: `${(100 * diy.progress.done) / diy.progress.total}%` }} /></div>
        {diy.steps.map((s: any, i: number) => <div className="card" key={s.key}><div className="row"><span className={`pill ${s.done ? "ok" : "warn"}`}>{s.done ? "done" : "to do"}</span><b>{i + 1}. {s.title}</b><span style={{ flex: 1 }} />
          <button className="secondary" onClick={async () => setDiy(await api(`/api/learn/diy/${period}/${s.key}`, jsonBody("POST", { done: !s.done })))}>{s.done ? "Undo" : "Mark done"}</button></div>
          <p>{s.text}</p><Figs f={s.figures} /><Citations list={s.citations} />{s.link && <a href={s.link}>Open in Account0 →</a>}</div>)}
      </>}
      {tab === "library" && <>
        <div className="card"><input placeholder="Filter…" value={filter} onChange={(e) => setFilter(e.target.value)} /> <span className="muted">Summaries are maintained by Account0 and are not legal advice — check the bare Act or ask your CA.</span></div>
        {lib.filter((e) => (e.title + e.citation + e.text).toLowerCase().includes(filter.toLowerCase())).map((e) => <div className="card" key={e.id}><b>{e.title}</b> <span className="pill">{e.citation}</span><p>{e.text}</p></div>)}
      </>}
    </>
  );
}
