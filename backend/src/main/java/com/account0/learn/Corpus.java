package com.account0.learn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.*;

/** Curated local corpus (sections, rules, circulars, glossary) + deterministic keyword retrieval. Summaries are maintained by Account0, not legal advice. */
@Component
public class Corpus {
    public record Entry(String id, String title, String citation, String keywords, String text, String kind) {}
    private final List<Entry> entries;
    private final Map<String, Entry> byId = new LinkedHashMap<>();
    private final Map<String, Double> idf = new HashMap<>();

    static final Set<String> STOP = Set.of("a", "an", "the", "is", "are", "was", "be", "to", "of", "in", "on", "for", "and", "or", "do", "does", "did", "i", "my", "we", "our", "me", "what", "how", "when", "why", "which", "who", "can", "should", "need", "it", "this", "that", "with", "about", "from", "by", "at", "as", "if", "so", "there", "have", "has", "will", "would", "much", "many", "any", "please", "tell", "explain", "mean", "means", "get", "pay", "paid", "after", "before", "than", "then");
    static final Map<String, List<String>> SYN = Map.ofEntries(
            Map.entry("rcm", List.of("reverse", "charge")), Map.entry("foreign", List.of("import", "non", "resident", "outside")), Map.entry("overseas", List.of("import", "outside")),
            Map.entry("credit", List.of("itc")), Map.entry("input", List.of("itc")), Map.entry("export", List.of("zero", "lut")), Map.entry("exports", List.of("zero", "lut", "export")),
            Map.entry("salary", List.of("192")), Map.entry("contractor", List.of("194c")), Map.entry("consultant", List.of("194j", "professional")), Map.entry("freelancer", List.of("194j", "professional")),
            Map.entry("rent", List.of("194i")), Map.entry("commission", List.of("194h")), Map.entry("deadline", List.of("due", "date")), Map.entry("penalty", List.of("late", "fee", "interest")),
            Map.entry("late", List.of("fee", "interest")), Map.entry("filing", List.of("return", "due")), Map.entry("tds", List.of("deduct")), Map.entry("agm", List.of("annual", "general", "meeting")),
            Map.entry("saas", List.of("software", "import", "services")), Map.entry("undertaking", List.of("lut")), Map.entry("2b", List.of("gstr-2b", "gstr2b")), Map.entry("3b", List.of("gstr-3b", "gstr3b")),
            Map.entry("gstr3b", List.of("gstr-3b")), Map.entry("gstr1", List.of("gstr-1")), Map.entry("blocked", List.of("17", "ineligible")));

    public Corpus(ObjectMapper om) throws Exception {
        try (var in = new ClassPathResource("corpus/corpus.json").getInputStream()) { entries = om.readValue(in, new TypeReference<List<Entry>>() {}); }
        entries.forEach(e -> byId.put(e.id(), e));
        Map<String, Integer> df = new HashMap<>();
        for (Entry e : entries) for (String t : new HashSet<>(tokens(e.title() + " " + e.keywords() + " " + e.text() + " " + e.citation()))) df.merge(t, 1, Integer::sum);
        for (var d : df.entrySet()) idf.put(d.getKey(), Math.log(1 + (double) entries.size() / d.getValue()));
    }
    public List<Entry> all() { return entries; }
    public Entry get(String id) { return byId.get(id); }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.\\-]", " ").split("\\s+")) {
            w = w.replaceAll("^[.\\-]+|[.\\-]+$", "");
            if (w.length() < 2 || STOP.contains(w)) continue;
            out.add(w);
            if (w.endsWith("s") && w.length() > 4) out.add(w.substring(0, w.length() - 1));
            if (w.contains("-")) out.add(w.replace("-", ""));
        }
        return out;
    }

    public record Hit(Entry entry, double score) {}
    /** Top-k entries; returns empty when nothing in the corpus is genuinely relevant (so the caller can decline instead of guessing). */
    public List<Hit> search(String query, int k) {
        Set<String> q = new LinkedHashSet<>();
        for (String t : tokens(query)) { q.add(t); for (String s : SYN.getOrDefault(t, List.of())) q.add(s); }
        // base words the user actually typed (stop words removed) - an entry must cover a fair share of them, so one incidental word ("good" ~ "goods") is not enough
        List<String> base = new ArrayList<>();
        for (String w : query.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.\\-]", " ").split("\\s+")) { w = w.replaceAll("^[.\\-]+|[.\\-]+$", ""); if (w.length() >= 2 && !STOP.contains(w) && !base.contains(w)) base.add(w); }
        List<Hit> hits = new ArrayList<>();
        for (Entry e : entries) {
            Set<String> title = new HashSet<>(tokens(e.title())), kw = new HashSet<>(tokens(e.keywords())), text = new HashSet<>(tokens(e.text())), cite = new HashSet<>(tokens(e.citation()));
            double score = 0; int strong = 0;
            for (String t : q) {
                double w = idf.getOrDefault(t, 0.0);
                if (title.contains(t)) { score += 3 * w; strong++; }
                else if (kw.contains(t)) { score += 2 * w; strong++; }
                else if (cite.contains(t)) { score += 2 * w; strong++; }
                else if (text.contains(t)) score += 0.7 * w;
            }
            int covered = 0;
            Set<String> all = new HashSet<>(); all.addAll(title); all.addAll(kw); all.addAll(text); all.addAll(cite);
            for (String w : base) { boolean hit = all.contains(w); if (!hit) for (String t : tokens(w)) if (all.contains(t)) hit = true; if (!hit) for (String t : SYN.getOrDefault(w, List.of())) if (all.contains(t)) hit = true; if (hit) covered++; }
            boolean enough = !base.isEmpty() && covered >= (base.size() >= 3 ? 2 : 1) && covered * 10 >= base.size() * 2;
            if (strong >= 1 && score >= 3.0 && enough) hits.add(new Hit(e, score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() > k ? hits.subList(0, k) : hits;
    }
}
