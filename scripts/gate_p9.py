"""P9 gate body (no LLM): bank/TDS/payroll postings are balanced, deterministic, audited, idempotent."""
import json, sys, urllib.request, urllib.error, uuid, os

BASE = sys.argv[1]
ROOT = os.path.join(os.path.dirname(__file__), "..")
exp = json.load(open(os.path.join(ROOT, "fixtures", "invoices", "expected.json")))

def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"content-type": "application/json"} if data else {})
    try:
        with urllib.request.urlopen(req, timeout=60) as r: return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return {"_status": e.code, **json.loads(e.read() or b"{}")}

def upload(path, fname, data):
    b = uuid.uuid4().hex
    body = (f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{fname}\"\r\nContent-Type: text/csv\r\n\r\n").encode() + data + f"\r\n--{b}--\r\n".encode()
    req = urllib.request.Request(BASE + path, data=body, method="POST", headers={"content-type": f"multipart/form-data; boundary={b}"})
    with urllib.request.urlopen(req, timeout=60) as r: return json.loads(r.read())

def check(cond, msg):
    print(("PASS: " if cond else "FAIL: ") + msg)
    if not cond: sys.exit(1)

def bal():
    tb = call("GET", "/api/ledger/trial-balance")
    return tb["balanced"], {a["account"]: a["debit_paise"] - a["credit_paise"] for a in tb["accounts"]}

call("PUT", "/api/company", exp["company"])
def seed(inv):
    r = call("POST", "/api/invoices", inv); c = call("POST", f"/api/invoices/{r['id']}/confirm", {"acknowledgeWarnings": True})
    check(c.get("status") == "CONFIRMED", f"seeded {inv['invoice_no']}"); return r["id"]
sale = seed({"direction": "SALE", "invoice_no": "ACW/9/1", "invoice_date": "2026-10-05", "counterparty_name": "Bluepeak Retail Private Limited", "counterparty_gstin": exp["invoices"][0]["counterparty_gstin"],
  "place_of_supply": "29", "supply_type": "B2B", "reverse_charge": 0, "taxable_paise": 12000000, "cgst_paise": 1080000, "sgst_paise": 1080000, "total_paise": 14160000, "category": "Income: Software services",
  "lines": [{"description": "SaaS", "hsn_sac": "998314", "gst_rate": "18", "taxable_paise": 12000000, "cgst_paise": 1080000, "sgst_paise": 1080000}]})
pur = seed({"direction": "PURCHASE", "invoice_no": "NHS/9/1", "invoice_date": "2026-10-08", "counterparty_name": "Nimbus Hosting Solutions Private Limited", "counterparty_gstin": exp["invoices"][2]["counterparty_gstin"],
  "place_of_supply": "29", "supply_type": "DOMESTIC", "reverse_charge": 0, "itc_eligible": 1, "taxable_paise": 10000000, "igst_paise": 1800000, "total_paise": 11800000, "category": "Expense: Professional fees",
  "lines": [{"description": "Consulting", "hsn_sac": "998313", "gst_rate": "18", "taxable_paise": 10000000, "igst_paise": 1800000}]})
call("POST", "/api/tds/assign", {"invoiceId": pur, "section": "194J_PROF"})
csv = "Date,Narration,Debit,Credit,Balance\n12/10/2026,NEFT CR BLUEPEAK RETAIL,,129600.00,129600.00\n15/10/2026,NEFT DR NIMBUS HOSTING,108000.00,,21600.00\n"
upload("/api/bank/import?account=HDFC", "s.csv", csv.encode())
txns = call("GET", "/api/bank/txns")
rc = [t for t in txns if "BLUEPEAK" in t["description"]][0]["id"]; pd = [t for t in txns if "NIMBUS" in t["description"]][0]["id"]
r = call("POST", f"/api/bank/txns/{rc}/match", {"invoiceId": sale, "customerTdsPaise": 1200000}); check(r.get("status") == "MATCHED", "receipt matched with customer TDS")
r = call("POST", f"/api/bank/txns/{pd}/match", {"invoiceId": pur}); check(r.get("status") == "MATCHED", "payment matched")
ok, b = bal()
check(ok, "trial balance balances")
check(b.get("Bank: HDFC") == 2160000, "bank balance = receipts - payments")
check(b.get("Receivable: Bluepeak Retail Private Limited") == 0, "net-of-TDS receipt clears the receivable")
check(b.get("Payable: Nimbus Hosting Solutions Private Limited") == 0, "TDS + payment clear the vendor payable")
check(b.get("TDS receivable (deducted by customers)") == 1200000, "customer TDS booked as receivable")
check(b.get("TDS payable: 194J_PROF") == -1000000, "TDS payable booked on deduction")
reg = call("GET", "/api/tds/register?fy=2026-27")["rows"]
call("POST", f"/api/tds/{reg[0]['id']}/deposit", {"challan_no": "99", "bsr_code": "0510308", "deposited_on": "2026-11-05"})
check(bal()[1].get("TDS payable: 194J_PROF") == 0, "TDS deposit clears TDS payable")
up = "employee_id,name,pan,month,gross\nE1,Asha Rao,ABCPR1234D,2026-10,500000\n"
upload("/api/payroll/import", "p.csv", up.encode())
line = call("GET", "/api/payroll?fy=2026-27")["lines"][0]; tds = line["tds_paise"]
ok, b = bal()
check(ok and b["Expense: Salaries"] == 50000000 and b["TDS payable: 192 Salary"] == -tds and b["Salary payable"] == -(50000000 - tds), "payroll: expense / TDS payable / net pay")
n1 = len(call("GET", "/api/ledger/journal")); a1 = len(call("GET", "/api/audit?entity=journal&limit=1000"))
call("POST", "/api/bank/rematch"); upload("/api/payroll/import", "p.csv", up.encode())
check(len(call("GET", "/api/ledger/journal")) == n1 and len(call("GET", "/api/audit?entity=journal&limit=1000")) == a1 and a1 > 0, "re-running is idempotent (no duplicate journals or audit rows)")
call("POST", f"/api/bank/txns/{pd}/unmatch")
ok, b = bal()
check(ok and len(call("GET", "/api/ledger/journal")) == n1 - 1, "unmatch removes only that journal; still balanced")
print("P9 ledger flow OK")
