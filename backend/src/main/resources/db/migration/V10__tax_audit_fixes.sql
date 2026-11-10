-- Phase 10 (tax-correctness audit fixes)
-- RCM time of supply (Sec 13(3) CGST Act): date the vendor was paid; with no payment, ToS = invoice date + 61 days.
ALTER TABLE invoice ADD COLUMN rcm_paid_on TEXT;
-- GSTR-1 Table 8: NIL (nil-rated) vs EXEMPT for NIL_EXEMPT_OUT sales; NULL is reported as exempt with a note.
ALTER TABLE invoice ADD COLUMN nil_type TEXT;
-- GSTR-3B Table 3.2: REGULAR (default) | COMPOSITION | UIN for B2B sales.
ALTER TABLE invoice ADD COLUMN buyer_kind TEXT;
