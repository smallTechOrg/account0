import { test } from "node:test";
import assert from "node:assert/strict";
import { inr, toPaise } from "../lib/format.ts";

test("inr uses Indian digit grouping", () => {
  assert.equal(inr(12345678), "₹1,23,456.78");
  assert.equal(inr(100), "₹1.00");
  assert.equal(inr(-50), "-₹0.50");
  assert.equal(inr(1000000000), "₹1,00,00,000.00");
});

test("toPaise parses rupee text", () => {
  assert.equal(toPaise("1,234.5"), 123450);
  assert.equal(toPaise("₹ 10"), 1000);
  assert.equal(toPaise("abc"), null);
});

import { inrPlain, fyOptions } from "../lib/format.ts";
test("inrPlain groups decimal rupees the Indian way", () => {
  assert.equal(inrPlain("120000"), "1,20,000.00");
  assert.equal(inrPlain(1234567.5), "12,34,567.50");
  assert.equal(inrPlain("x"), "—");
});
test("fyOptions lists FY labels around now", () => {
  assert.deepEqual(fyOptions(new Date(2026, 10, 10), 1, 1), ["2027-28", "2026-27", "2025-26"]);
  assert.equal(fyOptions(new Date(2026, 1, 1), 0, 0)[0], "2025-26");   // Feb 2026 is still FY 2025-26
});
