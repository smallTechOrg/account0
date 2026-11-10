"use client";
import { useEffect, useState } from "react";

type T = { id: number; message: string; kind: string };
/** Bottom-right toasts for failed API calls (api() dispatches "a0-toast"). */
export function ToastHost() {
  const [items, setItems] = useState<T[]>([]);
  useEffect(() => {
    let n = 0;
    const on = (e: Event) => {
      const d = (e as CustomEvent).detail; const id = ++n;
      setItems((x) => [...x.slice(-3), { id, message: d.message, kind: d.kind }]);
      setTimeout(() => setItems((x) => x.filter((t) => t.id !== id)), 7000);
    };
    window.addEventListener("a0-toast", on);
    return () => window.removeEventListener("a0-toast", on);
  }, []);
  return (
    <div className="toasts" role="status" aria-live="polite">
      {items.map((t) => <div key={t.id} className={`toast ${t.kind}`} data-testid="toast" onClick={() => setItems((x) => x.filter((y) => y.id !== t.id))}>{t.message}</div>)}
    </div>
  );
}
