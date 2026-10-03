package com.ae2addon.network;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Java 8 quantity grammar used by the type 17 server handler.
 *
 * <p>Public so the in-game self-test can pin the parsing rules directly (J group).
 */
public final class QuantityExpression {

    /**
     * The ONLY bound on a parsed quantity, counted in decimal digits.
     *
     * <p>This is not a storage limit -- the cell keeps quantities as {@code BigInteger}. It is a <b>cost</b>
     * limit: this parser runs on the server over a client-supplied string (read with
     * {@code readString(128)}), the result is written to the world save as a decimal string, and {@code ^}
     * makes enormous results cheap to write down ("MAX^99999" is 11 characters with about 1.9M digits).
     * 1000 digits is about 2^3321: far beyond anything a player means, and at most ~1 KB of NBT per entry.
     *
     * <p>2026-10-03: this replaced a 63-bit clamp that silently rewrote {@code 2^128} into 9.22e18 (sensei
     * typed exactly that into the quantity box). The box is allowed to mean what it says; only "absurdly
     * expensive to compute or store" is refused, and then with a message rather than a substitution.
     */
    private static final long MAX_RESULT_DIGITS = 1000L;

    private final String source;
    private int pos;

    private QuantityExpression(String source) { this.source = source; }

    public static BigInteger parse(String input) {
        String s = input.trim().toUpperCase(Locale.ROOT)
                .replace("LONG.MAX", "MAX").replace("LONGMAX", "MAX")
                .replace("INFINITE", "MAX").replace("INF", "MAX")
                .replace('×', '*').replace('÷', '/');
        if (s.isEmpty()) return BigInteger.ZERO;
        if (s.matches("[0-9]+")) return new BigInteger(s);
        BigDecimal unit = BigDecimal.ONE;
        char last = s.charAt(s.length() - 1);
        if ("KMGTP E".indexOf(last) >= 0 && last != ' ') {
            int power = last == 'E' ? 6 : "KMGTP".indexOf(last) + 1;
            if (power > 0) {
                unit = BigDecimal.TEN.pow(power * 3);
                s = s.substring(0, s.length() - 1);
            }
        }
        QuantityExpression parser = new QuantityExpression(s);
        BigDecimal amount = parser.expression().multiply(unit);
        parser.space();
        if (parser.pos != s.length() || amount.signum() < 0)
            throw new NumberFormatException("Invalid quantity expression");
        BigInteger result = amount.toBigInteger();
        // BOUND BY DIGITS, NOT BY 63 BITS (2026-10-03). The old clamp here returned Long.MAX_VALUE for
        // anything wider than a long, which silently turned a typed 2^128 into 9.22e18. Storage holds
        // BigInteger, so a huge pinned amount is legitimate; what has to stay bounded is the computation and
        // the decimal string that lands in the world save. Over the bound we refuse (the caller reports it)
        // instead of substituting a number the player never asked for.
        if (digits(result) > MAX_RESULT_DIGITS) {
            throw new NumberFormatException("结果超过 " + MAX_RESULT_DIGITS + " 位数字上限");
        }
        return result;
    }

    /**
     * Upper bound on the decimal digit count, without formatting the number.
     *
     * <p>{@code bitLength * log10(2) + 1} is an exact upper bound, so anything that fits the limit is
     * accepted straight away; only values that look too big pay for {@code toString()}.
     */
    private static long digits(BigInteger value) {
        long estimate = (long) (value.bitLength() * 0.30103D) + 2L;
        return estimate > MAX_RESULT_DIGITS ? (long) value.toString().length() : estimate;
    }

    private void space() {
        while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) pos++;
    }
    private boolean take(char c) {
        space();
        if (pos < source.length() && source.charAt(pos) == c) { pos++; return true; }
        return false;
    }
    private BigDecimal expression() {
        BigDecimal value = term();
        while (true) {
            if (take('+')) value = value.add(term());
            else if (take('-')) value = value.subtract(term());
            else return value;
        }
    }
    private BigDecimal term() {
        BigDecimal value = power();
        while (true) {
            if (take('*')) value = value.multiply(power());
            else if (take('/')) {
                BigDecimal divisor = power();
                if (divisor.signum() == 0) throw new NumberFormatException("Division by zero");
                value = value.divide(divisor, 0, RoundingMode.DOWN);
            } else return value;
        }
    }
    private BigDecimal power() {
        BigDecimal value = atom();
        if (take('^')) {
            BigDecimal exponent = power();
            int n;
            try { n = exponent.intValueExact(); }
            catch (ArithmeticException invalid) { throw new NumberFormatException("Invalid exponent"); }
            if (n < 0 || n > 100000) throw new NumberFormatException("Invalid exponent");
            // HARDENING (2026-10-02, spec side): the input string is only 128 chars, so an exponent like
            // 99999 is trivially expressible while the computation is not -- "MAX^99999" would build a
            // ~1.9M-digit BigDecimal on the server tick thread. Refuse anything that would obviously be
            // enormous instead of computing it: estimate the result's digit count first.
            long estimatedDigits = (long) Math.max(1, value.precision()) * n;
            if (estimatedDigits > MAX_RESULT_DIGITS) {
                throw new NumberFormatException("Exponent too large");
            }
            value = value.pow(n);
        }
        return value;
    }
    private BigDecimal atom() {
        space();
        if (take('+')) return atom();
        if (take('-')) return atom().negate();
        if (take('(')) {
            BigDecimal value = expression();
            if (!take(')')) throw new NumberFormatException("Missing closing parenthesis");
            return value;
        }
        if (source.startsWith("MAX", pos)) {
            pos += 3; return BigDecimal.valueOf(Long.MAX_VALUE);
        }
        int start = pos;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isDigit(c) || c == '.') pos++;
            else if (c == 'E' && pos + 1 < source.length()
                    && (Character.isDigit(source.charAt(pos + 1)) || source.charAt(pos + 1) == '+'
                    || source.charAt(pos + 1) == '-')) pos++;
            else if ((c == '+' || c == '-') && pos > start && source.charAt(pos - 1) == 'E') pos++;
            else break;
        }
        if (start == pos) throw new NumberFormatException("Expected number");
        return new BigDecimal(source.substring(start, pos));
    }
}
