package com.account0.extraction;

/** Stable prompt (kept byte-identical across calls so it can be cached later). */
public final class ExtractionPrompt {
    private ExtractionPrompt() {}

    public static final String SYSTEM = """
            You read Indian GST invoices (and foreign vendor invoices) and transcribe what is PRINTED on them into JSON.
            You are a transcriber, not a tax advisor: never decide tax treatment, never compute missing figures, never guess.
            If a value is not printed, use null with confidence 0. Copy numbers exactly as printed (no currency symbols, no thousands separators).
            """;

    public static String user(String ourGstin, String ourName) {
        return """
                The business using this tool is: %s (GSTIN %s). Decide nothing about tax; just transcribe.

                Return ONLY a JSON object (no markdown fences, no commentary) with this exact shape.
                A "field" is {"value": <string|number|boolean|null>, "confidence": <0..1>, "evidence": "<short verbatim snippet from the document or null>"}.

                {
                  "document_kind": field   // one of TAX_INVOICE, CREDIT_NOTE, DEBIT_NOTE, BILL_OF_SUPPLY, EXPORT_INVOICE, FOREIGN_INVOICE, RECEIPT, NOT_AN_INVOICE
                  "supplier": {"name": field, "gstin": field, "country": field, "state": field},
                  "recipient": {"name": field, "gstin": field, "country": field, "state": field},
                  "invoice_no": field,
                  "invoice_date": field,          // ISO YYYY-MM-DD
                  "currency": field,              // ISO code, e.g. INR, USD
                  "place_of_supply": field,       // as printed, e.g. "29-Karnataka" or "Outside India"
                  "reverse_charge_printed": field, // true/false ONLY if the document prints a reverse-charge statement; else null
                  "lut_or_without_payment_printed": field, // true if it prints LUT / "supply meant for export without payment of IGST"; else null
                  "original_invoice_no": field,   // for credit/debit notes
                  "lines": [ {"description": string, "hsn_sac": string|null, "quantity": string|null, "gst_rate": string|null,
                              "taxable_value": string|null, "cgst": string|null, "sgst": string|null, "igst": string|null, "cess": string|null,
                              "confidence": number} ],
                  "totals": {"taxable_value": field, "cgst": field, "sgst": field, "igst": field, "cess": field, "total": field},
                  "inr_amounts_printed": field,   // true if amounts are in INR or an INR equivalent total is printed
                  "inr_total_printed": field,     // INR total if a foreign-currency invoice also prints INR; else null
                  "category_guess": field,        // best-guess ledger head, e.g. "Expense: Software subscriptions", "Expense: Rent", "Income: Software services"
                  "notes": string|null            // anything unusual: handwritten edits, multiple invoices, unreadable areas
                }

                Confidence guide: 0.95+ clearly printed and unambiguous; 0.7-0.9 readable but formatting ambiguous; <0.6 inferred, blurry or partly cut off.
                """.formatted(ourName == null ? "(name not set)" : ourName, ourGstin == null ? "(not set)" : ourGstin);
    }
}
