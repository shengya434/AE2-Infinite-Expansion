package com.ae2addon.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public final class SizeFormat {
    private static final String[] UNITS = {"B", "K", "M", "G", "T", "P", "E", "Z", "Y", "R", "Q"};
    private static final BigInteger BASE = BigInteger.valueOf(1000);

    private SizeFormat() {}

    /** 字节/数量的可读格式化：B/K/M/G/T/P/E/Z/Y/R/Q（1000 进制），超出 Q 后转科学计数。 */
    public static String bytes(BigInteger value) {
        if (value.signum() < 0) return "0B";
        BigInteger scaled = value;
        int unit = 0;
        while (unit < UNITS.length - 1 && scaled.compareTo(BASE) >= 0) {
            scaled = scaled.divide(BASE);
            unit++;
        }
        if (unit == UNITS.length - 1 && scaled.compareTo(BASE) >= 0) {
            String digits = value.toString();
            return digits.charAt(0) + "." + digits.substring(1, 3) + "e" + (digits.length() - 1);
        }
        if (unit == 0) return scaled + UNITS[unit];
        BigDecimal amount = new BigDecimal(value);
        BigDecimal divisor = BigDecimal.valueOf(1000).pow(unit);
        return amount.divide(divisor, 1, RoundingMode.DOWN).toPlainString() + UNITS[unit];
    }

    public static String bytes(long value) {
        return bytes(BigInteger.valueOf(value));
    }
}
