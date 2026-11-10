# AI-native design

Principle: **the LLM reads and explains; code computes and decides; the user confirms tax treatment.**

## Where the LLM is used
| Use | Model | Shape | Phase |
|---|---|---|---|
| Invoice field extraction | claude-sonnet-5-5 | 1 call per document (PDF/image block + instructions) → JSON with per-field `confidence` + `evidence` | P1 |
| Category best-guess (expense/income head) | same call as extraction | `category_guess` + confidence; always shown as "suggested" | P1 |
| Plain-language explanation of a filed return / CA questions wording | claude-sonnet-5-5 | 1 call per comparison, given the deterministic diff as input | P3 |
| Parsing CA's filed 3B PDF into figures | claude-sonnet-5-5 | 1 call per PDF, then user confirms figures | P3 |
| Q&A with citations, walkthrough narration | claude-sonnet-5-5 / haiku for short | grounded on a curated local corpus of sections/rules/circulars | P7 |
| Key check | claude-haiku-5-5 | "Reply OK" | P0 |

## Where the LLM is NOT used (deterministic code)
- Any arithmetic: totals, tax, 3B/GSTR-1 tables, set-off, TDS amounts, reconciliation matching scores.
- Tax treatment decisions: supply type, RCM, GST rate, ITC eligibility, TDS section. The model may report
  what the document *says* (e.g. "Reverse charge: Yes" printed), never decide it. Fields are marked
  `requiresUserConfirmation`.
- Validation: GSTIN checksum, PAN/state derivation, sum checks, duplicate detection, period locks.
- Ledger posting.

## Confidence handling
- Model returns `confidence` ∈ [0,1] per field. Deterministic checks can only *lower* it (e.g. GSTIN checksum
  fail → 0.2, totals mismatch → flag on totals).
- Field < 0.85 or failed check → highlighted in review; invoice cannot be confirmed until the user has
  touched/acknowledged the flagged fields (acknowledge = the explicit "I have checked the highlighted fields" tick, recorded in the audit note on confirm).
- Tax-treatment fields are always unconfirmed on a new invoice regardless of confidence.
- Nothing extracted ever reaches the ledger or GST computation until status = CONFIRMED.

## Tool use
Extraction is a single structured-output style call (JSON in the text response, validated & parsed in Java;
if the JSON is invalid the document is marked FAILED with the reason and the user can click Retry — no silent automatic re-spend). No agent loop — task is fully specified. Future Q&A (P7)
may use client tools (`lookup_invoice`, `get_3b_figure`, `search_law_corpus`) through a manual loop bounded
to 4 turns, still through `LlmGateway` so spend is capped.

## Cost controls
Per-call usage logged with model, tokens, cost; monthly cap setting; batches processed sequentially by a single
worker so a big upload can't burst; extraction prompt is short and stable.
