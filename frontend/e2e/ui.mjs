// Browser-level UI test (headless Chromium via playwright-core). Usage: node e2e/ui.mjs <uiBase> <apiBase> <repoRoot>
// Extraction is served by the backend's STUB fixture transport (no LLM call). Exercises the real UI -> Next rewrite -> API path.
import { chromium } from "playwright-core";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const [UI, API, ROOT] = process.argv.slice(2);
function chromePath() {
  if (process.env.PLAYWRIGHT_CHROMIUM) return process.env.PLAYWRIGHT_CHROMIUM;
  const base = path.join(os.homedir(), "Library/Caches/ms-playwright");
  for (const d of fs.existsSync(base) ? fs.readdirSync(base).sort().reverse() : []) {
    if (!d.startsWith("chromium_headless_shell")) continue;
    for (const sub of fs.readdirSync(path.join(base, d))) {
      const p = path.join(base, d, sub, "chrome-headless-shell");
      if (fs.existsSync(p)) return p;
    }
  }
  return undefined; // fall back to playwright's own download
}
const step = (m) => console.log("UI STEP: " + m);
const browser = await chromium.launch({ executablePath: chromePath(), headless: true });
const page = await browser.newPage();
const consoleErrors = [];
page.on("pageerror", (e) => consoleErrors.push(String(e)));
page.setDefaultTimeout(30000);
try {
  // 0. proxy sanity: the UI origin must serve API JSON from the gate backend
  const health = await page.request.get(`${UI}/health`);
  assert.equal(health.status(), 200, "UI /health proxy");
  assert.match(await health.text(), /UP/);
  step("UI->API proxy serves /health");

  // 1. company profile
  await page.goto(`${UI}/company`);
  await page.getByText("Legal name").locator("xpath=following-sibling::input[1]").fill("Acme Cloudworks Private Limited");
  await page.getByText("GSTIN", { exact: false }).first().locator("xpath=following-sibling::input[1]").fill("29AAKCA1234B1Z4");
  await page.getByRole("button", { name: "Save" }).click();
  await page.getByText("Saved.").waitFor();
  const co = await (await page.request.get(`${API}/api/company`)).json();
  assert.equal(co.gstin, "29AAKCA1234B1Z4"); assert.equal(co.state_code, "29");
  step("company profile saved through the UI and visible in the API");

  // 2. upload sample invoice
  await page.goto(`${UI}/inbox`);
  await page.setInputFiles('input[type=file]', path.join(ROOT, "fixtures/invoices/03-purchase-nimbus-hosting.pdf"));
  await page.getByTestId("batch").getByText("done").waitFor();
  await page.getByText("NEEDS_REVIEW").first().waitFor();
  step("uploaded; stubbed extraction reached NEEDS_REVIEW");

  // 3. review + confirm
  await page.locator("main").getByRole("link", { name: "Review", exact: true }).first().click();
  await page.getByText("Tax treatment — you decide").waitFor();
  assert.equal(await page.locator('input').evaluateAll((els) => els.some((e) => e.value === "NHS/2026/1187")), true, "invoice number shown");
  // the supply type is the user's call; pick it explicitly
  await page.locator("select").nth(2).selectOption("DOMESTIC");
  await page.getByText("ITC eligible?").locator("xpath=following-sibling::select[1]").selectOption("1");
  await page.getByText("I have checked the highlighted fields").click();
  await page.getByRole("button", { name: "Confirm & post" }).click();
  await page.getByText("Confirmed and posted to the ledger.").waitFor();
  const invs = await (await page.request.get(`${API}/api/invoices?status=CONFIRMED`)).json();
  assert.ok(JSON.stringify(invs).includes("NHS/2026/1187"));
  step("invoice confirmed and posted");

  // 4. GST page: click a number, see rule + sources
  await page.goto(`${UI}/gst`);
  await page.locator('input[type=month]').fill("2026-10");
  await page.getByText("4 Eligible ITC").waitFor();
  const allItc = page.getByRole("row").filter({ hasText: "All other ITC" }).first();
  await allItc.locator("a.fig").nth(2).click();   // 0 = label, 1 = taxable value, 2 = IGST
  const ex = page.getByTestId("explain");
  await ex.waitFor();
  await ex.getByText("Rule applied:").waitFor();
  await ex.getByRole("link", { name: "NHS/2026/1187" }).waitFor();
  const exText = await ex.innerText();
  assert.match(exText, /₹59,000\.00|₹9,000\.00|₹50,000\.00/);
  step("GST figure explained with rule and source invoice");

  // 5. ledger page renders and balances
  await page.goto(`${UI}/ledger`);
  await page.getByText("Payable: Nimbus Hosting Solutions Private Limited").first().waitFor();
  step("ledger shows the posted journal");
  assert.deepEqual(consoleErrors, [], "no uncaught page errors: " + consoleErrors.join("; "));
  console.log("UI E2E GREEN");
} catch (e) {
  await page.screenshot({ path: path.join(os.tmpdir(), "account0-ui-fail.png"), fullPage: true }).catch(() => {});
  console.error("UI E2E FAILED: " + (e && e.stack || e)); console.error("screenshot: " + path.join(os.tmpdir(), "account0-ui-fail.png"));
  await browser.close(); process.exit(1);
}
await browser.close();
