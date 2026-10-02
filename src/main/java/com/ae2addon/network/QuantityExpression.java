package com.ae2addon.network;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;

/** Java 8 quantity grammar used by the type 17 server handler. */
final class QuantityExpression {

    /**
     * Pre-computation bound for {@code ^}. The packet field is read with {@code readString(128)}, so the
     * input length is already capped -- but "MAX^99999" is only 11 characters while its result has about
     * 1.9 million digits. We refuse anything whose estimated size exceeds this instead of computing it.
     */
    private static final long MAX_RESULT_DIGITS = 10000L;

    private final String source;
    private int pos;

    private QuantityExpression(String source) { this.source = source; }

    static BigInteger parse(String input) {
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
        // HARDENING (2026-10-02, spec side): this runs on the SERVER over a client-supplied string, so
        // the result must be bounded. Negative is already rejected above. We saturate at Long.MAX_VALUE,
        // the same ceiling the rest of the mod uses for "effectively infinite" -- otherwise a player
        // could store a million-digit quantity, which then lands in world-save NBT as a decimal string.
        if (result.bitLength() > 63) {
            return BigInteger.valueOf(Long.MAX_VALUE);
        }
        return result;
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
