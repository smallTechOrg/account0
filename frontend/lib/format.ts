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
