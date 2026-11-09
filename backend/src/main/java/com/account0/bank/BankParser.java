package com.account0.bank;

import com.account0.common.ApiException;
import org.apache.poi.ss.usermodel.*;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Pure parsing of bank statements (CSV / XLS / XLSX) into rows + column mapping detection. */
public final class BankParser {
    private BankParser() {}

    /** Column indexes (-1 = absent). Either debit+credit columns, or a single amount column with optional Dr/Cr type column. */
    public record Mapping(int date, int description, int ref, int debit, int credit, int amount, int type, int balance) {}
    public record Table(List<String> headers, List<List<String>> rows) {}
    public record Txn(String date, String description, String ref, long debit, long credit, Long balance) {}

    static final Map<String, List<String>> SYN = new LinkedHashMap<>();
    static {
        SYN.put("date", List.of("txn date", "transaction date", "tran date", "date", "value date", "posting date"));
        SYN.put("description", List.of("narration", "description", "particulars", "transaction remarks", "remarks", "details", "transaction details"));
        SYN.put("ref", List.of("chq./ref.no.", "chq/ref no", "ref no", "reference", "cheque no", "chq no", "utr", "ref"));
        SYN.put("debit", List.of("withdrawal amt.", "withdrawal", "debit", "debit amount", "dr", "withdrawals", "paid out"));
        SYN.put("credit", List.of("deposit amt.", "deposit", "credit", "credit amount", "cr", "deposits", "paid in"));
        SYN.put("amount", List.of("amount", "transaction amount", "amt"));
        SYN.put("type", List.of("dr/cr", "type", "cr/dr", "transaction type"));
        SYN.put("balance", List.of("closing balance", "balance", "running balance", "available balance"));
    }

    static int find(List<String> headers, String field) {
        List<String> norm = headers.stream().map(h -> h.toLowerCase(Locale.ROOT).trim()).toList();
        for (String s : SYN.get(field)) { int i = norm.indexOf(s); if (i >= 0) return i; }
        for (String s : SYN.get(field)) for (int i = 0; i < norm.size(); i++) if (norm.get(i).contains(s) && s.length() > 3) return i;
        return -1;
    }
    public static Mapping detect(List<String> headers) {
        return new Mapping(find(headers, "date"), find(headers, "description"), find(headers, "ref"), find(headers, "debit"), find(headers, "credit"), find(headers, "amount"), find(headers, "type"), find(headers, "balance"));
    }

    public static Table read(byte[] bytes, String filename) {
        String f = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        List<List<String>> all = f.endsWith(".xlsx") || f.endsWith(".xls") ? readExcel(bytes) : readCsv(new String(bytes, StandardCharsets.UTF_8).replace("﻿", ""));
        // header row = first row that contains a date-ish and an amount-ish column name
        for (int i = 0; i < Math.min(all.size(), 30); i++) {
            Mapping m = detect(all.get(i));
            if (m.date() >= 0 && (m.debit() >= 0 || m.amount() >= 0) ) return new Table(all.get(i), all.subList(i + 1, all.size()));
        }
        throw ApiException.bad("Could not find a header row with a Date column and Debit/Credit or Amount column. Check the file or choose the columns manually.");
    }

    static List<List<String>> readCsv(String text) {
        List<List<String>> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            List<String> r = new ArrayList<>(); StringBuilder cur = new StringBuilder(); boolean q = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') { if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = !q; }
                else if (c == ',' && !q) { r.add(cur.toString().trim()); cur.setLength(0); } else cur.append(c);
            }
            r.add(cur.toString().trim()); out.add(r);
        }
        return out;
    }
    static List<List<String>> readExcel(byte[] b) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(b))) {
            Sheet sh = wb.getSheetAt(0); DataFormatter fmt = new DataFormatter(Locale.ENGLISH);
            List<List<String>> out = new ArrayList<>();
            for (Row row : sh) {
                List<String> r = new ArrayList<>();
                for (int c = 0; c < Math.max(row.getLastCellNum(), 0); c++) {
                    Cell cell = row.getCell(c);
                    if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) r.add(cell.getLocalDateTimeCellValue().toLocalDate().toString());
                    else if (cell != null && cell.getCellType() == CellType.NUMERIC) r.add(BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString());
                    else r.add(cell == null ? "" : fmt.formatCellValue(cell).trim());
                }
                if (r.stream().anyMatch(s -> !s.isBlank())) out.add(r);
            }
            return out;
        } catch (Exception e) { throw ApiException.bad("Could not read the spreadsheet: " + e.getMessage()); }
    }

    static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ofPattern("dd/MM/yyyy"), DateTimeFormatter.ofPattern("dd-MM-yyyy"), DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yy"), DateTimeFormatter.ofPattern("dd-MM-yy"), DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH), DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH), DateTimeFormatter.ofPattern("d/M/yyyy"));
    /** Indian statements are day-first. */
    public static String parseDate(String s) {
        String t = s == null ? "" : s.trim();
        for (DateTimeFormatter f : DATES) try { return LocalDate.parse(t, f).toString(); } catch (Exception ignored) {}
        throw new IllegalArgumentException("unrecognised date '" + s + "'");
    }
    /** "1,23,456.78", "1234.5 Dr", "(500.00)" -> paise magnitude; blank -> 0. */
    public static long amount(String s) {
        if (s == null) return 0;
        String t = s.replace(",", "").replace("₹", "").replaceAll("(?i)\\b(dr|cr)\\.?$", "").trim();
        if (t.isEmpty() || t.equals("-")) return 0;
        boolean neg = t.startsWith("(") && t.endsWith(")") || t.startsWith("-");
        t = t.replaceAll("[()\\-]", "");
        long v = new BigDecimal(t).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        return neg ? -v : v;
    }

    public static List<Txn> parse(Table t, Mapping m) {
        if (m.date() < 0 || m.description() < 0 && false) throw ApiException.bad("Date column is required");
        if (m.debit() < 0 && m.credit() < 0 && m.amount() < 0) throw ApiException.bad("Map either Debit/Credit columns or an Amount column.");
        List<Txn> out = new ArrayList<>(); int line = 1;
        for (List<String> r : t.rows()) {
            line++;
            String ds = cell(r, m.date());
            if (ds.isBlank()) continue;
            String low = ds.toLowerCase(Locale.ROOT);
            if (low.startsWith("opening") || low.startsWith("closing") || low.startsWith("total") || low.startsWith("statement")) continue;
            String date;
            try { date = parseDate(ds); } catch (IllegalArgumentException e) { if (out.isEmpty()) continue; throw ApiException.bad("Row " + (line) + ": " + e.getMessage()); }
            long debit = 0, credit = 0;
            try {
                if (m.debit() >= 0 || m.credit() >= 0) { debit = Math.abs(amount(cell(r, m.debit()))); credit = Math.abs(amount(cell(r, m.credit()))); }
                else {
                    String raw = cell(r, m.amount()); long a = amount(raw);
                    String type = (m.type() >= 0 ? cell(r, m.type()) : raw).toLowerCase(Locale.ROOT);
                    boolean dr = type.contains("dr") || type.contains("debit") || type.contains("withdraw") || a < 0 && !type.contains("cr");
                    if (dr) debit = Math.abs(a); else credit = Math.abs(a);
                }
            } catch (ApiException e) { throw e; } catch (Exception e) { throw ApiException.bad("Row " + line + ": amount is not a number"); }
            if (debit == 0 && credit == 0) continue;
            Long bal = null;
            if (m.balance() >= 0 && !cell(r, m.balance()).isBlank()) try { bal = amount(cell(r, m.balance())); } catch (Exception ignored) {}
            out.add(new Txn(date, cell(r, m.description()), cell(r, m.ref()), debit, credit, bal));
        }
        if (out.isEmpty()) throw ApiException.bad("No transactions found with this column mapping.");
        return out;
    }
    static String cell(List<String> r, int i) { return i < 0 || i >= r.size() ? "" : r.get(i); }
}
