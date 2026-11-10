import { inr } from "@/lib/format";
export function ClaimTable({ rows }: { rows: any[] }) {
  return (
    <table data-testid="itc-claims">
      <thead><tr><th>Document</th><th>Party</th><th className="num">Tax</th><th>Candidate periods</th><th>Claimed in</th><th>Here?</th><th>Note</th></tr></thead>
      <tbody>{rows.map((r: any) => (
        <tr key={r.invoiceId}><td><a href={`/review/${r.invoiceId}`}>{r.invoiceNo}</a>{r.docType !== "INVOICE" && <span className="pill">{r.docType}</span>}</td><td>{r.party}</td><td className="num">{inr(r.taxPaise)}</td>
          <td>{r.candidates.join(", ")}</td><td>{r.claimedIn ?? "—"}{r.filed && <span className="pill ok">filed</span>}</td>
          <td>{r.claimedHere ? <span className="pill ok">claimed</span> : <span className="pill warn">not here</span>}</td><td className="muted">{r.note}</td></tr>))}</tbody>
    </table>
  );
}
