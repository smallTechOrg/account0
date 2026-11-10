"""P7 gate body: reference library, walkthroughs on own data, DIY coaching, and REAL grounded Q&A (2 Claude calls)."""
import json, sys, urllib.request, os

BASE = sys.argv[1]
ROOT = os.path.join(os.path.dirname(__file__), "..")
exp = json.load(open(os.path.join(ROOT, "fixtures", "invoices", "expected.json")))

def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"content-type": "application/json"} if data else {})
    try:
        with urllib.request.urlopen(req, timeout=120) as r: return json.loads(r.read() or b"null")
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


lib = call("GET", "/api/learn/library")
ids = {e["id"] for e in lib}
check(len(lib) >= 50 and {"igst-5-3", "cgst-16", "it-194j", "term-lut"} <= ids, f"reference library has {len(lib)} entries incl. core provisions")
why = call("GET", "/api/learn/why")
check("3.1d" in why and why["3.1d"]["citations"], "inline why-note for 3.1(d) carries citations")
check(any(h["id"] == "cgst-16" for h in call("GET", "/api/learn/search?q=when+can+I+claim+input+tax+credit")), "library search finds Sec 16")

w = call("GET", "/api/learn/walkthrough/rcm?period=2026-10")
txt = json.dumps(w, ensure_ascii=False)
check("INV-2026-10-0091" in txt and "18,144.00" not in txt and any(f["paise"] == 1814400 for s in w["steps"] for f in s["figures"]), "RCM walkthrough uses YOUR Atlas invoice and RCM tax of 1814400 paise")
w3 = call("GET", "/api/learn/walkthrough/gstr3b?period=2026-10")
check("2026-11-20" in json.dumps(w3) and any(s["title"].startswith("Table 6.1") for s in w3["steps"]) and not w3["empty"], "GSTR-3B walkthrough has due date and set-off step")
cash = [f for s in w3["steps"] for f in s["figures"] if f["label"] == "Total cash payable"][0]["paise"]
check(cash == 1814400, "walkthrough cash payable equals the 3B engine (18,144)")
d = call("GET", "/api/learn/diy/2026-10")
check(len(d["steps"]) == 8 and "Prepare Offline" in d["steps"][1]["text"], "DIY guide has 8 steps with portal instructions")
d = call("POST", "/api/learn/diy/2026-10/GSTR2B", {"done": True})
check(d["progress"]["done"] == 1, "DIY progress persists")
check("shadow mode" in call("GET", "/api/learn/diy-readiness")["verdict"], "readiness: keep shadow mode until 3 compared months")

# off-topic is declined WITHOUT an LLM call
u0 = call("GET", "/api/llm/usage")
off = call("POST", "/api/learn/ask", {"question": "what is a good biryani recipe"})
u1 = call("GET", "/api/llm/usage")
check(off["answered"] is False and u0["currentMonthCostUsd"] == u1["currentMonthCostUsd"], "off-topic question declined, no LLM spend")

# real grounded Q&A
a = call("POST", "/api/learn/ask", {"question": "Why do I have to pay GST myself on my US software subscription, and where does it show up in GSTR-3B?"})
print("ANSWER:", a.get("answer"))
check(a.get("answered") and a.get("grounded"), "real Claude answer is grounded (cites at least one source)")
cited = {c["id"] for c in a["citations"]}
check(cited <= ids and len(cited) >= 1, f"every citation exists in the library: {sorted(cited)}")
check(cited & {"igst-5-3", "term-rcm", "cgst-9-3", "cgst-39"}, "cites the reverse-charge / 3B provisions")
b = call("POST", "/api/learn/ask", {"question": "Explain my reverse charge liability in GSTR-3B for October 2026"})
print("ANSWER:", b.get("answer"))
check(b.get("answered") and any("3.1(d)" in f for f in b.get("usedFacts", [])), "facts about October 2026 were attached from the engine")
check("18,144" in b["answer"] or "18144" in b["answer"], "answer quotes the engine's RCM figure (18,144), not its own arithmetic")
print("P7 flow OK")
