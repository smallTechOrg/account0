"""P3 gate body: CA scrutiny (real Claude PDF transcription + portal JSON), GSTR-2B import/match, alerts."""
import json, sys, urllib.request, os, uuid

BASE = sys.argv[1]
ROOT = os.path.join(os.path.dirname(__file__), "..")
exp = json.load(open(os.path.join(ROOT, "fixtures", "invoices", "expected.json")))

def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"content-type": "application/json"} if data else {})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return {"_status": e.code, **json.loads(e.read() or b"{}")}

def upload(path, fname, data):
    b = uuid.uuid4().hex
    body = (f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{fname}\"\r\nContent-Type: application/octet-stream\r\n\r\n").encode() + data + f"\r\n--{b}--\r\n".encode()
    req = urllib.request.Request(BASE + path, data=body, method="POST", headers={"content-type": f"multipart/form-data; boundary={b}"})
    try:
        with urllib.request.urlopen(req, timeout=180) as r: return json.loads(r.read())
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


VENDOR = exp["invoices"][2]["counterparty_gstin"]
# 1. CA's filed 3B as PDF -> ONE real Claude call; figures come back UNCONFIRMED
pdf = open(os.path.join(ROOT, "fixtures", "returns", "gstr3b-2026-10-filed.pdf"), "rb").read()
rep = upload("/api/scrutiny/upload", "gstr3b-2026-10-filed.pdf", pdf)
check(rep.get("filedReturn", {}).get("period") == "2026-10", "PDF period read as 2026-10: " + str(rep.get("error", "")))
check(rep["filedReturn"]["source"] == "PDF_LLM" and rep.get("unconfirmedNotice"), "PDF figures flagged unconfirmed")
rows = {(r["key"], r["head"]): r for r in rep["rows"]}
check(rows[("3.1a", "cgst")]["filed"] == 1080000 and rows[("3.1a", "cgst")]["status"] == "MATCH", "3.1(a) CGST transcribed 10,800 and matches books")
check(rows[("3.1b", "taxable")]["filed"] == 33600000 and rows[("3.1b", "taxable")]["status"] == "MATCH", "3.1(b) transcribed 3,36,000 and matches")
check(rows[("3.1d", "igst")]["filed"] == 0 and rows[("3.1d", "igst")]["books"] == 1814400 and rows[("3.1d", "igst")]["status"] == "DIFF", "3.1(d) RCM omitted by CA -> DIFF vs books 18,144")
check(rows[("4A2", "igst")]["books"] == 1814400 and rows[("4A2", "igst")]["filed"] == 0, "4A(2) import-of-services ITC omitted by CA")
check(any("Sec 5(3) IGST Act" in q["question"] for q in rep["questions"]), "question to CA cites Sec 5(3) IGST Act for RCM")
check(rep["mismatches"] >= 2, f"{rep['mismatches']} mismatching figures")
conf = call("POST", f"/api/scrutiny/{rep['filedReturn']['id']}/confirm")
check(conf["filedReturn"]["confirmed"] == 1 and "unconfirmedNotice" not in conf, "user confirmation clears the notice")
alerts = call("GET", "/api/alerts")
check(any(a["code"] == "RCM_UNDERPAID" for a in alerts), "alert RCM_UNDERPAID raised from filed 3B")

# 2. Portal-JSON route (a corrected return) -> no RCM diff
j = {"gstin": exp["company"]["gstin"], "ret_period": "102026", "sup_details": {"osup_det": {"txval": 120000, "iamt": 0, "camt": 10800, "samt": 10800, "csamt": 0}, "osup_zero": {"txval": 336000, "iamt": 0, "csamt": 0},
     "osup_nil_exmp": {"txval": 0}, "isup_rev": {"txval": 100800, "iamt": 18144, "camt": 0, "samt": 0, "csamt": 0}, "osup_nongst": {"txval": 0}},
     "itc_elg": {"itc_avl": [{"ty": "IMPG", "iamt": 0, "camt": 0, "samt": 0, "csamt": 0}, {"ty": "IMPS", "iamt": 18144, "camt": 0, "samt": 0, "csamt": 0}, {"ty": "ISRC", "iamt": 0, "camt": 0, "samt": 0, "csamt": 0}, {"ty": "OTH", "iamt": 9000, "camt": 0, "samt": 0, "csamt": 0}],
     "itc_rev": [{"ty": "RUL", "iamt": 0, "camt": 0, "samt": 0, "csamt": 0}], "itc_net": {"iamt": 27144, "camt": 0, "samt": 0, "csamt": 0}}}
rep2 = upload("/api/scrutiny/upload", "GSTR3B_102026.json", json.dumps(j).encode())
check(rep2.get("mismatches") == 0 and rep2["questions"] == [], "corrected portal JSON: zero differences, no questions: " + str(rep2.get("error", rep2.get("mismatches"))))
check(not any(a["code"] == "RCM_UNDERPAID" for a in call("GET", "/api/alerts")), "RCM alert cleared once corrected 3B is the latest")

# 3. GSTR-2B: Nimbus present (invoice no formatted differently), plus an unknown invoice
b2 = {"data": {"gstin": exp["company"]["gstin"], "rtnprd": "102026", "docdata": {"b2b": [
    {"ctin": VENDOR, "trdnm": "Nimbus Hosting Solutions", "supfildt": "11-11-2026", "supprd": "102026", "inv": [{"inum": "NHS-2026-1187", "dt": "08-10-2026", "val": 59000, "rev": "N", "itcavl": "Y", "txval": 50000, "igst": 9000, "cgst": 0, "sgst": 0, "cess": 0}]},
    {"ctin": "27AAACS0000A1Z5", "trdnm": "Unknown Traders", "inv": [{"inum": "UT-77", "dt": "10-10-2026", "val": 1180, "rev": "N", "itcavl": "Y", "txval": 1000, "igst": 180, "cgst": 0, "sgst": 0, "cess": 0}]}]}}}
imp = upload("/api/itc/2b/upload", "2b.json", json.dumps(b2).encode())
check(imp.get("documents") == 2 and imp["period"] == "2026-10", "2B imported: 2 documents")
m = call("GET", "/api/itc/match?period=2026-10")
st = {r["status"] for r in m["rows"]}
check(st == {"MATCHED", "IN_2B_NOT_IN_BOOKS"}, f"ITC match statuses {st}")
check(any(r["status"] == "MATCHED" and r["invoiceNo"] == "NHS/2026/1187" for r in m["rows"]), "NHS/2026/1187 matched to NHS-2026-1187 after normalisation")
bad = upload("/api/itc/2b/upload", "x.json", b'{"foo": 1}')
check(bad.get("_status") == 400, "non-2B JSON rejected with 400")
alerts = call("GET", "/api/alerts")
check(any(a["code"] == "IN_2B_NOT_IN_BOOKS" for a in alerts), "alert: invoice in 2B not in books")
# 4. Duplicate alert + vendor-not-filed alert via a second purchase absent from 2B
extra = {"direction": "PURCHASE", "invoice_no": "NHS/2026/1187", "invoice_date": "2026-10-09", "counterparty_name": "Nimbus Hosting Solutions Private Limited", "counterparty_gstin": VENDOR,
   "place_of_supply": "29", "supply_type": "DOMESTIC", "reverse_charge": 0, "itc_eligible": 1, "taxable_paise": 5000000, "igst_paise": 900000, "total_paise": 5900000, "category": "Expense: Cloud hosting",
   "lines": [{"description": "Hosting", "hsn_sac": "998315", "gst_rate": "18", "taxable_paise": 5000000, "igst_paise": 900000}]}
r = call("POST", "/api/invoices", extra)
check(any(a["code"] == "DUPLICATE_INVOICE" for a in call("GET", "/api/alerts")), "alert: duplicate invoice number from same vendor")
extra2 = dict(extra, invoice_no="NHS/2026/2000")
r2 = call("POST", "/api/invoices", extra2); c = call("POST", f"/api/invoices/{r2['id']}/confirm", {"acknowledgeWarnings": True})
al = call("GET", "/api/alerts")
check(any(a["code"] == "VENDOR_NOT_FILED" and "NHS/2026/2000" in a["message"] for a in al), "alert: vendor not filed (ITC at risk) for invoice absent from 2B")
print("P3 flow OK")
