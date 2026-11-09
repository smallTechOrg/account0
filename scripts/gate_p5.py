"""P5 gate body: import a bank statement CSV, check auto-match / suggestions / TDS-net receipt, manual actions, outstanding, balance check."""
import json, sys, urllib.request, os, uuid

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
    try:
        with urllib.request.urlopen(req, timeout=60) as r: return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return {"_status": e.code, **json.loads(e.read() or b"{}")}

def check(cond, msg):
    print(("PASS: " if cond else "FAIL: ") + msg)
    if not cond: sys.exit(1)

call("PUT", "/api/company", exp["company"])
seed = [
  {"direction": "SALE", "invoice_no": "ACW/25-26/041", "invoice_date": "2026-10-05", "counterparty_name": "Bluepeak Retail Private Limited", "counterparty_gstin": exp["invoices"][0]["counterparty_gstin"],
   "place_of_supply": "29", "supply_type": "B2B", "reverse_charge": 0, "taxable_paise": 12000000, "cgst_paise": 1080000, "sgst_paise": 1080000, "total_paise": 14160000, "category": "Income: Software services",
   "lines": [{"description": "SaaS", "hsn_sac": "998314", "gst_rate": "18", "taxable_paise": 10000000, "cgst_paise": 900000, "sgst_paise": 900000},
             {"description": "Implementation", "hsn_sac": "998313", "gst_rate": "18", "taxable_paise": 2000000, "cgst_paise": 180000, "sgst_paise": 180000}]},
  {"direction": "SALE", "invoice_no": "ACW/25-26/042", "invoice_date": "2026-10-12", "counterparty_name": "Northwind Labs LLC", "counterparty_country": "USA", "place_of_supply": "96",
   "supply_type": "EXPORT_LUT", "reverse_charge": 0, "currency": "USD", "foreign_amount": "4000.00", "fx_rate": "84", "taxable_paise": 33600000, "total_paise": 33600000, "category": "Income: Export of services",
   "lines": [{"description": "Dev services", "hsn_sac": "998314", "gst_rate": "0", "taxable_paise": 33600000}]},
  {"direction": "PURCHASE", "invoice_no": "NHS/2026/1187", "invoice_date": "2026-10-08", "counterparty_name": "Nimbus Hosting Solutions Private Limited", "counterparty_gstin": exp["invoices"][2]["counterparty_gstin"],
   "place_of_supply": "29", "supply_type": "DOMESTIC", "reverse_charge": 0, "itc_eligible": 1, "taxable_paise": 5000000, "igst_paise": 900000, "total_paise": 5900000, "category": "Expense: Cloud hosting",
   "lines": [{"description": "Hosting", "hsn_sac": "998315", "gst_rate": "18", "taxable_paise": 5000000, "igst_paise": 900000}]},
  {"direction": "PURCHASE", "invoice_no": "INV-2026-10-0091", "invoice_date": "2026-10-01", "counterparty_name": "Atlas Analytics, Inc.", "counterparty_country": "USA", "place_of_supply": "29",
   "supply_type": "IMPORT_SERVICE_RCM", "reverse_charge": 1, "itc_eligible": 1, "taxable_paise": 10080000, "total_paise": 10080000, "category": "Expense: Software subscriptions",
   "lines": [{"description": "Seats", "hsn_sac": "998316", "gst_rate": "18", "taxable_paise": 10080000}]},
]
ids = []
for s in seed:
    r = call("POST", "/api/invoices", s)
    check("id" in r, f"seeded {s['invoice_no']}")
    if s["reverse_charge"]: call("POST", f"/api/invoices/{r['id']}/compute-rcm")
    c = call("POST", f"/api/invoices/{r['id']}/confirm", {"acknowledgeWarnings": True})
    check(c.get("status") == "CONFIRMED", f"confirmed {s['invoice_no']} {c.get('error','')}")
    ids.append(r["id"])


csv = open(os.path.join(ROOT, "fixtures", "bank", "hdfc-style-oct-2026.csv"), "rb").read()
pv = upload("/api/bank/preview", "stmt.csv", csv)
check(pv["mapping"]["debit"] == 4 and pv["mapping"]["credit"] == 5 and pv["mapping"]["date"] == 0, "column mapping auto-detected from HDFC-style headers")
imp = upload("/api/bank/import?account=HDFC", "stmt.csv", csv)
check(imp["new"] == 7 and imp["matching"]["autoMatched"] == 2 and imp["matching"]["suggested"] == 2, f"7 lines imported, 2 auto-matched, 2 suggested: {imp.get('matching')}")
check(imp["balanceCheck"]["ok"], "running balance continuity verified (no missing rows)")
again = upload("/api/bank/import?account=HDFC", "stmt.csv", csv)
check(again["new"] == 0 and again["duplicates"] == 7, "re-importing the same statement adds nothing")
tx = call("GET", "/api/bank/txns")
by = {t["ref"]: t for t in tx}
check(by["N2610100001"]["status"] == "MATCHED" and by["N2610100001"]["invoice_no"] == "NHS/2026/1187", "Nimbus payment auto-matched to NHS/2026/1187")
check(by["N2610120077"]["status"] == "MATCHED" and "TDS" in by["N2610120077"]["match_note"], "Bluepeak receipt (net of ₹12,000) auto-matched with a TDS note")
check(by["FIRC20261020"]["status"] == "SUGGESTED" and by["FIRC20261020"]["invoice_no"] == "ACW/25-26/042", "Northwind FIRC (₹800 short) is only a SUGGESTION")
check(by["CARD4242"]["status"] == "SUGGESTED" and by["CARD4242"]["invoice_no"] == "INV-2026-10-0091", "Atlas card payment suggested")
check(call("POST", f"/api/bank/txns/{by['FIRC20261020']['id']}/match", {"invoiceId": by['FIRC20261020']['matched_invoice_id']})["status"] == "MATCHED", "user accepts suggestion")
check(call("POST", f"/api/bank/txns/{by['CHG1025']['id']}/ignore", {"reason": ""}).get("_status") == 400, "ignore needs a reason")
check(call("POST", f"/api/bank/txns/{by['CHG1025']['id']}/ignore", {"reason": "bank charges"})["status"] == "IGNORED", "bank charges ignored with reason")
cand = call("GET", f"/api/bank/txns/{by['SAL1003']['id']}/candidates")
check(isinstance(cand, list), "candidate list endpoint works for an unmatched salary debit")
s = call("GET", "/api/bank/summary")
out = {o["invoiceNo"]: o for o in s["outstanding"]}
check(out["ACW/25-26/041"]["outstandingPaise"] == 1200000, "Bluepeak invoice still shows ₹12,000 outstanding (TDS withheld by customer — ask for Form 16A)")
check(out["ACW/25-26/042"]["outstandingPaise"] == 80000, "Northwind invoice shows ₹800 shortfall (bank/forex charges?)")
check("NHS/2026/1187" not in out, "Nimbus invoice fully settled")
bad = upload("/api/bank/preview", "x.csv", b"foo,bar\n1,2\n")
check(bad.get("_status") == 400, "unrecognisable file rejected with 400")
al = call("GET", "/api/alerts")
check(any(a["code"] == "BANK_UNRECONCILED" for a in al), "alert: unreconciled bank lines remain")
print("P5 flow OK")
