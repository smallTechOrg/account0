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

    public ExtractionService(DocumentService docs, LlmGateway llm, LlmProperties props, CompanyService company, InvoiceService invoices, JdbcTemplate jdbc, ObjectMapper om) {
        this.docs = docs; this.llm = llm; this.props = props; this.company = company; this.invoices = invoices; this.jdbc = jdbc; this.om = om;
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
}
