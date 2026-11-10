"""P10 gate body (no LLM): fixes from the independent tax-correctness audit, asserted through the API."""
import json, sys, urllib.request, urllib.error, uuid, os

BASE = sys.argv[1]
ROOT = os.path.join(os.path.dirname(__file__), "..")
exp = json.load(open(os.path.join(ROOT, "fixtures", "invoices", "expected.json")))
BUYER, VENDOR = exp["invoices"][0]["counterparty_gstin"], exp["invoices"][2]["counterparty_gstin"]   # 29 (intra) / 27 (inter)

def call(method, path, body=None, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"content-type": "application/json"} if data else {})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            b = r.read(); return b.decode() if raw else json.loads(b or b"null")
    except urllib.error.HTTPError as e:
        return {"_status": e.code, **json.loads(e.read() or b"{}")}

def upload(path, fname, data):
    b = uuid.uuid4().hex
    body = (f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{fname}\"\r\nContent-Type: application/json\r\n\r\n").encode() + data + f"\r\n--{b}--\r\n".encode()
    req = urllib.request.Request(BASE + path, data=body, method="POST", headers={"content-type": f"multipart/form-data; boundary={b}"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r: return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return {"_status": e.code, **json.loads(e.read() or b"{}")}

def check(cond, msg):
    print(("PASS: " if cond else "FAIL: ") + msg)
    if not cond: sys.exit(1)

def confirmed(inv, extra=None):
    r = call("POST", "/api/invoices", inv)
    check("id" in r, f"created {inv['invoice_no']} {r.get('error', '')}")
    if extra: extra(r["id"])
    c = call("POST", f"/api/invoices/{r['id']}/confirm", {"acknowledgeWarnings": True})
    check(c.get("status") == "CONFIRMED", f"confirmed {inv['invoice_no']} {c.get('error', '')}")
    return c

def fig(g, table, key): return [f for f in g[table] if f["key"] == key][0]

call("PUT", "/api/company", exp["company"])

# ---- #1 set-off (Rule 88A + Circular 98/17/2019) and #2 4A(5) limited to 2B lines ----
confirmed({"direction": "SALE", "invoice_no": "ACW/26-27/101", "invoice_date": "2026-10-05", "counterparty_name": "Bluepeak Retail", "counterparty_gstin": BUYER, "place_of_supply": "29",
           "supply_type": "B2B", "reverse_charge": 0, "taxable_paise": 1000000, "cgst_paise": 90000, "sgst_paise": 90000, "total_paise": 1180000, "category": "Income",
           "lines": [{"description": "SaaS", "hsn_sac": "998314", "gst_rate": "18", "taxable_paise": 1000000, "cgst_paise": 90000, "sgst_paise": 90000}]})
for no, igst in (("NHS/2026/501", 90000), ("NHS/2026/502", 18000)):
    confirmed({"direction": "PURCHASE", "invoice_no": no, "invoice_date": "2026-10-08", "counterparty_name": "Nimbus Hosting", "counterparty_gstin": VENDOR, "place_of_supply": "29",
               "supply_type": "DOMESTIC", "reverse_charge": 0, "itc_eligible": 1, "taxable_paise": igst * 100 // 18, "igst_paise": igst, "total_paise": igst * 100 // 18 + igst, "category": "Hosting",
               "lines": [{"description": "Hosting", "hsn_sac": "998315", "gst_rate": "18", "taxable_paise": igst * 100 // 18, "igst_paise": igst}]})
g = call("GET", "/api/gst/3b?period=2026-10")
check(fig(g, "table4", "4A5")["igst_paise"] == 108000 and fig(g, "table4", "4A5").get("status") == "PROVISIONAL" and g["itcProvisional"], "no 2B: 4A(5) = books (1,080) marked PROVISIONAL")
b2 = {"data": {"gstin": exp["company"]["gstin"], "rtnprd": "102026", "docdata": {
    "b2b": [{"ctin": VENDOR, "trdnm": "Nimbus Hosting", "inv": [{"inum": "NHS-2026-501", "dt": "08-10-2026", "rev": "N", "itcavl": "Y", "txval": 5000, "igst": 900, "cgst": 0, "sgst": 0, "cess": 0}]}],
    "isd": [{"ctin": "29AAKCA1234B2Z3", "trdnm": "Acme HQ (ISD)", "doclist": [{"doctyp": "ISDI", "docnum": "ISD/7", "docdt": "05-10-2026", "itcelg": "Y", "igst": 0, "cgst": 900, "sgst": 0, "cess": 0}]}]}}}
imp = upload("/api/itc/2b/upload", "2b.json", json.dumps(b2).encode())
check(imp.get("documents") == 2, f"2B imported with an ISD line: {imp}")
g = call("GET", "/api/gst/3b?period=2026-10")
a5 = fig(g, "table4", "4A5")
check(a5["igst_paise"] == 90000 and "status" not in a5, "with 2B: 4A(5) = only the matched 2B line (900), NHS/2026/502 left out")
check(any("NHS/2026/502" in n for n in g["notes"]), "note explains the purchase missing from 2B")
check(fig(g, "table4", "4A4")["cgst_paise"] == 90000, "ISD credit (CGST 900) in 4A(4)")
heads = {h["head"]: h for h in g["payment61"]["heads"]}
# credits: IGST 900, CGST 900, SGST 0; liability CGST 900 + SGST 900 -> IGST credit to SGST, CGST credit to CGST, cash 0 (old fixed order paid SGST 900 in cash)
check(heads["SGST"]["paidByIgstItcPaise"] == 90000 and heads["CGST"]["paidByCgstItcPaise"] == 90000, "IGST credit routed to SGST, CGST credit to CGST")
check(g["payment61"]["cashTotalPaise"] == 0, "set-off example: cash payable 0")

# ---- #11 RCM time of supply ----
atlas = {"direction": "PURCHASE", "invoice_no": "INV-2026-10-0091", "invoice_date": "2026-10-01", "counterparty_name": "Atlas Analytics, Inc.", "counterparty_country": "USA", "place_of_supply": "29",
         "supply_type": "IMPORT_SERVICE_RCM", "reverse_charge": 1, "itc_eligible": 1, "taxable_paise": 10080000, "total_paise": 10080000, "category": "Software",
         "lines": [{"description": "Seats", "hsn_sac": "998316", "gst_rate": "18", "taxable_paise": 10080000}]}
c = confirmed(atlas, lambda i: call("POST", f"/api/invoices/{i}/compute-rcm"))
check(c["rcm_tos_date"] == "2026-12-01" and any(f["code"] == "RCM_TOS_UNPAID" for f in c["checks"]), "unpaid RCM: time of supply = invoice + 61 days (1 Dec 2026), flagged")
check(fig(call("GET", "/api/gst/3b?period=2026-10"), "table31", "3.1d")["igst_paise"] == 0, "unpaid RCM is not in October 3.1(d)")
check(fig(call("GET", "/api/gst/3b?period=2026-12"), "table31", "3.1d")["igst_paise"] == 1814400, "unpaid RCM is in December 3.1(d) (18,144)")

# ---- #3 194I monthly threshold, #4 director fees ----
def unreg(no, date, name, rupees):
    return confirmed({"direction": "PURCHASE", "invoice_no": no, "invoice_date": date, "counterparty_name": name, "place_of_supply": "29", "supply_type": "UNREGISTERED", "reverse_charge": 0,
                      "itc_eligible": 0, "taxable_paise": rupees * 100, "total_paise": rupees * 100, "category": "Rent", "lines": [{"description": "x", "hsn_sac": "997212", "gst_rate": "0", "taxable_paise": rupees * 100}]})["id"]
r1 = call("POST", "/api/tds/assign", {"invoiceId": unreg("RENT/05", "2026-05-01", "Landlord One", 60000), "section": "194I_LB", "pan": "ABCPL1234K"})
check(r1["tds_paise"] == 600000 and r1["status"] == "DEDUCT", "rent ₹60,000 in one month triggers 194I: 10% = ₹6,000")
r2 = call("POST", "/api/tds/assign", {"invoiceId": unreg("RENT/06", "2026-06-01", "Landlord Two", 45000), "section": "194I_LB", "pan": "ABCPM1234K"})
check(r2["tds_paise"] == 0, "rent ₹45,000 for a month: below ₹50,000 per month")
r3 = call("POST", "/api/tds/assign", {"invoiceId": unreg("DIR/1", "2026-07-01", "R. Director", 10000), "section": "194J_DIR", "pan": "ABCPD1234K"})
check(r3["tds_paise"] == 100000, "director sitting fee ₹10,000: 10% from the first rupee (no threshold)")
secs = {s["code"]: s for s in call("GET", "/api/tds/sections")}
check(secs["194Q"]["noPanRateBp"] == 500 and secs["194C"]["noPanRateBp"] == 2000, "no-PAN rate 5% for 194Q, 20% otherwise")
check(call("GET", "/api/tds/forms?fy=2026-27")["nonSalaryReturn"].startswith("Form 140") and call("GET", "/api/tds/forms?fy=2025-26")["nonSalaryReturn"] == "Form 26Q", "FY-aware TDS form names")

# ---- #10 DIR-3 KYC, #12/#13 calendar ----
call("PUT", "/api/compliance/directors", [{"name": "A Director", "din": "01234567", "lastKycFy": "2023-24"}, {"name": "B Director", "din": "07654321", "lastKycFy": "2025-26"}])
cal = call("GET", "/api/compliance/calendar?fy=2026-27&asOf=2026-11-10")
I = {(i["code"], i["period"]): i for i in cal["items"]}
check(I[("DIR3KYC", "FY2026-27-01234567")]["due"] == "2026-06-30", "DIR-3 KYC due 30 Jun 2026 for the director last filed FY 2023-24 (3-year cycle)")
check(("DIR3KYC", "FY2026-27-07654321") not in I, "no DIR-3 KYC this year for the director who filed in FY 2025-26")
check(I[("TDS_26Q", "2025-26-Q4")]["form"] == "Form 26Q" and I[("TDS_26Q", "2026-27-Q2")]["form"].startswith("Form 140"), "Q4 FY 2025-26 keeps 26Q; FY 2026-27 is Form 140")
check(I[("LUT_RFD11", "FY2027-28")]["due"] == "2027-03-31" and I[("GSTR9C", "FY2025-26")]["due"] == "2026-12-31" and ("MSME1", "FY2026-27-H1") in I and ("TDS_27Q", "2026-27-Q1") in I, "new items: LUT, GSTR-9C, MSME-1, Form 144")
check(any(i["code"] == "FORM15CA" for i in cal["items"]), "15CA/15CB event for the foreign vendor invoice")

# ---- #8 Table 13 numeric sort, #18 HSN blocker ----
for no in ("INV-9", "INV-10"):
    confirmed({"direction": "SALE", "invoice_no": no, "invoice_date": "2026-09-10", "counterparty_name": "Bluepeak Retail", "counterparty_gstin": BUYER, "place_of_supply": "29", "supply_type": "B2B",
               "reverse_charge": 0, "taxable_paise": 100000, "cgst_paise": 9000, "sgst_paise": 9000, "total_paise": 118000, "category": "Income",
               "lines": [{"description": "SaaS", "hsn_sac": "998314", "gst_rate": "18", "taxable_paise": 100000, "cgst_paise": 9000, "sgst_paise": 9000}]})
g1 = call("GET", "/api/gst/1?period=2026-09")
s0 = g1["documentsIssued"]["series"][0]
check(s0["from"] == "INV-9" and s0["to"] == "INV-10" and s0["doc_num"] == 1, "Table 13 sorts INV-9 before INV-10 (numeric), invoices are doc_num 1")
check(json.loads(call("GET", "/api/gst/1/portal.json?period=2026-09", raw=True))["doc_issue"]["doc_det"][0]["doc_num"] == 1, "portal JSON exports with valid HSN")
confirmed({"direction": "SALE", "invoice_no": "INV-11", "invoice_date": "2026-09-12", "counterparty_name": "Bluepeak Retail", "counterparty_gstin": BUYER, "place_of_supply": "29", "supply_type": "B2B",
           "reverse_charge": 0, "taxable_paise": 100000, "cgst_paise": 9000, "sgst_paise": 9000, "total_paise": 118000, "category": "Income"})
blocked = call("GET", "/api/gst/1/portal.json?period=2026-09")
check(blocked.get("_status") == 400 and "HSN" in blocked.get("error", ""), "GSTR-1 JSON blocked while an HSN/SAC is missing")
print("P10 flow OK")
