import "./globals.css";
import type { ReactNode } from "react";

export const metadata = { title: "Account0", description: "Local-first back-office & compliance co-pilot" };

const NAV: [string, [string, string][]][] = [
  ["Overview", [["/", "Dashboard"], ["/company", "Company profile"], ["/settings", "Settings"]]],
  ["Books", [["/inbox", "Invoice inbox"], ["/review", "Review queue"], ["/invoices", "Invoices"], ["/ledger", "Ledger"], ["/rules", "Vendor/customer rules"]]],
  ["GST", [["/gst", "GSTR-3B / GSTR-1"], ["/filings", "Filing history"]]],
  ["System", [["/usage", "LLM spend"], ["/audit", "Audit trail"]]],
];

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <div className="shell">
          <nav className="side">
            <div className="brand">Account0</div>
            {NAV.map(([g, items]) => (
              <div key={g}>
                <div className="grp">{g}</div>
                {items.map(([href, label]) => <a key={href} href={href}>{label}</a>)}
              </div>
            ))}
          </nav>
          <main>{children}</main>
        </div>
      </body>
    </html>
  );
}
