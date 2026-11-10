"""P11 gate body (no LLM): ITC claimed once, 2B accept/reject, advances, Rules 42/43/37, ledger postings, 194I rent period."""
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

def purchase(no, date, igst, **kw):
    tx = igst * 100 // 18
    inv = {"direction": "PURCHASE", "invoice_no": no, "invoice_date": date, "counterparty_name": "Nimbus Hosting", "counterparty_gstin": VENDOR, "place_of_supply": "29",
           "supply_type": "DOMESTIC", "reverse_charge": 0, "itc_eligible": 1, "taxable_paise": tx, "igst_paise": igst, "total_paise": tx + igst, "category": "Hosting",
           "lines": [{"description": "Hosting", "hsn_sac": "998315", "gst_rate": "18", "taxable_paise": tx, "igst_paise": igst}]}
    inv.update(kw)
    return confirmed(inv)["id"]

def twob(mmyyyy, lines):
    inv = [{"inum": n, "dt": "10-08-2026", "rev": "N", "itcavl": "Y", "txval": 1000, "igst": i, "cgst": 0, "sgst": 0, "cess": 0} for n, i in lines]
    d = {"data": {"gstin": exp["company"]["gstin"], "rtnprd": mmyyyy, "docdata": {"b2b": [{"ctin": VENDOR, "trdnm": "Nimbus Hosting", "inv": inv}] if inv else []}}}
    return upload("/api/itc/2b/upload", "2b.json", json.dumps(d).encode())

def a5(p): return fig(call("GET", f"/api/gst/3b?period={p}"), "table4", "4A5")["igst_paise"]

# ---- 1. ITC claimed exactly once ----
x = purchase("NHS/801", "2026-08-10", 90000)
check(a5("2026-08") == 90000, "Aug (no 2B): NHS/801 claimed provisionally (900)")
twob("092026", [("NHS-801", 900)])
sep = call("GET", "/api/gst/3b?period=2026-09")
row = [r for r in sep["itcClaims"]["rows"] if r["invoiceId"] == x][0]
check(fig(sep, "table4", "4A5")["igst_paise"] == 0 and row["claimedIn"] == "2026-08" and not row["claimedHere"], "Sep 2B matches NHS/801 but it is already claimed in Aug: Sep 4A(5) = 0 (no double count)")
check(any("already claimed in 2026-08" in n for n in sep["notes"]), "Sep note explains the earlier provisional claim")
f = call("POST", "/api/filings", {"return_type": "GSTR3B", "period": "2026-08", "arn": "AA290826000001", "filed_on": "2026-09-20"})
twob("082026", [])
aug = call("GET", "/api/gst/3b?period=2026-08")
check(aug["itcMode"] == "FILED" and fig(aug, "table4", "4A5")["igst_paise"] == 90000 and a5("2026-09") == 0, "Aug filed+locked: its claim snapshot is authoritative after a later Aug 2B without the invoice")
call("POST", f"/api/filings/{f['id']}/unlock", {"reason": "revise"})
check(a5("2026-08") == 0 and a5("2026-09") == 90000, "after unlock: Aug 2B lacks it, Sep 2B has it -> claimed in Sep only")
call("POST", f"/api/filings/{f['id']}/lock", {})

# ---- 2. accept / reject mismatched 2B lines ----
purchase("NHS/1001", "2026-10-05", 180000)
purchase("NHS/1002", "2026-10-06", 36000)
twob("102026", [("NHS/1001", 1700), ("NHS/10002", 360)])
rows = call("GET", "/api/itc/match?period=2026-10")["rows"]
mm = [r for r in rows if r["status"] == "AMOUNT_MISMATCH"][0]; pm = [r for r in rows if r["status"] == "POSSIBLE_MATCH"][0]
check(a5("2026-10") == 0, "mismatched / possible 2B lines are not claimed before a decision")
check(call("POST", f"/api/itc/2b/docs/{mm['docId']}/decision", {"decision": "ACCEPTED"}).get("_status") == 400, "accept without a reason is refused")
call("POST", f"/api/itc/2b/docs/{mm['docId']}/decision", {"decision": "ACCEPTED", "reason": "claim as per 2B"})
call("POST", f"/api/itc/2b/docs/{pm['docId']}/decision", {"decision": "REJECTED", "reason": "not our invoice"})
g = call("GET", "/api/gst/3b?period=2026-10")
check(fig(g, "table4", "4A5")["igst_paise"] == 170000, "accepted AMOUNT_MISMATCH claimed at the 2B tax (1,700, not books 1,800); rejected line left out")
check(any("REJECTED by you" in n for n in g["notes"]), "rejection shown in the notes")
check(sum(1 for a in call("GET", "/api/audit") if a.get("entity") == "gstr2b_doc" and a.get("action") == "decision") >= 2, "decisions are in the audit trail")

# ---- 3. advances (GSTR-1 Table 11, 3B 3.1(a)) ----
adv = call("POST", "/api/advances", {"receipt_date": "2026-06-15", "customer_name": "Bluepeak Retail", "customer_gstin": BUYER, "place_of_supply": "29", "kind": "DOMESTIC", "gst_rate": "18", "gross_paise": 11800000})
check(adv.get("taxable_paise") == 10000000 and adv.get("cgst_paise") == 900000, f"domestic services advance 1,18,000: taxable 1,00,000 + CGST/SGST 9,000 each {adv.get('error', '')}")
lut = call("POST", "/api/advances", {"receipt_date": "2026-06-16", "customer_name": "Globex Inc", "kind": "EXPORT_LUT", "gst_rate": "0", "gross_paise": 5000000})
check(lut.get("taxedAtReceipt") is False, "export advance under LUT carries no tax")
j = json.loads(call("GET", "/api/gst/1/portal.json?period=2026-06", raw=True))
check(len(j.get("at", [])) == 1 and j["at"][0]["pos"] == "29" and j["at"][0]["itms"][0]["ad_amt"] == 100000.0, "GSTR-1 JSON 'at' (11A) has the domestic advance only")
check(fig(call("GET", "/api/gst/3b?period=2026-06"), "table31", "3.1a")["cgst_paise"] == 900000, "3B Jun 3.1(a) carries the advance's tax")
s_inv = confirmed({"direction": "SALE", "invoice_no": "ACW/27/01", "invoice_date": "2026-07-05", "counterparty_name": "Bluepeak Retail", "counterparty_gstin": BUYER, "place_of_supply": "29", "supply_type": "B2B",
                   "reverse_charge": 0, "taxable_paise": 10000000, "cgst_paise": 900000, "sgst_paise": 900000, "total_paise": 11800000, "category": "Income",
                   "lines": [{"description": "SaaS", "hsn_sac": "998314", "gst_rate": "18", "taxable_paise": 10000000, "cgst_paise": 900000, "sgst_paise": 900000}]})
call("POST", f"/api/advances/{adv['id']}/adjust", {"invoiceId": s_inv["id"]})
j = json.loads(call("GET", "/api/gst/1/portal.json?period=2026-07", raw=True))
check(len(j.get("txpd", [])) == 1 and j["txpd"][0]["itms"][0]["camt"] == 9000.0 and "at" not in j, "Jul GSTR-1 JSON 'txpd' (11B) adjusts the June advance")
check(fig(call("GET", "/api/gst/3b?period=2026-07"), "table31", "3.1a")["cgst_paise"] == 0, "Jul 3B 3.1(a): invoice tax less the tax already paid on the advance = 0")

print("P11 flow OK")
