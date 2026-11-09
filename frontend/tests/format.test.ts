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
