package com.ae2addon.gui;

import java.util.Locale;

/** Java 8 parser for the threshold expressions accepted by the 1.20 UI. */
final class ThresholdExpression {
    private final String input;
    private int pos;

    private ThresholdExpression(String input) { this.input = input; }

    static long parse(String text) {
        String raw = text.trim();
        if (raw.isEmpty()) throw new NumberFormatException("empty threshold");
        try { return Long.parseLong(raw); }
        catch (NumberFormatException ignored) { /* expression or unit */ }
        String s = raw.toUpperCase(Locale.ROOT).replace("LONG.MAX", "MAX")
                .replace("LONGMAX", "MAX").replace("INFINITE", "MAX")
                .replace("INF", "MAX").replace('×', '*').replace('÷', '/');
        double unit = 1;
        char suffix = s.charAt(s.length() - 1);
        switch (suffix) {
            case 'K': unit = 1e3; break;
            case 'M': unit = 1e6; break;
            case 'G': unit = 1e9; break;
            case 'T': unit = 1e12; break;
            case 'P': unit = 1e15; break;
            case 'E': unit = 1e18; break;
            default: break;
        }
        if (unit != 1) s = s.substring(0, s.length() - 1);
        ThresholdExpression parser = new ThresholdExpression(s);
        double result = parser.expression() * unit;
        parser.spaces();
        if (parser.pos != s.length() || Double.isNaN(result) || Double.isInfinite(result))
            throw new NumberFormatException("invalid threshold expression: " + text);
        if (result >= Long.MAX_VALUE) return Long.MAX_VALUE;
        if (result <= Long.MIN_VALUE) return Long.MIN_VALUE;
        return (long) result;
    }

    private void spaces() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
    }

    private boolean take(char c) {
        spaces();
        if (pos < input.length() && input.charAt(pos) == c) { pos++; return true; }
        return false;
    }

    private double expression() {
        double value = term();
        while (true) {
            if (take('+')) value += term();
            else if (take('-')) value -= term();
            else return value;
        }
    }

    private double term() {
        double value = power();
        while (true) {
            if (take('*')) value *= power();
            else if (take('/')) value /= power();
            else return value;
        }
    }

    private double power() {
        double value = atom();
        if (take('^')) value = Math.pow(value, power());
        return value;
    }

    private double atom() {
        spaces();
        if (take('+')) return atom();
        if (take('-')) return -atom();
        if (take('(')) {
            double value = expression();
            if (!take(')')) throw new NumberFormatException("missing )");
            return value;
        }
        if (input.startsWith("MAX", pos)) { pos += 3; return Long.MAX_VALUE; }
        int start = pos;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isDigit(c) || c == '.') pos++;
            else if (c == 'E' && pos + 1 < input.length()) {
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
            } else break;
        }
        if (start == pos) throw new NumberFormatException("expected number");
        return Double.parseDouble(input.substring(start, pos));
    }
}
