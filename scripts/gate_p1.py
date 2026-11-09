"""P1 gate body: real extraction of fixture invoices through the running backend, then review+confirm via API."""
import json, sys, time, urllib.request, uuid, os

BASE = sys.argv[1]
FIX = os.path.join(os.path.dirname(__file__), "..", "fixtures", "invoices")
exp = json.load(open(os.path.join(FIX, "expected.json")))

def call(method, path, body=None, raw=None, ctype="application/json"):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"content-type": ctype} if data else {})
    try:
        with urllib.request.urlopen(req, timeout=300) as r: return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return {"_status": e.code, **json.loads(e.read() or b"{}")}

def check(cond, msg):
    print(("PASS: " if cond else "FAIL: ") + msg)
    if not cond: sys.exit(1)

co = call("PUT", "/api/company", exp["company"])
check(co.get("gstin") == exp["company"]["gstin"] and co.get("state_code") == "29", "company profile saved, state derived from GSTIN")

boundary = uuid.uuid4().hex
parts = b""
for inv in exp["invoices"]:
    parts += f"--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; filename=\"{inv['file']}\"\r\nContent-Type: application/pdf\r\n\r\n".encode()
    parts += open(os.path.join(FIX, inv["file"]), "rb").read() + b"\r\n"
parts += f"--{boundary}--\r\n".encode()
up = call("POST", "/api/documents", raw=parts, ctype=f"multipart/form-data; boundary={boundary}")
batch = up["batchId"]
t0 = time.time()
while True:
    b = call("GET", f"/api/batches/{batch}")
    print(f"  batch progress {b['done']}/{b['total']}")
    if b["complete"] or time.time() - t0 > 600: break
    time.sleep(5)
check(b["complete"], f"batch of {b['total']} extracted in {int(time.time()-t0)}s")
by_file = {d["filename"]: d for d in b["documents"]}

for e in exp["invoices"]:
    d = by_file[e["file"]]
    check(d["status"] == "NEEDS_REVIEW", f"{e['file']}: status {d['status']} {d.get('error') or ''}")
    inv = call("GET", f"/api/invoices/{d['invoice_id']}")
    check(inv["invoice_no"] == e["invoice_no"], f"{e['file']}: invoice_no {inv['invoice_no']!r}")
    check(inv["direction"] == e["direction"], f"{e['file']}: direction {inv['direction']}")
    check(inv["counterparty_gstin"] == e["counterparty_gstin"], f"{e['file']}: counterparty GSTIN {inv['counterparty_gstin']}")
    check(inv["supply_type"] is None, f"{e['file']}: tax treatment NOT guessed (supply_type null)")
    for k in ("taxable_paise", "cgst_paise", "sgst_paise", "igst_paise", "total_paise"):
        if k in e and not e.get("foreign_amount"):
            check(inv[k] == e[k], f"{e['file']}: {k} = {inv[k]}")
    upd = {"supply_type": e["supply_type"], "reverse_charge": e["reverse_charge"]}
    if "itc_eligible" in e: upd["itc_eligible"] = e["itc_eligible"]
    if e.get("foreign_amount") and e["direction"] == "PURCHASE":
        check(inv["foreign_amount"] in ("1200.00", "1200", "1,200.00"), f"{e['file']}: foreign amount {inv['foreign_amount']}")
        upd.update({"taxable_paise": e["inr_taxable_paise"], "total_paise": e["inr_taxable_paise"], "fx_rate": "84.00",
                    "lines": [{"description": "Atlas Analytics seats", "gst_rate": e["gst_rate"], "taxable_paise": e["inr_taxable_paise"]}]})
    if e["supply_type"] == "EXPORT_LUT":
        check(inv["taxable_paise"] == e["taxable_paise"], f"{e['file']}: INR equivalent picked up {inv['taxable_paise']}")
        upd["place_of_supply"] = "96"
    r = call("PUT", f"/api/invoices/{inv['id']}", upd)
    check("_status" not in r, f"{e['file']}: treatment saved by 'user'")
    if e["reverse_charge"]:
        r = call("POST", f"/api/invoices/{inv['id']}/compute-rcm")
        check(r.get("igst_paise") == 1814400, f"{e['file']}: RCM IGST computed deterministically = {r.get('igst_paise')}")
    r = call("POST", f"/api/invoices/{inv['id']}/confirm", {"acknowledgeWarnings": True, "saveRule": True})
    check(r.get("status") == "CONFIRMED", f"{e['file']}: confirmed {r.get('error','')}")

tb = call("GET", "/api/ledger/trial-balance")
check(tb["balanced"], f"trial balance balanced (Dr {tb['totalDebitPaise']} = Cr {tb['totalCreditPaise']})")
rules = call("GET", "/api/rules")
check(len(rules) == 4, f"{len(rules)} counterparty rules learned")
u = call("GET", "/api/llm/usage")
calls = [c for c in u["recent"] if c["purpose"] == "extraction"]
check(len(calls) == 4 and all(c["status"] == "OK" for c in calls), f"4 extraction calls logged, cost ${sum(c['cost_usd'] for c in calls):.4f}")
