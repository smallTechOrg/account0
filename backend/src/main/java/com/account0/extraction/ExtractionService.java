package com.account0.extraction;

import com.account0.common.Tenant;
import com.account0.company.CompanyService;
import com.account0.documents.DocumentService;
import com.account0.invoice.Flag;
import com.account0.invoice.InvoiceService;
import com.account0.llm.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.util.*;

/** One Claude call per document → draft invoice in the review queue. */
@Service
public class ExtractionService {
    private final DocumentService docs;
    private final LlmGateway llm;
    private final LlmProperties props;
    private final CompanyService company;
    private final InvoiceService invoices;
    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final com.account0.common.AuditService audit;

    public ExtractionService(DocumentService docs, LlmGateway llm, LlmProperties props, CompanyService company, InvoiceService invoices, JdbcTemplate jdbc, ObjectMapper om, com.account0.common.AuditService audit) {
        this.docs = docs; this.llm = llm; this.props = props; this.company = company; this.invoices = invoices; this.jdbc = jdbc; this.om = om; this.audit = audit;
    }

    public void extract(long documentId) throws Exception {
        Map<String, Object> d = docs.get(documentId);
        if (!List.of("QUEUED", "EXTRACTING", "FAILED").contains(d.get("status"))) return;
        docs.setStatus(documentId, "EXTRACTING", null);
        byte[] bytes = Files.readAllBytes(docs.file(documentId));
        String mime = (String) d.get("mime");
        Map<String, Object> source = Map.of("type", "base64", "media_type", mime, "data", Base64.getEncoder().encodeToString(bytes));
        Map<String, Object> docBlock = mime.equals("application/pdf") ? Map.of("type", "document", "source", source) : Map.of("type", "image", "source", source);
        Map<String, Object> co = company.get();
        String ourGstin = (String) co.get("gstin"), ourName = (String) co.get("legal_name");
        List<Map<String, Object>> content = List.of(docBlock, Map.of("type", "text", "text", ExtractionPrompt.user(ourGstin, ourName)));

        JsonNode json;
        LlmResponse r;
        try {
            r = llm.call(new LlmRequest(props.getExtractionModel(), "extraction", "document:" + documentId, ExtractionPrompt.SYSTEM, content, 8000));
            json = parse(r.text());
        } catch (LlmException e) {
            docs.setStatus(documentId, "FAILED", e.getMessage());
            return;
        }
        if (json == null) {
            docs.setStatus(documentId, "FAILED", "Model did not return valid JSON (see LLM usage log). Retry, or enter manually.");
            return;
        }
        ExtractionMapper.Result res = ExtractionMapper.map(json, ourGstin, ourName);
        jdbc.update("INSERT INTO extraction(tenant_id, document_id, model, raw_json, checks_json) VALUES (?,?,?,?,?)",
                Tenant.current(), documentId, props.getExtractionModel(), json.toString(), om.writeValueAsString(res.flags()));
        List<Flag> flags = new ArrayList<>(res.flags());
        if ("true".equalsIgnoreCase(String.valueOf(json.path("_truncated").asText()))) flags.add(Flag.warn("lines", "TRUNCATED", "Response was truncated."));
        if ("max_tokens".equals(r.stopReason())) flags.add(Flag.warn("lines", "TRUNCATED", "Extraction output hit the token limit; some lines may be missing."));
        invoices.createDraft(documentId, res.header(), res.lines(), flags, "system:extraction");
        docs.setStatus(documentId, "NEEDS_REVIEW", null);
    }

    JsonNode parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try { return om.readTree(t.substring(a, b + 1)); } catch (Exception e) { return null; }
    }

    /**
     * After the company profile changes, re-derive direction for untouched drafts whose direction was unknown,
     * from the stored extraction (no new LLM call). Only drafts with no user edits are touched; tax treatment is never set here.
     * Flags: the original extraction flags (extraction.checks_json) are swapped for the re-mapped ones; other flags (duplicates, rules) are kept.
     */
    @SuppressWarnings("unchecked")
    public int remapUnknownDirections() {
        Map<String, Object> co = company.get();
        String g = co.get("gstin") == null ? null : co.get("gstin").toString();
        String name = co.get("legal_name") == null ? null : co.get("legal_name").toString();
        if (g == null && name == null) return 0;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.id, i.flags_json, e.raw_json, e.checks_json FROM invoice i
                JOIN extraction e ON e.id = (SELECT MAX(id) FROM extraction WHERE document_id = i.document_id)
                WHERE i.tenant_id=? AND i.status='DRAFT' AND i.direction IS NULL
                  AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.tenant_id=i.tenant_id AND a.entity='invoice' AND a.entity_id=CAST(i.id AS TEXT) AND a.actor NOT LIKE 'system:%')""", Tenant.current());
        int n = 0;
        for (Map<String, Object> r : rows) {
            try {
                ExtractionMapper.Result res = ExtractionMapper.map(om.readTree((String) r.get("raw_json")), g, name);
                Object dir = res.header().get("direction");
                if (dir == null) continue;
                List<Map<String, Object>> old = om.readValue(String.valueOf(r.get("flags_json")), List.class);
                List<Map<String, Object>> orig = om.readValue(String.valueOf(r.get("checks_json")), List.class);
                Set<String> origKeys = new HashSet<>();
                for (Map<String, Object> f : orig) origKeys.add(f.get("field") + "|" + f.get("code"));
                List<Object> flags = new ArrayList<>(om.convertValue(res.flags(), List.class));
                for (Map<String, Object> f : old) if (!origKeys.contains(f.get("field") + "|" + f.get("code"))) flags.add(f);
                long id = ((Number) r.get("id")).longValue();
                Map<String, Object> h = res.header();
                jdbc.update("UPDATE invoice SET direction=?, counterparty_name=?, counterparty_gstin=?, counterparty_country=?, flags_json=? WHERE id=? AND direction IS NULL",
                        dir, h.get("counterparty_name"), h.get("counterparty_gstin"), h.get("counterparty_country"), om.writeValueAsString(flags), id);
                jdbc.update("UPDATE extraction SET checks_json=? WHERE document_id=(SELECT document_id FROM invoice WHERE id=?) AND id=(SELECT MAX(id) FROM extraction WHERE document_id=(SELECT document_id FROM invoice WHERE id=?))",
                        om.writeValueAsString(res.flags()), id, id);
                audit.record("system:profile-remap", "invoice", id, "update", "direction", null, dir, "Re-derived from stored extraction after company profile was saved");
                n++;
            } catch (Exception ignored) { /* leave the draft as is; user can still choose direction manually */ }
        }
        return n;
    }
}
