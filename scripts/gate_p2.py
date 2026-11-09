"""P2 gate body: seed the fixture month via manual entry (no LLM), assert 3B/GSTR-1 figures and exports, period lock."""
import io, json, sys, urllib.request, zipfile, os

BASE = sys.argv[1]
exp = json.load(open(os.path.join(os.path.dirname(__file__), "..", "fixtures", "invoices", "expected.json")))

def call(method, path, body=None, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"content-type": "application/json"} if data else {})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            b = r.read()
            return b if raw else json.loads(b or b"null")
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

g3 = call("GET", "/api/gst/3b?period=2026-10")
f = {x["key"]: x for t in ("table31", "table4") for x in g3[t]}
check(f["3.1a"]["taxable_paise"] == 12000000 and f["3.1a"]["cgst_paise"] == 1080000, "3.1(a) = 1,20,000 taxable, CGST 10,800")
check(f["3.1b"]["taxable_paise"] == 33600000, "3.1(b) zero-rated = 3,36,000")
check(f["3.1d"]["igst_paise"] == 1814400, "3.1(d) RCM IGST = 18,144")
check(f["4C"]["igst_paise"] == 2714400, "4C net IGST ITC = 27,144")
check(len(f["3.1a"]["sources"]) == 1 and f["3.1a"]["sources"][0]["invoice_no"] == "ACW/25-26/041" and "Sec 9" in f["3.1a"]["ref"], "3.1(a) explains its rule + source invoice")
check(g3["payment61"]["cashTotalPaise"] == 1814400, "6.1 cash payable = 18,144 (RCM only; forward tax covered by IGST ITC)")

g1 = call("GET", "/api/gst/1/portal.json?period=2026-10")
check(g1["fp"] == "102026" and g1["gstin"] == exp["company"]["gstin"], "GSTR-1 JSON header")
check(g1["b2b"][0]["inv"][0]["inum"] == "ACW/25-26/041" and g1["exp"][0]["exp_typ"] == "WOPAY", "GSTR-1 JSON b2b + exp WOPAY")

x = call("GET", "/api/gst/workings.xlsx?period=2026-10", raw=True)
names = zipfile.ZipFile(io.BytesIO(x)).namelist()
check(any("worksheets/sheet" in n for n in names), "Excel workbook is a valid xlsx")

z = zipfile.ZipFile(io.BytesIO(call("GET", "/api/gst/ca-pack.zip?period=2026-10", raw=True))).namelist()
for need in ["README.txt", "gstr3b-computation.json", "gstr1-portal.json", "gst-workings.xlsx", "invoice-register.csv", "journal.csv", "trial-balance.csv"]:
    check(need in z, f"CA pack has {need}")

lock = call("POST", "/api/filings", {"return_type": "GSTR3B", "period": "2026-10", "arn": "AA291026123456X", "filed_on": "2026-11-20"})
check(lock.get("locked") == 1, "filing recorded, period locked")
r = call("POST", f"/api/invoices/{ids[0]}/reopen")
check(r.get("_status") == 409, "locked period rejects reopen (409)")
r = call("POST", f"/api/filings/{lock['id']}/unlock", {"reason": "CA asked to amend"})
check(r.get("locked") == 0, "unlock with reason works")
a = call("GET", "/api/audit?entity=filing")
check(any(e["action"] == "unlock" and e["note"] == "CA asked to amend" for e in a), "unlock is audited")
