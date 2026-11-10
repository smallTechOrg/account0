/** Shows an error toast (rendered by ToastHost in the layout). No-op outside the browser. */
export function toast(message: string, kind: "error" | "info" = "error") {
  if (typeof window !== "undefined") window.dispatchEvent(new CustomEvent("a0-toast", { detail: { message, kind } }));
}
export async function api<T = any>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try { res = await fetch(path, { cache: "no-store", ...init }); }
  catch { const m = "Cannot reach the Account0 server — is it running?"; toast(m); throw new Error(m); }
  const text = await res.text();
  let body: any = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = { error: text }; }
  if (!res.ok) { const m = body?.error || `${res.status} ${res.statusText}`; toast(m); throw new Error(m); }
  return body as T;
}
export const jsonBody = (method: string, data: unknown): RequestInit => ({
  method,
  headers: { "content-type": "application/json" },
  body: JSON.stringify(data),
});
