package com.account0.scrutiny;

import com.account0.common.ApiException;
import com.account0.common.AuditService;
import com.account0.common.Tenant;
import com.account0.documents.DocumentService;
import com.account0.gst.GstService;
import com.account0.llm.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

@Service
public class ScrutinyService {
    private final JdbcTemplate jdbc;
    private final GstService gst;
    private final ObjectMapper om;
    private final AuditService audit;
    private final LlmGateway llm;
    private final LlmProperties props;
    private final DocumentService docs;

    public ScrutinyService(JdbcTemplate jdbc, GstService gst, ObjectMapper om, AuditService audit, LlmGateway llm, LlmProperties props, DocumentService docs) {
        this.jdbc = jdbc; this.gst = gst; this.om = om; this.audit = audit; this.llm = llm; this.props = props; this.docs = docs;
    }

    public long save(String period, String source, Map<String, Map<String, Long>> figures, boolean confirmed, Long documentId) {
        try {
            GeneratedKeyHolder kh = new GeneratedKeyHolder();
            String json = om.writeValueAsString(figures);
            jdbc.update(c -> { PreparedStatement ps = c.prepareStatement("INSERT INTO filed_return(tenant_id, return_type, period, source, figures_json, confirmed, document_id) VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, Tenant.current()); ps.setString(2, "GSTR3B"); ps.setString(3, period); ps.setString(4, source); ps.setString(5, json); ps.setInt(6, confirmed ? 1 : 0);
                if (documentId == null) ps.setNull(7, java.sql.Types.INTEGER); else ps.setLong(7, documentId); return ps; }, kh);
            long id = kh.getKey().longValue();
            audit.record("user", "filed_return", id, "create", "figures", null, json, source);
            return id;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public long importPortalJson(byte[] bytes) {
        try {
            JsonNode root = om.readTree(bytes);
            JsonNode j = root.has("data") ? root.get("data") : root;
            String rp = j.path("ret_period").asText(null);
            if (rp == null || !rp.matches("\\d{6}")) throw ApiException.bad("ret_period (MMYYYY) missing in JSON");
            return save(rp.substring(2) + "-" + rp.substring(0, 2), "PORTAL_JSON", Gstr3bFigures.fromPortalJson(root), true, null);
        } catch (ApiException e) { throw e; }
        catch (IllegalArgumentException e) { throw ApiException.bad(e.getMessage()); }
        catch (Exception e) { throw ApiException.bad("Could not read JSON: " + e.getMessage()); }
    }

    /** Typed-in figures (rupees as strings) → paise. Already "confirmed" since the user typed them. */
    public long importManual(String period, Map<String, Map<String, Object>> rupees) {
        Map<String, Map<String, Long>> f = new LinkedHashMap<>();
        for (String k : Gstr3bFigures.LINES.keySet()) {
            Map<String, Long> m = Gstr3bFigures.zero();
            Map<String, Object> in = rupees.getOrDefault(k, Map.of());
            for (String h : Gstr3bFigures.HEADS) if (in.get(h) != null && !in.get(h).toString().isBlank()) m.put(h, com.account0.common.Money.toPaise(in.get(h).toString()));
            f.put(k, m);
        }
        return save(period, "MANUAL", f, true, null);
    }

    /** PDF of the filed return → one Claude call to transcribe figures → saved UNCONFIRMED until the user checks them. */
    public long importPdf(byte[] bytes, String filename) throws LlmException {
        Map<String, Object> doc = docs.ingestReference(bytes, filename);
        long docId = ((Number) doc.get("id")).longValue();
        String prompt = """
                This is a filed GSTR-3B return (Indian GST). Transcribe the figures exactly as printed. Return ONLY JSON:
                {"period": "YYYY-MM", "figures": {"3.1a": {"taxable":"","igst":"","cgst":"","sgst":"","cess":""}, "3.1b": {...}, "3.1c": {...}, "3.1d": {...}, "3.1e": {...},
                 "4A1": {...}, "4A2": {...}, "4A3": {...}, "4A5": {...}, "4B1": {...}, "4C": {...}}}
                Use plain numbers in rupees without commas (e.g. "120000.00"); use "0" when a cell is blank or zero. For 4A/4B/4C leave "taxable" as "0".
                4A(1)=Import of goods, 4A(2)=Import of services, 4A(3)=Inward supplies liable to reverse charge, 4A(5)=All other ITC, 4B(1)=reversed as per rules 38,42,43 and 17(5), 4C=Net ITC.
                """;
        Map<String, Object> src = Map.of("type", "base64", "media_type", "application/pdf", "data", Base64.getEncoder().encodeToString(bytes));
        LlmResponse r = llm.call(new LlmRequest(props.getExtractionModel(), "filed-3b-parse", "document:" + docId, "You transcribe tax return figures exactly. Never compute or guess.",
                List.of(Map.of("type", "document", "source", src), Map.of("type", "text", "text", prompt)), 4000));
        try {
            String t = r.text(); JsonNode j = om.readTree(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1));
            String period = j.path("period").asText();
            if (!period.matches("\\d{4}-\\d{2}")) throw ApiException.bad("Could not read the return period from the PDF.");
            Map<String, Map<String, Object>> fig = om.convertValue(j.get("figures"), new TypeReference<>() {});
            Map<String, Map<String, Long>> f = new LinkedHashMap<>();
            for (String k : Gstr3bFigures.LINES.keySet()) {
                Map<String, Long> m = Gstr3bFigures.zero();
                Map<String, Object> in = fig.getOrDefault(k, Map.of());
                for (String h : Gstr3bFigures.HEADS) if (in.get(h) != null) try { m.put(h, com.account0.common.Money.toPaise(in.get(h).toString())); } catch (Exception ignored) {}
                f.put(k, m);
            }
            return save(period, "PDF_LLM", f, false, docId);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Model output was not valid JSON — enter the figures manually."); }
    }

    public void confirm(long id) {
        jdbc.update("UPDATE filed_return SET confirmed=1 WHERE tenant_id=? AND id=?", Tenant.current(), id);
        audit.record("user", "filed_return", id, "confirm", "confirmed", 0, 1, "user checked transcribed figures");
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id, return_type, period, source, confirmed, document_id, created_at FROM filed_return WHERE tenant_id=? ORDER BY period DESC, id DESC", Tenant.current());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> report(long id) throws Exception {
        Map<String, Object> fr = jdbc.queryForMap("SELECT * FROM filed_return WHERE tenant_id=? AND id=?", Tenant.current(), id);
        String period = (String) fr.get("period");
        Map<String, Map<String, Long>> filed = om.readValue((String) fr.get("figures_json"), new TypeReference<>() {});
        Map<String, Object> g3 = gst.gstr3b(period);
        Map<String, Map<String, Long>> books = Gstr3bFigures.fromComputed(g3);
        Map<String, List<Map<String, Object>>> sources = new HashMap<>();
        for (String t : List.of("table31", "table4")) for (Map<String, Object> fg : (List<Map<String, Object>>) g3.get(t)) sources.put((String) fg.get("key"), (List<Map<String, Object>>) fg.get("sources"));
        Long itc2b = jdbc.queryForObject("SELECT CASE WHEN COUNT(*)=0 THEN NULL ELSE SUM((CASE WHEN doc_type='CREDIT_NOTE' THEN -1 ELSE 1 END)*(igst_paise+cgst_paise+sgst_paise+cess_paise)) END FROM gstr2b_doc WHERE tenant_id=? AND period=? AND itc_available=1 AND reverse_charge=0 AND section IN ('B2B','B2BA','CDNR')", Long.class, Tenant.current(), period);
        List<ScrutinyEngine.Row> rows = ScrutinyEngine.diff(filed, books);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filedReturn", Map.of("id", id, "period", period, "source", fr.get("source"), "confirmed", fr.get("confirmed"), "document_id", Objects.toString(fr.get("document_id"), "")));
        out.put("rows", rows);
        out.put("mismatches", rows.stream().filter(r -> r.status().equals("DIFF")).count());
        out.put("questions", ScrutinyEngine.questions(rows, sources, itc2b));
        out.put("warnings", g3.get("warnings"));
        out.put("explanation", fr.get("explanation"));
        if (((Number) fr.get("confirmed")).intValue() == 0) out.put("unconfirmedNotice", "These figures were transcribed from a PDF by Claude. Check them against the PDF and click Confirm before relying on the comparison.");
        return out;
    }

    /** Optional: one Sonnet call turning the deterministic diff into a plain-language explanation for the founder. */
    public String explain(long id) throws Exception {
        Map<String, Object> rep = report(id);
        String prompt = """
                You are helping a startup founder in India understand the GSTR-3B their CA filed for %s. Below are (1) the filed figures vs the figures
                recomputed from their own books by deterministic code, and (2) the open questions. Write a short plain-language explanation (max 250 words, bullet points ok):
                what each non-zero line of the filed return means for their business, where it differs from the books, and what they should ask.
                Do NOT recompute or invent any numbers — only use the numbers given. Cite the relevant section/rule when you mention a requirement.
                DATA: %s
                """.formatted(((Map<?, ?>) rep.get("filedReturn")).get("period"), om.writeValueAsString(Map.of("rows", rep.get("rows"), "questions", rep.get("questions"))));
        LlmResponse r = llm.call(new LlmRequest(props.getExtractionModel(), "filed-3b-explain", "filed_return:" + id, null, List.of(Map.of("type", "text", "text", prompt)), 2000));
        jdbc.update("UPDATE filed_return SET explanation=? WHERE id=?", r.text(), id);
        return r.text();
    }
}
