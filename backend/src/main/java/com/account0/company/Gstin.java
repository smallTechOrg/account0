package com.account0.company;

import java.util.Map;

/** GSTIN structure: 2-digit state + 10-char PAN + entity no + 'Z' + check char (mod-36 Luhn variant). */
public final class Gstin {
    private Gstin() {}
    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    public static final java.util.regex.Pattern FORMAT = java.util.regex.Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

    public static boolean isValid(String g) {
        if (g == null) return false;
        g = g.trim().toUpperCase();
        return FORMAT.matcher(g).matches() && checkChar(g.substring(0, 14)) == g.charAt(14);
    }

    public static char checkChar(String first14) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int v = CHARS.indexOf(first14.charAt(i)) * ((i % 2 == 0) ? 1 : 2);
            sum += v / 36 + v % 36;
        }
        return CHARS.charAt((36 - sum % 36) % 36);
    }

    public static String stateCode(String g) { return g == null || g.length() < 2 ? null : g.substring(0, 2); }
    public static String pan(String g) { return g == null || g.length() < 12 ? null : g.substring(2, 12); }

    public static final Map<String, String> STATES = Map.ofEntries(
            Map.entry("01", "Jammu and Kashmir"), Map.entry("02", "Himachal Pradesh"), Map.entry("03", "Punjab"),
            Map.entry("04", "Chandigarh"), Map.entry("05", "Uttarakhand"), Map.entry("06", "Haryana"), Map.entry("07", "Delhi"),
            Map.entry("08", "Rajasthan"), Map.entry("09", "Uttar Pradesh"), Map.entry("10", "Bihar"), Map.entry("11", "Sikkim"),
            Map.entry("12", "Arunachal Pradesh"), Map.entry("13", "Nagaland"), Map.entry("14", "Manipur"), Map.entry("15", "Mizoram"),
            Map.entry("16", "Tripura"), Map.entry("17", "Meghalaya"), Map.entry("18", "Assam"), Map.entry("19", "West Bengal"),
            Map.entry("20", "Jharkhand"), Map.entry("21", "Odisha"), Map.entry("22", "Chhattisgarh"), Map.entry("23", "Madhya Pradesh"),
            Map.entry("24", "Gujarat"), Map.entry("26", "Dadra and Nagar Haveli and Daman and Diu"), Map.entry("27", "Maharashtra"),
            Map.entry("29", "Karnataka"), Map.entry("30", "Goa"), Map.entry("31", "Lakshadweep"), Map.entry("32", "Kerala"),
            Map.entry("33", "Tamil Nadu"), Map.entry("34", "Puducherry"), Map.entry("35", "Andaman and Nicobar Islands"),
            Map.entry("36", "Telangana"), Map.entry("37", "Andhra Pradesh"), Map.entry("38", "Ladakh"), Map.entry("97", "Other Territory"),
            Map.entry("96", "Other Country"));
}
