/** Friendly empty state shown under a table that has no rows. */
export function Empty({ show, children }: { show: boolean; children: React.ReactNode }) {
  return show ? <div className="empty" data-testid="empty">{children}</div> : null;
}
