"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";

let cache: Promise<Record<string, any>> | null = null;
const load = () => (cache ??= api("/api/learn/why").catch(() => ({})));

/** Inline "why" note: a small (?) that expands to a plain-language explanation with citations. */
export function Why({ k }: { k: string }) {
  const [note, setNote] = useState<any>(null);
  const [open, setOpen] = useState(false);
  useEffect(() => { load().then((all) => setNote(all[k] ?? null)); }, [k]);
  if (!note) return null;
  return (
    <>
      <a className="fig" title={note.title} onClick={() => setOpen(!open)} style={{ marginLeft: 6 }}>(?)</a>
      {open && (
        <div className="why">
          <b>{note.title}</b> — {note.text}
          <div className="muted">{note.citations.map((c: any) => c.citation).join(" · ")}</div>
        </div>
      )}
    </>
  );
}
