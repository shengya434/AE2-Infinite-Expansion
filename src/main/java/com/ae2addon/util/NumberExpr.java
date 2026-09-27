package com.ae2addon.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public final class NumberExpr {
    private NumberExpr() {}

    /** 元件内数量解析；锁定时超出 long 上限的纯数字回退到 Long.MAX_VALUE。 */
    public record Quantity(BigInteger value, boolean clamped) {}

    public static Quantity parseQuantity(String text, boolean lock) throws NumberFormatException {
        String t = text.trim();
        if (t.isEmpty()) return new Quantity(BigInteger.ZERO, false);
        if (t.matches("[+-]?[0-9]+")) {
            BigInteger value = new BigInteger(t);
            BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
            if (lock && value.compareTo(max) > 0) return new Quantity(max, true);
            return new Quantity(value, false);
        }
        BigDecimal v = evalExprBig(t);
        BigInteger value = v.toBigInteger();
        if (value.signum() < 0) throw new NumberFormatException("数量不能为负");
        if (lock) {
            BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
            if (value.compareTo(max) > 0) return new Quantity(max, true);
        }
        return new Quantity(value, false);
    }

    /**
     * 数字解析（2026-08-27 21:56 sensei 要求支持表达式 + 22:00 单位后缀）：
     * 先试精确整数（Long.parseLong，Long.MAX 不失真）；失败则走表达式求值。
     * 支持 + - * / ^（幂）括号、科学计数 1e12、常量 MAX/INF（= Long.MAX_VALUE）、
     * 单位后缀 K/M/G/T/P/E（1K=1e3 … 1E=1e18，大小写通吃）。
     */
    public static long parse(String text) throws NumberFormatException {
        String t = text.trim();
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException ignored) {
            // 非纯数字 → 表达式求值（含单位后缀）
        }
        return evalExpr(t);
    }

    /** 表达式求值（递归下降，double 运算后截断 long）。 */
    private static long evalExpr(String text) throws NumberFormatException {
        String s = text.trim().toUpperCase()
                .replace("LONG.MAX", "MAX")
                .replace("LONGMAX", "MAX")
                .replace("INFINITE", "MAX")
                .replace("INF", "MAX")
                .replace("MAX", "\u0000") // 保护常量（防下方 X→* 误伤 MAX 的 X）
                .replace("×", "*")
                .replace("X", "*")
                .replace("÷", "/")
                .replace("\u0000", "MAX");
        // 单位后缀：末尾 K/M/G/T/P/E（前面是数字/点/括号闭合）→ 整体乘单位
        // 注意与科学计数区分：1e12 末尾是数字不触发；1E 末尾是单位 E 触发
        // （1e18 科学计数与 1E 单位值相同，无冲突）
        double unit = 1.0;
        if (!s.isEmpty()) {
            char last = s.charAt(s.length() - 1);
            switch (last) {
                case 'K' -> { unit = 1e3; s = s.substring(0, s.length() - 1); }
                case 'M' -> { unit = 1e6; s = s.substring(0, s.length() - 1); }
                case 'G' -> { unit = 1e9; s = s.substring(0, s.length() - 1); }
                case 'T' -> { unit = 1e12; s = s.substring(0, s.length() - 1); }
                case 'P' -> { unit = 1e15; s = s.substring(0, s.length() - 1); }
                case 'E' -> { unit = 1e18; s = s.substring(0, s.length() - 1); }
                default -> { }
            }
        }
        ExprParser p = new ExprParser(s);
        double v = p.parseExpression() * unit;
        if (!p.isEnd()) {
            throw new NumberFormatException("多余字符: " + s.substring(p.pos));
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new NumberFormatException("结果无效");
        }
        return (long) v;
    }

    /** 迷你表达式解析器（+ - * / ^ 括号，右结合幂）。 */
    private static final class ExprParser {
        private final String s;
        private int pos;

        ExprParser(String s) {
            this.s = s;
        }

        boolean isEnd() {
            return pos >= s.length();
        }

        private char peek() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private void skipWs() {
            peek();
        }

        double parseExpression() {
            double v = parseTerm();
            while (true) {
                skipWs();
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    char op = s.charAt(pos++);
                    double rhs = parseTerm();
                    v = op == '+' ? v + rhs : v - rhs;
                } else {
                    return v;
                }
            }
        }

        private double parseTerm() {
            double v = parsePower();
            while (true) {
                skipWs();
                if (pos < s.length() && (s.charAt(pos) == '*' || s.charAt(pos) == '/')) {
                    char op = s.charAt(pos++);
                    double rhs = parsePower();
                    v = op == '*' ? v * rhs : v / rhs;
                } else {
                    return v;
                }
            }
        }

        /** 幂：右结合（2^3^2 = 2^(3^2)）。 */
        private double parsePower() {
            double base = parseAtom();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '^') {
                pos++;
                double exp = parsePower(); // 右结合
                return Math.pow(base, exp);
            }
            return base;
        }

        private double parseAtom() {
            skipWs();
            if (pos >= s.length()) {
                throw new NumberFormatException("表达式不完整");
            }
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                double v = parseExpression();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ')') {
                    throw new NumberFormatException("缺少右括号");
                }
                pos++;
                return v;
            }
            if (c == '-') { // 一元负号
                pos++;
                return -parseAtom();
            }
            if (c == '+') {
                pos++;
                return parseAtom();
            }
            if (s.startsWith("MAX", pos)) {
                pos += 3;
                return Long.MAX_VALUE;
            }
            // 数字（含科学计数 1e12 / 1.5e9；+/- 仅在 e/E 后合法）
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos))
                    || s.charAt(pos) == '.' || s.charAt(pos) == 'e' || s.charAt(pos) == 'E'
                    || ((s.charAt(pos) == '+' || s.charAt(pos) == '-') && pos > start
                        && (s.charAt(pos - 1) == 'e' || s.charAt(pos - 1) == 'E')))) {
                pos++;
            }
            if (start == pos) {
                throw new NumberFormatException("无法识别的字符: " + c);
            }
            try {
                return Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new NumberFormatException("无效数字: " + s.substring(start, pos));
            }
        }
    }

    /** 元件内数量的表达式求值，保留超过 long 上限的整数精度。 */
    private static BigDecimal evalExprBig(String text) throws NumberFormatException {
        String s = text.trim().toUpperCase()
                .replace("LONG.MAX", "MAX")
                .replace("LONGMAX", "MAX")
                .replace("INFINITE", "MAX")
                .replace("INF", "MAX")
                .replace("MAX", "\u0000")
                .replace("×", "*")
                .replace("X", "*")
                .replace("÷", "/")
                .replace("\u0000", "MAX");
        BigDecimal unit = BigDecimal.ONE;
        if (!s.isEmpty()) {
            char last = s.charAt(s.length() - 1);
            switch (last) {
                case 'K' -> { unit = BigDecimal.TEN.pow(3); s = s.substring(0, s.length() - 1); }
                case 'M' -> { unit = BigDecimal.TEN.pow(6); s = s.substring(0, s.length() - 1); }
                case 'G' -> { unit = BigDecimal.TEN.pow(9); s = s.substring(0, s.length() - 1); }
                case 'T' -> { unit = BigDecimal.TEN.pow(12); s = s.substring(0, s.length() - 1); }
                case 'P' -> { unit = BigDecimal.TEN.pow(15); s = s.substring(0, s.length() - 1); }
                case 'E' -> { unit = BigDecimal.TEN.pow(18); s = s.substring(0, s.length() - 1); }
                default -> { }
            }
        }
        BigExprParser p = new BigExprParser(s);
        BigDecimal v = p.parseExpression().multiply(unit);
        if (!p.isEnd()) {
            throw new NumberFormatException("多余字符: " + s.substring(p.pos));
        }
        if (v.signum() < 0) {
            throw new NumberFormatException("数量不能为负");
        }
        return v;
    }

    /** 与 ExprParser 相同文法的 BigDecimal 解析器。 */
    private static final class BigExprParser {
        private final String s;
        private int pos;

        BigExprParser(String s) {
            this.s = s;
        }

        boolean isEnd() {
            return pos >= s.length();
        }

        private char peek() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private void skipWs() {
            peek();
        }

        BigDecimal parseExpression() {
            BigDecimal v = parseTerm();
            while (true) {
                skipWs();
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    char op = s.charAt(pos++);
                    BigDecimal rhs = parseTerm();
                    v = op == '+' ? v.add(rhs) : v.subtract(rhs);
                } else {
                    return v;
                }
            }
        }

        private BigDecimal parseTerm() {
            BigDecimal v = parsePower();
            while (true) {
                skipWs();
                if (pos < s.length() && (s.charAt(pos) == '*' || s.charAt(pos) == '/')) {
                    char op = s.charAt(pos++);
                    BigDecimal rhs = parsePower();
                    if (op == '*') {
                        v = v.multiply(rhs);
                    } else {
                        if (rhs.signum() == 0) throw new NumberFormatException("结果无效");
                        v = v.divide(rhs, 0, RoundingMode.DOWN);
                    }
                } else {
                    return v;
                }
            }
        }

        private BigDecimal parsePower() {
            BigDecimal base = parseAtom();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '^') {
                pos++;
                BigDecimal exp = parsePower();
                try {
                    int n = exp.intValueExact();
                    if (n >= 0 && n <= 100000) return base.pow(n);
                } catch (ArithmeticException ignored) {
                    // 非整数或超过 int 范围的指数沿用 double 幂运算。
                }
                double result = Math.pow(base.doubleValue(), exp.doubleValue());
                if (!Double.isFinite(result)) throw new NumberFormatException("结果无效");
                return BigDecimal.valueOf(result);
            }
            return base;
        }

        private BigDecimal parseAtom() {
            skipWs();
            if (pos >= s.length()) {
                throw new NumberFormatException("表达式不完整");
            }
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                BigDecimal v = parseExpression();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ')') {
                    throw new NumberFormatException("缺少右括号");
                }
                pos++;
                return v;
            }
            if (c == '-') {
                pos++;
                return parseAtom().negate();
            }
            if (c == '+') {
                pos++;
                return parseAtom();
            }
            if (s.startsWith("MAX", pos)) {
                pos += 3;
                return BigDecimal.valueOf(Long.MAX_VALUE);
            }
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos))
                    || s.charAt(pos) == '.' || s.charAt(pos) == 'e' || s.charAt(pos) == 'E'
                    || ((s.charAt(pos) == '+' || s.charAt(pos) == '-') && pos > start
                        && (s.charAt(pos - 1) == 'e' || s.charAt(pos - 1) == 'E')))) {
                pos++;
            }
            if (start == pos) {
                throw new NumberFormatException("无法识别的字符: " + c);
            }
            try {
                return new BigDecimal(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new NumberFormatException("无效数字: " + s.substring(start, pos));
            }
        }
    }

}
