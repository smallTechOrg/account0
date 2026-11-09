"""P8 gate body (no credentials needed): the integration must be present but honestly UNCONNECTED, never import, never leak, and refuse unsafe input."""
import json, sys, urllib.request

BASE = sys.argv[1]

def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"content-type": "application/json"} if data else {})
    try:
        with urllib.request.urlopen(req, timeout=30) as r: return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return {"_status": e.code, **json.loads(e.read() or b"{}")}

def check(cond, msg):
    print(("PASS: " if cond else "FAIL: ") + msg)
    if not cond: sys.exit(1)

st = call("GET", "/api/google/status")
check(st["configured"] is False and st["connected"] is False, "no credentials -> not configured, not connected")
check(st["gmail"]["message"].startswith("STUB: not configured") and "ACCOUNT0_GOOGLE_CLIENT_ID" in st["gmail"]["message"], "Gmail card says STUB and tells the user exactly what to set")
check(st["drive"]["stub"] is True and st["gmail"]["untestedAgainstLiveGoogle"] is True, "marked as stub and as untested against live Google")
check(call("GET", "/api/google/auth-url").get("_status") == 409, "auth-url refuses (409) without credentials")
check(call("POST", "/api/google/sync/gmail").get("_status") == 400, "sync refuses while not connected")
check(call("GET", "/api/google/callback?code=x&state=forged").get("_status") == 400, "callback with forged state is rejected")
src = call("GET", "/api/sources")
check(sorted(e["name"] for e in src["external"]) == ["Gmail", "Google Drive"] and all(e["connected"] is False for e in src["external"]), "/api/sources lists both external sources as unconnected")
check(call("PUT", "/api/google/config", {"drive_folder_id": "../etc"}).get("_status") == 400, "unsafe Drive folder id rejected")
check("access_token" not in json.dumps(st).lower() and "refresh_token" not in json.dumps(st).lower(), "status exposes no secrets")
print("P8 flow OK")
