package com.account0.invoice;

/** severity: ERROR blocks confirmation; WARN needs explicit acknowledgement; INFO is a hint. */
public record Flag(String field, String severity, String code, String message) {
    public static Flag error(String f, String c, String m) { return new Flag(f, "ERROR", c, m); }
    public static Flag warn(String f, String c, String m) { return new Flag(f, "WARN", c, m); }
    public static Flag info(String f, String c, String m) { return new Flag(f, "INFO", c, m); }
}
