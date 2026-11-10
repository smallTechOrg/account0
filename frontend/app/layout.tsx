import "./globals.css";
import type { ReactNode } from "react";
import { ToastHost } from "@/lib/ToastHost";

export const metadata = { title: "Account0", description: "Local-first back-office & compliance co-pilot" };

const NAV: [string, [string, string][]][] = [
  ["Overview", [["/", "Dashboard"], ["/company", "Company profile"], ["/settings", "Settings"]]],
  ["Books", [["/inbox", "Invoice inbox"], ["/review", "Review queue"], ["/invoices", "Invoices"], ["/ledger", "Ledger"], ["/bank", "Bank reconciliation"], ["/rules", "Vendor/customer rules"]]],
  ["GST", [["/gst", "GSTR-3B / GSTR-1"], ["/filings", "Filing history"], ["/scrutiny", "CA scrutiny"], ["/itc", "GSTR-2B / ITC match"], ["/advances", "Advances (Table 11)"]]],
  ["TDS & payroll", [["/tds", "TDS register"], ["/payroll", "Payroll / Sec 192"]]],
  ["Compliance", [["/calendar", "Compliance calendar"], ["/close", "Month-close checklist"]]],
  ["Learn", [["/learn", "Learn & ask"]]],
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
          <ToastHost />
        </div>
      </body>
    </html>
  );
}
