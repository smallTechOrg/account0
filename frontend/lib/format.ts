/** Formats integer paise as Indian-grouped rupees, e.g. 12345678 -> "₹1,23,456.78". */
export function inr(paise: number | null | undefined): string {
  if (paise === null || paise === undefined || Number.isNaN(paise)) return "—";
  const neg = paise < 0;
  const abs = Math.abs(Math.round(paise));
  const rupees = Math.floor(abs / 100).toString();
  const p = (abs % 100).toString().padStart(2, "0");
  let grouped = rupees;
  if (rupees.length > 3) {
    const last3 = rupees.slice(-3);
    const rest = rupees.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ",");
    grouped = `${rest},${last3}`;
  }
  return `${neg ? "-" : ""}₹${grouped}.${p}`;
}

/** Parses "1,234.50" rupee text to paise; returns null if invalid. */
export function toPaise(text: string): number | null {
  const t = text.replace(/[,₹\s]/g, "");
  if (t === "") return null;
  if (!/^-?\d+(\.\d{1,2})?$/.test(t)) return null;
  const [w, f = ""] = t.replace("-", "").split(".");
  const v = parseInt(w, 10) * 100 + parseInt((f + "00").slice(0, 2), 10);
  return t.startsWith("-") ? -v : v;
}

export function usd(v: number | null | undefined): string {
  return v === null || v === undefined ? "—" : `$${Number(v).toFixed(4)}`;
}

/** Paise → plain rupee string for form inputs ("1234.50"). */
export function rs(paise: number | null | undefined): string {
  if (paise === null || paise === undefined) return "";
  return (paise / 100).toFixed(2);
}

/** Decimal rupee amount (e.g. GST offline-tool JSON "12345.5") -> Indian grouping without the symbol: "12,345.50". */
export function inrPlain(rupees: number | string | null | undefined): string {
  if (rupees === null || rupees === undefined || rupees === "" || Number.isNaN(Number(rupees))) return "—";
  return inr(Math.round(Number(rupees) * 100)).replace("₹", "");
}

/** Financial-year labels ("2026-27") from `back` years ago to `fwd` years ahead of the current FY, newest first. */
export function fyOptions(now: Date = new Date(), back = 4, fwd = 1): string[] {
  const cur = now.getMonth() >= 3 ? now.getFullYear() : now.getFullYear() - 1;
  const out: string[] = [];
  for (let y = cur + fwd; y >= cur - back; y--) out.push(`${y}-${String((y + 1) % 100).padStart(2, "0")}`);
  return out;
}
