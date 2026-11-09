"""P4 gate body: TDS decisions -> register -> 26Q CSV; payroll CSV -> Sec 192 -> 24Q CSV; alerts. No LLM."""
import json, sys, urllib.request, os, uuid

BASE = sys.argv[1]
ROOT = os.path.join(os.path.dirname(__file__), "..")
exp = json.load(open(os.path.join(ROOT, "fixtures", "invoices", "expected.json")))

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
    body = (f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{fname}\"\r\nContent-Type: text/csv\r\n\r\n").encode() + data + f"\r\n--{b}--\r\n".encode()
    req = urllib.request.Request(BASE + path, data=body, method="POST", headers={"content-type": f"multipart/form-data; boundary={b}"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r: return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return {"_status": e.code, **json.loads(e.read() or b"{}")}

def check(cond, msg):
    print(("PASS: " if cond else "FAIL: ") + msg)
    if not cond: sys.exit(1)

co = dict(exp["company"]); co["tan"] = "BLRA12345B"
call("PUT", "/api/company", co)
VENDOR = exp["invoices"][2]["counterparty_gstin"]
def purchase(no, date, name, gstin, country, taxable_rupees):
    inv = {"direction": "PURCHASE", "invoice_no": no, "invoice_date": date, "counterparty_name": name, "place_of_supply": "29", "supply_type": "DOMESTIC" if not country else "IMPORT_SERVICE_RCM",
           "reverse_charge": 0 if not country else 1, "itc_eligible": 1, "taxable_paise": taxable_rupees * 100, "total_paise": taxable_rupees * 100, "category": "Expense: Professional fees",
           "lines": [{"description": "Services", "hsn_sac": "998311", "gst_rate": "0" if not country else "18", "taxable_paise": taxable_rupees * 100}]}
    if gstin: inv["counterparty_gstin"] = gstin
    if country: inv["counterparty_country"] = country
    r = call("POST", "/api/invoices", inv)
    if country: call("POST", f"/api/invoices/{r['id']}/compute-rcm")
    c = call("POST", f"/api/invoices/{r['id']}/confirm", {"acknowledgeWarnings": True})
    check(c.get("status") == "CONFIRMED", f"confirmed {no} {c.get('error','')}")
    return r["id"]

a = purchase("CONS/1", "2026-07-10", "Nimbus Hosting Solutions Private Limited", VENDOR, None, 40000)
b = purchase("CONS/2", "2026-08-10", "Nimbus Hosting Solutions Private Limited", VENDOR, None, 20000)
f = purchase("ATL/1", "2026-07-01", "Atlas Analytics, Inc.", None, "USA", 100800)
pend = call("GET", "/api/tds/pending")
check(len(pend) == 3 and all("suggestedSection" not in p for p in pend), "3 purchases pending a TDS decision; no section is pre-guessed")
check(call("POST", "/api/tds/assign", {"invoiceId": f, "section": "194J_TECH"}).get("_status") == 400, "non-resident cannot be given a domestic section (Sec 195 not computed)")
check(call("POST", "/api/tds/assign", {"invoiceId": a, "section": "194X"}).get("_status") == 400, "unknown section rejected")
r1 = call("POST", "/api/tds/assign", {"invoiceId": a, "section": "194J_PROF", "saveRule": True})
check(r1["tds_paise"] == 0 and r1["status"] == "BELOW_THRESHOLD", "₹40,000 under 194J ₹50,000 threshold: no TDS yet")
call("POST", "/api/tds/assign", {"invoiceId": b, "section": "194J_PROF"})
reg = call("GET", "/api/tds/register?fy=2026-27")
check(reg["totalTdsPaise"] == 600000, "after crossing ₹50,000 TDS = 10% of ₹60,000 = ₹6,000 (catch-up on first invoice)")
check(any(t["section"] == "194J_PROF" and t["crossed"] for t in reg["thresholds"]), "threshold table shows crossed")
csv = call("GET", "/api/tds/26q.csv?fy=2026-27&quarter=2", raw=True)
check(csv.startswith("TAN,BLRA12345B") and "194J_PROF" in csv and "6000.0" in csv, "26Q CSV for Q2 carries the deduction")
al = call("GET", "/api/alerts")
check(any(x["code"] == "TDS_SEC195" for x in al), "alert: Sec 195 for the US vendor")
check(any(x["code"] == "TDS_DEPOSIT_OVERDUE" for x in al), "alert: TDS deposit overdue (no challan recorded)")
eid = [r for r in reg["rows"] if r["tds_paise"] > 0][0]["id"]
d = call("POST", f"/api/tds/{eid}/deposit", {"challan_no": "00123", "bsr_code": "0510308", "deposited_on": "2026-09-05"})
check(d["deposited_on"] == "2026-09-05", "deposit recorded")
check(not any(x["code"] == "TDS_DEPOSIT_OVERDUE" for x in call("GET", "/api/alerts")), "overdue alert clears after deposit")

imp = upload("/api/payroll/import", "payroll.csv", open(os.path.join(ROOT, "fixtures", "payroll", "payroll-fy2026-27-sample.csv"), "rb").read())
check(imp.get("rows") == 9 and imp["employees"] == 3, "payroll CSV imported: 9 rows, 3 employees")
pr = call("GET", "/api/payroll?fy=2026-27")
L = {(l["employee_id"], l["month"]): l for l in pr["lines"]}
check(L[("E001", "2026-04")]["tds_paise"] == 812500, "E001 ₹15L p.a.: tax ₹97,500 -> ₹8,125/month")
check(L[("E001", "2026-06")]["tds_paise"] == 812500, "E001 stays flat through the year")
check(L[("E002", "2026-04")]["tds_paise"] == 0, "E002 ₹7.2L p.a.: nil after 87A rebate")
e3 = L[("E003", "2026-04")]["tds_paise"]
check(e3 == 2437500, f"E003 ₹24L p.a.: tax ₹2,92,500 -> ₹24,375/month (got {e3 / 100})")
q = call("GET", "/api/payroll/24q.csv?fy=2026-27&quarter=1", raw=True)
check("E001,Asha Rao,ABCPR1234D,2026-04,125000.0,8125.0" in q, "24Q Q1 CSV row for E001 April")
bad = upload("/api/payroll/import", "bad.csv", b"employee_id,month,gross\nE9,April,100\n")
check(bad.get("_status") == 400, "invalid month rejected")
print("P4 flow OK")
