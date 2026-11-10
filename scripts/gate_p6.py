"""P6 gate body: compliance calendar (hand-checked dates, auto status from filings, AGM-driven ROC dates, catch-up) + month-close checklist."""
import json, sys, urllib.request, os

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
   "supply_type": "IMPORT_SERVICE_RCM", "reverse_charge": 1, "rcm_paid_on": "2026-10-01", "itc_eligible": 1, "taxable_paise": 10080000, "total_paise": 10080000, "category": "Expense: Software subscriptions",
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


AS_OF = "2026-11-10"
cal = call("GET", f"/api/compliance/calendar?fy=2026-27&asOf={AS_OF}")
I = {(i["code"], i["period"]): i for i in cal["items"]}
check(len(cal["items"]) == 94, "FY 2026-27 calendar has 94 obligations (85 + Form 144 x4, GSTR-9C, LUT, MSME-1 x2 + one 15CA event for the US vendor)")
check(I[("GSTR3B", "2026-10")]["due"] == "2026-11-20" and I[("GSTR1", "2026-10")]["due"] == "2026-11-11", "GSTR-1 on the 11th, GSTR-3B on the 20th")
check(I[("TDS_DEPOSIT", "2026-03")]["due"] == "2026-04-30" and I[("TDS_DEPOSIT", "2026-09")]["due"] == "2026-10-07", "TDS deposit 7th (30 April for March)")
check(I[("TDS_26Q", "2026-27-Q2")]["due"] == "2026-10-31" and I[("ITR6", "FY2025-26")]["due"] == "2026-10-31", "26Q Q2 and ITR-6 on 31 Oct")
check(I[("AOC4", "FY2025-26")]["due"] == "2026-10-30" and I[("MGT7A", "FY2025-26")]["due"] == "2026-11-29", "default AGM 30 Sep -> AOC-4 30 Oct, MGT-7A 29 Nov")
check(I[("GSTR1", "2026-10")]["state"] == "DUE_SOON" and I[("GSTR3B", "2026-09")]["state"] == "OVERDUE" and I[("PF", "2026-10")]["state"] == "NA", "states: due soon / overdue / PF n.a. without employees")
check(call("PUT", "/api/settings/company.agm_date", {"value": "2026-09-15"}) is not None, "AGM date saved")
I2 = {(i["code"], i["period"]): i for i in call("GET", f"/api/compliance/calendar?fy=2026-27&asOf={AS_OF}")["items"]}
check(I2[("AOC4", "FY2025-26")]["due"] == "2026-10-15", "AOC-4 follows the AGM date (15 Sep + 30 d)")
call("POST", "/api/filings", {"return_type": "GSTR3B", "period": "2026-09", "arn": "AA2909260001", "filed_on": "2026-10-19", "filed_by": "CA"})
I3 = {(i["code"], i["period"]): i for i in call("GET", f"/api/compliance/calendar?fy=2026-27&asOf={AS_OF}")["items"]}
check(I3[("GSTR3B", "2026-09")]["state"] == "DONE" and I3[("GSTR3B", "2026-09")]["filingRef"] == "AA2909260001", "recording a filing ticks the calendar automatically (with ARN)")
check(call("POST", "/api/compliance/mark", {"code": "DPT3", "period": "FY2025-26", "status": "NA"}).get("_status") == 400, "N/A requires a reason")
check(call("POST", "/api/compliance/mark", {"code": "DPT3", "period": "FY2025-26", "status": "NA", "note": "no deposits or loans"})["status"] == "NA", "N/A with reason saved")
cu = call("POST", "/api/compliance/catch-up", {"fy": "2026-27", "before": "2026-11-08"})
check(cu["marked"] > 20, f"catch-up marked {cu['marked']} earlier items done")
al = call("GET", "/api/alerts")
print("alerts:", sorted({a["code"] for a in al}))

# month close
cl = call("GET", "/api/close/2026-10")
check(len(cl["items"]) == 12 and cl["readyToClose"] is False, "12-step checklist, October not ready initially")
K = {i["key"]: i for i in cl["items"]}
check(K["INVOICES_CONFIRMED"]["state"] == "DONE" and K["NO_DUPLICATES"]["state"] == "DONE" and K["GST_CLEAN"]["state"] in ("DONE", "TODO"), "invoice steps reflect the 4 confirmed fixtures")
check(K["TDS_DECIDED"]["state"] == "TODO", "TDS decision pending for the purchases -> TODO")
check(K["BANK_RECONCILED"]["state"] == "TODO" and K["ITC_MATCHED"]["state"] == "TODO" and K["FILED_3B_COMPARED"]["state"] == "TODO", "bank, 2B and filed-3B steps are TODO with no data")
check(all(i["why"] for i in cl["items"]), "every step explains why it matters")
check(call("POST", "/api/close/2026-10/tick", {"key": "ITC_MATCHED", "state": "DONE"}).get("_status") == 400, "derived steps cannot be ticked by hand")
cl = call("POST", "/api/close/2026-10/tick", {"key": "CA_PACK_SENT", "state": "DONE"})
cl = call("POST", "/api/close/2026-10/tick", {"key": "PAYROLL", "state": "NA"})
K = {i["key"]: i for i in cl["items"]}
check(K["CA_PACK_SENT"]["state"] == "DONE" and K["PAYROLL"]["state"] == "NA", "manual steps tick")
# resolve TDS for the purchases and record the October filing -> locked step
for p in call("GET", "/api/tds/pending"): call("POST", "/api/tds/assign", {"invoiceId": p["id"], "section": "NONE"})
call("POST", "/api/filings", {"return_type": "GSTR3B", "period": "2026-10", "arn": "AA2910260001", "filed_on": "2026-11-18", "filed_by": "CA"})
K = {i["key"]: i for i in call("GET", "/api/close/2026-10")["items"]}
check(K["TDS_DECIDED"]["state"] == "DONE" and K["PERIOD_LOCKED"]["state"] == "DONE", "TDS decided and period locked -> both DONE")
check(call("GET", "/api/close/2026-13").get("_status") == 400, "bad period rejected")
print("P6 flow OK")
