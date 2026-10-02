package com.ae2addon.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Readable formatting for byte/quantity values, ported verbatim from the 1.20 line so both versions print
 * the same thing.
 *
 * <p>Units go B/K/M/G/T/P/E/Z/Y/R/Q in steps of 1000; past Q it falls back to scientific notation
 * (e.g. {@code 1.23e45}), because the cell's reported "bytes" can be enormous: every infinite type is
 * charged {@code INFINITE_BYTES} (300,000,000) by the summary.
 */
public final class SizeFormat {
    private static final String[] UNITS = {"B", "K", "M", "G", "T", "P", "E", "Z", "Y", "R", "Q"};
    private static final BigInteger BASE = BigInteger.valueOf(1000);

    private SizeFormat() {}

    /** Byte/quantity formatting: B/K/M/G/T/P/E/Z/Y/R/Q (1000-based), scientific past Q. */
    public static String bytes(BigInteger value) {
        if (value.signum() < 0) {
            return "0B";
        }
        BigInteger scaled = value;
        int unit = 0;
        while (unit < UNITS.length - 1 && scaled.compareTo(BASE) >= 0) {
            scaled = scaled.divide(BASE);
            unit++;
        }
        if (unit == UNITS.length - 1 && scaled.compareTo(BASE) >= 0) {
            // A number this large has at least 34 digits, so the substring below is always in range.
            String digits = value.toString();
            return digits.charAt(0) + "." + digits.substring(1, 3) + "e" + (digits.length() - 1);
        }
        if (unit == 0) {
            return scaled + UNITS[unit];
        }
        BigDecimal amount = new BigDecimal(value);
        BigDecimal divisor = BigDecimal.valueOf(1000).pow(unit);
        return amount.divide(divisor, 1, RoundingMode.DOWN).toPlainString() + UNITS[unit];
    }

    public static String bytes(long value) {
        return bytes(BigInteger.valueOf(value));
    }
}
