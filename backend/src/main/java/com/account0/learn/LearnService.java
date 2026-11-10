package com.account0.learn;

import com.account0.common.ApiException;
import com.account0.common.Tenant;
import com.account0.company.CompanyService;
import com.account0.compliance.CloseService;
import com.account0.gst.GstService;
import com.account0.llm.*;
import com.account0.scrutiny.ScrutinyService;
import com.account0.tds.TdsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.*;
import java.util.regex.*;

@Service
public class LearnService {
    private final Corpus corpus; private final JdbcTemplate jdbc; private final GstService gst; private final LlmGateway llm; private final LlmProperties props; private final ObjectMapper om;
    private final CompanyService company; private final CloseService close; private final ScrutinyService scrutiny; private final TdsService tds;
    public LearnService(Corpus corpus, JdbcTemplate jdbc, GstService gst, LlmGateway llm, LlmProperties props, ObjectMapper om, CompanyService company, CloseService close, ScrutinyService scrutiny, TdsService tds) {
        this.corpus = corpus; this.jdbc = jdbc; this.gst = gst; this.llm = llm; this.props = props; this.om = om; this.company = company; this.close = close; this.scrutiny = scrutiny; this.tds = tds;
    }

    static String rs(long p) { return "₹" + String.format(Locale.ROOT, "%,.2f", p / 100.0); }
    Map<String, Object> cite(String id) {
        Corpus.Entry e = corpus.get(id); if (e == null) throw new IllegalStateException("unknown corpus id " + id);
        return Map.of("id", e.id(), "title", e.title(), "citation", e.citation(), "text", e.text());
    }
    List<Map<String, Object>> cites(String... ids) { return Arrays.stream(ids).map(this::cite).toList(); }

    // ---------------------------------------------------------------- Q&A
    static final Pattern PERIOD = Pattern.compile("\\b(20\\d{2})-(0[1-9]|1[0-2])\\b");
    static final Pattern MONTH_YEAR = Pattern.compile("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(20\\d{2})\\b", Pattern.CASE_INSENSITIVE);

    public static String detectPeriod(String q) {
        Matcher m = PERIOD.matcher(q); if (m.find()) return m.group(1) + "-" + m.group(2);
        m = MONTH_YEAR.matcher(q);
        if (m.find()) { int mo = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(m.group(1).toLowerCase(Locale.ROOT)) + 1; return m.group(2) + "-" + String.format("%02d", mo); }
        return null;
    }

    /** Facts computed by code from the user's own confirmed data, attached so the model can speak about THEIR numbers without computing anything. */
    @SuppressWarnings("unchecked")
    List<String> facts(String question) {
        List<String> f = new ArrayList<>();
        String period = detectPeriod(question);
        if (period != null) {
            try {
                Map<String, Object> g = gst.gstr3b(period);
                for (String t : List.of("table31", "table4"))
                    for (Map<String, Object> fig : (List<Map<String, Object>>) g.get(t)) {
                        long tx = ((Number) fig.get("taxable_paise")).longValue(), ig = ((Number) fig.get("igst_paise")).longValue(), cg = ((Number) fig.get("cgst_paise")).longValue(), sg = ((Number) fig.get("sgst_paise")).longValue();
                        if (tx + ig + cg + sg == 0) continue;
                        f.add("GSTR-3B " + period + " " + fig.get("label") + ": taxable " + rs(tx) + ", IGST " + rs(ig) + ", CGST " + rs(cg) + ", SGST " + rs(sg) + " (rule: " + fig.get("rule") + ")");
                    }
                Map<String, Object> pay = (Map<String, Object>) g.get("payment61");
                f.add("GSTR-3B " + period + " total cash payable: " + rs(((Number) pay.get("cashTotalPaise")).longValue()));
            } catch (Exception ignored) {}
        }
        String compact = question.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        for (Map<String, Object> inv : jdbc.queryForList("SELECT invoice_no, invoice_date, direction, counterparty_name, supply_type, reverse_charge, taxable_paise, igst_paise, cgst_paise, sgst_paise, status FROM invoice WHERE tenant_id=? AND invoice_no IS NOT NULL AND status<>'REJECTED'", Tenant.current())) {
            String no = String.valueOf(inv.get("invoice_no")).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            if (no.length() >= 4 && compact.contains(no))
                f.add("Invoice " + inv.get("invoice_no") + " (" + inv.get("direction") + ", " + inv.get("counterparty_name") + ", " + inv.get("invoice_date") + "): supply type " + inv.get("supply_type") + ", reverse charge " + inv.get("reverse_charge") + ", taxable " + rs(((Number) inv.get("taxable_paise")).longValue())
                        + ", IGST " + rs(((Number) inv.get("igst_paise")).longValue()) + ", CGST " + rs(((Number) inv.get("cgst_paise")).longValue()) + ", SGST " + rs(((Number) inv.get("sgst_paise")).longValue()) + ", status " + inv.get("status"));
        }
        return f;
    }

    static final Pattern CITE = Pattern.compile("\\[([A-Za-z0-9_.\\-]+)\\]");

    public Map<String, Object> ask(String question) throws LlmException {
        if (question == null || question.isBlank()) throw ApiException.bad("Ask a question.");
        if (question.length() > 600) throw ApiException.bad("Keep the question under 600 characters.");
        List<Corpus.Hit> hits = corpus.search(question, 5);
        Map<String, Object> out = new LinkedHashMap<>();
        if (hits.isEmpty()) {
            String msg = "I don't have a reliable source for that in Account0's reference library, so I won't guess. Please ask your CA — or rephrase using the tax topic (e.g. reverse charge, ITC, TDS 194J, due dates).";
            jdbc.update("INSERT INTO qa_log(tenant_id, question, answer, cited_json, grounded) VALUES (?,?,?,?,0)", Tenant.current(), question, msg, "[]");
            out.put("answered", false); out.put("answer", msg); out.put("citations", List.of());
            return out;
        }
        List<String> facts = facts(question);
        StringBuilder src = new StringBuilder();
        for (Corpus.Hit h : hits) src.append("[").append(h.entry().id()).append("] ").append(h.entry().title()).append(" (").append(h.entry().citation()).append("): ").append(h.entry().text()).append("\n");
        String prompt = "SOURCES (the only authority you may rely on):\n" + src + "\nFACTS ABOUT THIS COMPANY (computed by software; do not recompute):\n" + (facts.isEmpty() ? "(none)" : String.join("\n", facts))
                + "\n\nQUESTION: " + question
                + "\n\nAnswer in at most 180 words, plain language for a non-accountant founder. Every statement of law must carry a citation tag like [cgst-16] using ONLY the ids above. "
                + "If the sources do not answer the question, say so and recommend asking the CA. Never do arithmetic; quote numbers from FACTS only. Never decide tax treatment - say it is for the founder/CA to confirm.";
        LlmResponse r = llm.call(new LlmRequest(props.getExtractionModel(), "learn-qa", "qa", "You are a careful Indian tax and compliance tutor. You answer strictly from the supplied sources.", List.of(Map.of("type", "text", "text", prompt)), 700));
        String answer = r.text().trim();
        Set<String> allowed = new HashSet<>(); hits.forEach(h -> allowed.add(h.entry().id()));
        Set<String> used = new LinkedHashSet<>(), bad = new LinkedHashSet<>();
        Matcher m = CITE.matcher(answer);
        while (m.find()) { if (allowed.contains(m.group(1))) used.add(m.group(1)); else bad.add(m.group(1)); }
        for (String b : bad) answer = answer.replace("[" + b + "]", "");
        boolean grounded = !used.isEmpty();
        List<Map<String, Object>> cit = new ArrayList<>();
        // cite what the answer used; if the model cited nothing valid, still show the sources it was given, flagged as ungrounded
        for (String id : grounded ? used : allowed) cit.add(cite(id));
        out.put("answered", true); out.put("grounded", grounded); out.put("answer", answer); out.put("citations", cit);
        if (!grounded) out.put("warning", "The model did not cite a source, so treat this answer with caution. The most relevant sources are listed - read them directly or ask your CA.");
        if (!bad.isEmpty()) out.put("removedCitations", bad);
        out.put("disclaimer", "Reference summaries are maintained by Account0 and are not legal advice. Check the bare Act or ask your CA before acting.");
        if (!facts.isEmpty()) out.put("usedFacts", facts);
        try { jdbc.update("INSERT INTO qa_log(tenant_id, question, answer, cited_json, grounded) VALUES (?,?,?,?,?)", Tenant.current(), question, answer, om.writeValueAsString(used), grounded ? 1 : 0); } catch (Exception ignored) {}
        return out;
    }

    public List<Map<String, Object>> history() { return jdbc.queryForList("SELECT id, question, answer, cited_json, grounded, created_at FROM qa_log WHERE tenant_id=? ORDER BY id DESC LIMIT 30", Tenant.current()); }

    public List<Map<String, Object>> search(String q) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Corpus.Hit h : corpus.search(q, 8)) out.add(Map.of("id", h.entry().id(), "title", h.entry().title(), "citation", h.entry().citation(), "text", h.entry().text(), "kind", h.entry().kind(), "score", Math.round(h.score() * 10) / 10.0));
        return out;
    }
    public Map<String, Object> why() {
        Map<String, Object> out = new LinkedHashMap<>();
        WhyNotes.ALL.forEach((k, n) -> out.put(k, Map.of("title", n.title(), "text", n.text(), "citations", cites(n.cites().toArray(new String[0])))));
        return out;
    }
    public List<Map<String, Object>> library() { return corpus.all().stream().map(e -> Map.<String, Object>of("id", e.id(), "title", e.title(), "citation", e.citation(), "text", e.text(), "kind", e.kind())).toList(); }

    // ---------------------------------------------------------------- walkthroughs on the user's own data
    private Map<String, Object> step(String title, String text, List<Map<String, Object>> figures, List<Map<String, Object>> cites, String link) {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("title", title); m.put("text", text); m.put("figures", figures); m.put("citations", cites); m.put("link", link); return m;
    }
    private Map<String, Object> fig(String label, long paise) { return Map.of("label", label, "paise", paise); }

    @SuppressWarnings("unchecked")
    public Map<String, Object> walkthrough(String topic, String period) {
        if (period == null || !period.matches("\\d{4}-(0[1-9]|1[0-2])")) throw ApiException.bad("period must be YYYY-MM");
        switch (topic) {
            case "gstr3b" -> { return gstr3bWalk(period); }
            case "rcm" -> { return rcmWalk(period); }
            case "itc" -> { return itcWalk(period); }
            case "tds" -> { return tdsWalk(period); }
            default -> throw ApiException.bad("topic must be one of gstr3b, rcm, itc, tds");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> gstr3bWalk(String period) {
        Map<String, Object> g = gst.gstr3b(period);
        Map<String, Map<String, Object>> f = new HashMap<>();
        for (String t : List.of("table31", "table4")) for (Map<String, Object> x : (List<Map<String, Object>>) g.get(t)) f.put((String) x.get("key"), x);
        List<Map<String, Object>> steps = new ArrayList<>();
        YearMonthLabel ml = new YearMonthLabel(period);
        steps.add(step("What GSTR-3B is", "GSTR-3B is the monthly summary return. For " + ml.label + " it is due on " + ml.nextMonth(20) + " and the tax is paid with it. Below, each box is rebuilt from YOUR confirmed invoices, so you can see where every number comes from.", List.of(), cites("cgst-39"), "/gst"));
        for (String k : List.of("3.1a", "3.1b", "3.1c", "3.1d")) {
            Map<String, Object> x = f.get(k); List<Map<String, Object>> src = (List<Map<String, Object>>) x.get("sources");
            long tx = ((Number) x.get("taxable_paise")).longValue(), tax = ((Number) x.get("igst_paise")).longValue() + ((Number) x.get("cgst_paise")).longValue() + ((Number) x.get("sgst_paise")).longValue();
            String names = src.stream().limit(5).map(s -> s.get("invoice_no") + " (" + s.get("party") + ")").reduce((a, b) -> a + ", " + b).orElse("");
            String text = src.isEmpty() ? "You have no invoices in this box for " + ml.label + ". That is normal if nothing of this kind happened." : src.size() + " invoice(s) feed this box: " + names + ". " + x.get("rule");
            if (k.equals("3.1b")) { Object lut = company.get().get("lut_arn"); text += src.isEmpty() ? "" : " LUT on file: " + (lut == null || lut.toString().isBlank() ? "NONE RECORDED - exports without a valid LUT must be taxed, add it in Company profile." : lut + "."); }
            steps.add(step(x.get("label") + "", text, List.of(fig("Taxable value", tx), fig("Tax in this box", tax)), cites(WhyNotes.ALL.get(k).cites().toArray(new String[0])), "/gst"));
        }
        long itcNet = ((Number) f.get("4C").get("igst_paise")).longValue() + ((Number) f.get("4C").get("cgst_paise")).longValue() + ((Number) f.get("4C").get("sgst_paise")).longValue();
        steps.add(step("Table 4 - your input tax credit", "Eligible ITC this month comes from domestic purchases (4A5), import of services you paid RCM on (4A2) and other RCM (4A3), less blocked or reversed credit (4B1). " + f.get("4C").get("rule"), List.of(fig("Net ITC available", itcNet)), cites("cgst-16", "cgst-17-5", "gstr2b"), "/itc"));
        Map<String, Object> pay = (Map<String, Object>) g.get("payment61");
        long cash = ((Number) pay.get("cashTotalPaise")).longValue();
        List<Map<String, Object>> figs = new ArrayList<>();
        for (Map<String, Object> h : (List<Map<String, Object>>) pay.get("heads")) figs.add(fig(h.get("head") + " paid in cash", ((Number) h.get("cashPaise")).longValue() + ((Number) h.get("rcmCashPaise")).longValue()));
        steps.add(step("Table 6.1 - how the tax is paid", pay.get("rule") + "", figs, cites("cgst-49", "cgst-rule-88a", "term-setoff"), "/gst"));
        steps.add(step("What to put into the cash ledger", "Before filing, the electronic cash ledger must hold at least the cash payable. Late payment attracts interest at 18% a year.", List.of(fig("Total cash payable", cash)), cites("cgst-50"), "/gst"));
        steps.add(step("Before you approve your CA's return", "Upload what your CA filed and compare line by line - any difference becomes a question to ask. If both agree, you know the return is right without redoing it.", List.of(), List.of(), "/scrutiny"));
        return Map.of("topic", "gstr3b", "period", period, "title", "How your GSTR-3B for " + ml.label + " is built", "steps", steps, "empty", ((List<?>) ((Map<String, Object>) f.get("3.1a")).get("sources")).isEmpty() && ((List<?>) f.get("3.1b").get("sources")).isEmpty() && ((List<?>) f.get("3.1d").get("sources")).isEmpty());
    }

    private Map<String, Object> rcmWalk(String period) {
        YearMonthLabel ml = new YearMonthLabel(period);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, invoice_no, invoice_date, counterparty_name, counterparty_country, taxable_paise, igst_paise, cgst_paise, sgst_paise, itc_eligible, currency, foreign_amount, fx_rate FROM invoice WHERE tenant_id=? AND status='CONFIRMED' AND direction='PURCHASE' AND reverse_charge=1 AND period=? ORDER BY invoice_date", Tenant.current(), period);
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step("Why you owe GST on some purchases", "When a vendor outside India supplies you a service (software subscription, consulting), the vendor does not charge Indian GST. The law makes YOU pay it: this is the reverse charge. Then you claim it back as input credit - so the net cost is zero, but cash goes out first.", List.of(), cites("term-rcm", "igst-5-3", "igst-2-10"), "/review"));
        long totalTax = 0;
        for (Map<String, Object> r : rows) {
            long tax = ((Number) r.get("igst_paise")).longValue() + ((Number) r.get("cgst_paise")).longValue() + ((Number) r.get("sgst_paise")).longValue(); totalTax += tax;
            String fx = r.get("foreign_amount") != null ? " Invoice value " + r.get("currency") + " " + r.get("foreign_amount") + " converted at the rate you confirmed (" + r.get("fx_rate") + ")." : "";
            steps.add(step(r.get("invoice_no") + " - " + r.get("counterparty_name"), "Taxable value " + rs(((Number) r.get("taxable_paise")).longValue()) + " in INR." + fx + " The tax shown was computed by software at the rate you confirmed; ITC eligible: " + (Objects.equals(r.get("itc_eligible"), 1) ? "yes (you confirmed)" : "no / not confirmed") + ".",
                    List.of(fig("Taxable value", ((Number) r.get("taxable_paise")).longValue()), fig("RCM tax", tax)), cites("igst-5-3"), "/review/" + r.get("id")));
        }
        if (rows.isEmpty()) steps.add(step("No reverse-charge purchases in " + ml.label, "You have none confirmed for this month. If you did buy from a foreign vendor, check that its invoice is uploaded and confirmed as 'import of service - RCM'.", List.of(), List.of(), "/inbox"));
        steps.add(step("Where it appears in the return and when you pay", "Report the tax in GSTR-3B Table 3.1(d) and pay it in CASH by the 20th of the next month (credit balance cannot be used for RCM). After paying, claim the same tax as ITC in Table 4(A)(2).", List.of(fig("RCM tax to pay in cash", totalTax)), cites("cgst-39", "cgst-49", "cgst-16"), "/gst"));
        return Map.of("topic", "rcm", "period", period, "title", "Reverse charge on your purchases in " + ml.label, "steps", steps, "empty", rows.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> itcWalk(String period) {
        Map<String, Object> g = gst.gstr3b(period); YearMonthLabel ml = new YearMonthLabel(period);
        Map<String, Object> f = null;
        for (Map<String, Object> x : (List<Map<String, Object>>) g.get("table4")) if ("4A5".equals(x.get("key"))) f = x;
        List<Map<String, Object>> src = (List<Map<String, Object>>) f.get("sources");
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step("How input credit works", "The GST on your business purchases can reduce the GST you owe - but only for invoices that are genuine, not blocked and present in GSTR-2B.", List.of(), cites("term-itc", "cgst-16", "cgst-16-2-aa"), "/itc"));
        long itc = ((Number) f.get("igst_paise")).longValue() + ((Number) f.get("cgst_paise")).longValue() + ((Number) f.get("sgst_paise")).longValue();
        steps.add(step("Your domestic purchases with ITC in " + ml.label, src.isEmpty() ? "None confirmed." : src.stream().map(s -> s.get("invoice_no") + " from " + s.get("party")).reduce((a, b) -> a + "; " + b).get() + ".", List.of(fig("ITC claimed (4A5)", itc)), cites("cgst-16"), "/invoices"));
        List<Map<String, Object>> m = jdbc.queryForList("SELECT match_status, COUNT(*) n FROM gstr2b_doc WHERE tenant_id=? AND period=? GROUP BY match_status", Tenant.current(), period);
        steps.add(step("Checking against GSTR-2B", m.isEmpty() ? "You have not imported GSTR-2B for " + ml.label + ". Download it from the GST portal (Returns > GSTR-2B > Download JSON) after the 14th and import it." : "2B results: " + m.stream().map(x -> x.get("match_status") + "=" + x.get("n")).reduce((a, b) -> a + ", " + b).get() + ". Anything 'in books, not in 2B' cannot be claimed until the vendor files.", List.of(), cites("gstr2b", "cgst-16-2-aa"), "/itc"));
        steps.add(step("Blocked and time-barred credit", "Even a perfect invoice can be ineligible: food, personal use, motor vehicles and similar items are blocked, and credit expires on 30 November after the financial year.", List.of(), cites("cgst-17-5", "cgst-16-4"), "/review"));
        return Map.of("topic", "itc", "period", period, "title", "Your input tax credit in " + ml.label, "steps", steps, "empty", src.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tdsWalk(String period) {
        YearMonthLabel ml = new YearMonthLabel(period); String fy = TdsService.fyOf(period + "-01");
        List<Map<String, Object>> rows = tds.register(fy, null).stream().filter(r -> String.valueOf(r.get("payment_date")).startsWith(period)).toList();
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step("When you must deduct TDS", "When you pay or credit certain vendors above a yearly threshold, you hold back a percentage and deposit it with the Government by the 7th of the next month. You choose the section for each vendor; software applies the rate.", List.of(), cites("it-194j", "it-tds-deposit", "cbdt-23-2017"), "/tds"));
        for (Map<String, Object> r : rows)
            steps.add(step(r.get("invoice_no") + " - " + r.get("payee_name") + " (" + r.get("section") + ")", "Base " + rs(((Number) r.get("base_paise")).longValue()) + " (GST excluded). " + r.get("reason"), List.of(fig("Base", ((Number) r.get("base_paise")).longValue()), fig("TDS", ((Number) r.get("tds_paise")).longValue())), cites("it-194j", "it-206aa"), "/tds"));
        if (rows.isEmpty()) steps.add(step("No TDS decisions for " + ml.label, "Open the TDS register and choose a section (or 'not applicable') for each purchase.", List.of(), List.of(), "/tds"));
        steps.add(step("Payments abroad", "For payments to foreign vendors, TDS under Sec 195 may apply depending on the nature of the payment and the tax treaty. Account0 does not compute it - this is a question for your CA before you remit.", List.of(), cites("it-195"), "/tds"));
        return Map.of("topic", "tds", "period", period, "title", "TDS on your payments in " + ml.label, "steps", steps, "empty", rows.isEmpty());
    }

    static final class YearMonthLabel {
        final String period, label;
        YearMonthLabel(String period) { this.period = period; this.label = Month.of(Integer.parseInt(period.substring(5))).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + period.substring(0, 4); }
        String nextMonth(int day) { java.time.YearMonth n = java.time.YearMonth.parse(period).plusMonths(1); return n.atDay(day).toString(); }
    }

    /** Optional: one Claude call turning a deterministic walkthrough into friendlier prose. Numbers come only from the walkthrough. */
    public String narrate(String topic, String period) throws LlmException {
        Map<String, Object> w = walkthrough(topic, period);
        try {
            String prompt = "Rewrite this walkthrough as a short friendly explanation (max 220 words) for a first-time founder. Use ONLY the facts and numbers below; do not compute anything new; keep citation ids in [brackets] if you mention a rule.\n" + om.writeValueAsString(w.get("steps"));
            return llm.call(new LlmRequest(props.getExtractionModel(), "learn-narrate", topic + ":" + period, null, List.of(Map.of("type", "text", "text", prompt)), 900)).text();
        } catch (LlmException e) { throw e; } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ---------------------------------------------------------------- DIY coaching
    private Set<String> done(String period) { return new HashSet<>(jdbc.queryForList("SELECT step_key FROM diy_progress WHERE tenant_id=? AND period=? AND done=1", String.class, Tenant.current(), period)); }

    @SuppressWarnings("unchecked")
    public Map<String, Object> diy(String period) {
        if (period == null || !period.matches("\\d{4}-(0[1-9]|1[0-2])")) throw ApiException.bad("period must be YYYY-MM");
        YearMonthLabel ml = new YearMonthLabel(period);
        Map<String, Object> g3 = gst.gstr3b(period);
        Map<String, Object> closeState = close.checklist(period);
        Set<String> done = done(period);
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(diyStep(done, "CLOSE", "Close the month in Account0", "Work through the month-close checklist first. " + closeState.get("open") + " item(s) still open. Do not file while invoices are still in the review queue - the portal will not tell you what you missed.",
                List.of(), cites("acc-confirm"), "/close"));
        steps.add(diyStep(done, "GSTR1", "File GSTR-1 (due " + ml.nextMonth(11) + ")",
                "1) Download the GSTR-1 JSON from Account0 (GST page > GSTR-1 JSON). 2) On the GST portal: Services > Returns > Returns Dashboard > choose the financial year and period > GSTR-1 > 'Prepare Offline' > upload the JSON. 3) After the upload, open the portal's summary and compare the totals with the figures below - if they differ, do not file; find the invoice. 4) Generate the summary, then 'File with DSC/EVC' (companies must use DSC). 5) Note the ARN and record it in Filing history. (Portal screens change; this is the usual flow.)",
                List.of(fig("Outward taxable value (3.1a)", ((Number) ((Map<String, Object>) ((List<Map<String, Object>>) g3.get("table31")).get(0)).get("taxable_paise")).longValue()), fig("Zero-rated value (3.1b)", ((Number) ((Map<String, Object>) ((List<Map<String, Object>>) g3.get("table31")).get(1)).get("taxable_paise")).longValue())), cites("cgst-37"), "/gst"));
        steps.add(diyStep(done, "GSTR2B", "Import GSTR-2B (available from the 14th)", "Portal: Returns Dashboard > GSTR-2B > Download > JSON. Import it under GSTR-2B / ITC match and clear every difference before claiming ITC.", List.of(), cites("gstr2b", "cgst-16-2-aa"), "/itc"));
        long cash = ((Number) ((Map<String, Object>) g3.get("payment61")).get("cashTotalPaise")).longValue();
        steps.add(diyStep(done, "GSTR3B", "Prepare GSTR-3B (due " + ml.nextMonth(20) + ")", "On the portal: Returns Dashboard > GSTR-3B > 'Prepare Online'. Enter each box from Account0's GST page (click any number to see its invoices). In Table 6.1 the portal shows the liability and your ITC; confirm the cash payable below matches before you pay.",
                List.of(fig("Cash payable (incl. RCM)", cash)), cites("cgst-39", "cgst-49"), "/gst"));
        steps.add(diyStep(done, "PAY", "Pay the cash liability", "Create a challan from 'Payment of Tax' > 'Create Challan' (net banking/NEFT/RTGS) for the cash amount, split by head as in Table 6.1. The credit lands in the cash ledger instantly for net banking, the next working day for others. File GSTR-3B only after the ledger shows the money.",
                List.of(fig("Cash to deposit", cash)), cites("cgst-50", "term-setoff"), "/gst"));
        steps.add(diyStep(done, "FILE3B", "File GSTR-3B with DSC/EVC", "Submit, then 'File GSTR-3B' and sign. Download the filed PDF and upload it to Account0's CA scrutiny page to confirm that the filed return equals your recompute.", List.of(), cites("term-dsc-evc"), "/scrutiny"));
        steps.add(diyStep(done, "RECORD", "Record the filing and lock the month", "Enter the ARN in Filing history for GSTR-1 and GSTR-3B. This locks the month against accidental edits.", List.of(), cites("acc-lock"), "/filings"));
        steps.add(diyStep(done, "TDS", "Deposit TDS by the 7th", "Pay Challan 281 for TDS deducted last month (salary and vendors). Record the challan in the TDS register.", List.of(), cites("it-tds-deposit"), "/tds"));
        long doneCount = steps.stream().filter(s -> Boolean.TRUE.equals(s.get("done"))).count();
        return Map.of("period", period, "title", "File " + ml.label + " yourself - guided", "steps", steps, "progress", Map.of("done", doneCount, "total", steps.size()), "readiness", readiness());
    }
    private Map<String, Object> diyStep(Set<String> done, String key, String title, String text, List<Map<String, Object>> figs, List<Map<String, Object>> cites, String link) {
        Map<String, Object> m = step(title, text, figs, cites, link); m.put("key", key); m.put("done", done.contains(key)); return m;
    }
    public Map<String, Object> diyMark(String period, String key, boolean doneFlag) {
        if (!List.of("CLOSE", "GSTR1", "GSTR2B", "GSTR3B", "PAY", "FILE3B", "RECORD", "TDS").contains(key)) throw ApiException.bad("unknown step " + key);
        jdbc.update("INSERT INTO diy_progress(tenant_id, period, step_key, done) VALUES (?,?,?,?) ON CONFLICT(tenant_id, period, step_key) DO UPDATE SET done=excluded.done, updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now')", Tenant.current(), period, key, doneFlag ? 1 : 0);
        return diy(period);
    }

    /** Shadow-mode confidence: in how many months did your own recompute agree with what the CA filed? */
    public Map<String, Object> readiness() {
        List<Map<String, Object>> months = new ArrayList<>();
        int agree = 0;
        for (Map<String, Object> fr : jdbc.queryForList("SELECT id, period FROM filed_return WHERE tenant_id=? AND confirmed=1 AND id IN (SELECT MAX(id) FROM filed_return WHERE tenant_id=? GROUP BY period) ORDER BY period", Tenant.current(), Tenant.current())) {
            try {
                long mism = ((Number) scrutiny.report(((Number) fr.get("id")).longValue()).get("mismatches")).longValue();
                if (mism == 0) agree++;
                months.add(Map.of("period", fr.get("period"), "mismatches", mism));
            } catch (Exception ignored) {}
        }
        int n = months.size();
        String verdict = n < 3 ? "Keep going in shadow mode: let your CA file and compare. You need at least 3 compared months before filing yourself."
                : agree == n ? "Your recompute agreed with the CA in all " + n + " compared months. You are ready to try filing yourself with the CA as a backup for one more month."
                : "Your recompute disagreed with the CA in " + (n - agree) + " of " + n + " months. Resolve those differences (they are either your data or the CA's filing) before filing yourself.";
        return Map.of("comparedMonths", n, "agreeingMonths", agree, "months", months, "verdict", verdict);
    }
}
