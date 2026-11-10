package com.account0.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** All amounts are stored as integer paise. Conversions live here only. */
public final class Money {
    private Money() {}
    public static long toPaise(BigDecimal rupees) {
        return rupees == null ? 0 : rupees.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }
    public static long toPaise(String rupees) {
        if (rupees == null || rupees.isBlank()) return 0;
        return toPaise(new BigDecimal(rupees.replace(",", "").trim()));
    }
    public static BigDecimal toRupees(long paise) { return BigDecimal.valueOf(paise, 2); }
    public static String fmt(long paise) { return toRupees(paise).toPlainString(); }
}
