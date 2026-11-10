-- Phase 9: customer TDS withheld on a receipt (net-of-TDS receipts), needed for correct receivable clearing.
ALTER TABLE bank_txn ADD COLUMN customer_tds_paise INTEGER NOT NULL DEFAULT 0;
