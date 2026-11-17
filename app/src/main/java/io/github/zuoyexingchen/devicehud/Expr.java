package io.github.zuoyexingchen.devicehud;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简表达式求值器，插件指标的 {@code expr} 用它算值。
 *
 * <p>为什么不用 JS：本项目刻意不引入任何依赖（见 DEVELOPMENT.md）。
 * Android 自带 WebView 里的 V8 又重又慢，为了一行公式去起一个 WebView
 * 完全不划算；引 Rhino 则直接违反"零依赖"这条线。所以这里自己写一个
 * 只做算术的递归下降解析器 —— 解析一次编译成树缓存起来，之后每次采样
 * 只是遍历几个节点，开销可以忽略。
 *
 * <p>支持的语法（够用了，也别再无脑加）：
 * <pre>
 *   cmp    := term (('<' | '>' | '<=' | '>=' | '==' | '!=') term)?   → 1/0
 *   expr   := term (('+' | '-') term)*
 *   term   := factor (('*' | '/' | '%') factor)*
 *   factor := ('-' | '+')? primary
 *   primary:= number | ident | ident '(' args ')' | '(' expr ')'
 * 函数：abs min max clamp sqrt pow round floor ceil avg if
 * </pre>
 *
 * <p>标识符只能由字母、数字、下划线组成，且不能以数字开头 —— 传感器 id 里的
 * 连字符由 {@link Sensor#safeName(String)} 换成下划线后再参与求值。
 */
public final class Expr {

    /** 变量取值回调 */
    public interface Resolver {
        /** 未知变量应返回 NaN，不要返回 0 —— 0 会算出貌似合理的假数据 */
        float get(String name);
    }

    private Expr() {
    }

    // ------------------------------------------------------------------ 语法树

    public abstract static class Node {
        abstract float eval(Resolver r);
    }

    private static final class Const extends Node {
        final float v;

        Const(float v) {
            this.v = v;
        }

        @Override
        float eval(Resolver r) {
            return v;
        }
    }

    private static final class Var extends Node {
        final String name;

        Var(String name) {
            this.name = name;
        }

        @Override
        float eval(Resolver r) {
            return r.get(name);
        }
    }

    private static final class Neg extends Node {
        final Node a;

        Neg(Node a) {
            this.a = a;
        }

        @Override
        float eval(Resolver r) {
            return -a.eval(r);
        }
    }

    private static final class Bin extends Node {
        final char op;
        final Node a;
        final Node b;

        Bin(char op, Node a, Node b) {
            this.op = op;
            this.a = a;
            this.b = b;
        }

        @Override
        float eval(Resolver r) {
            float x = a.eval(r);
            float y = b.eval(r);
            if (Float.isNaN(x) || Float.isNaN(y)) return Float.NaN;
            switch (op) {
                case '+':
                    return x + y;
                case '-':
                    return x - y;
                case '*':
                    return x * y;
                case '/':
                    // 除零返回 NaN 而不是 Infinity：上屏会显示 --
                    return (y == 0f) ? Float.NaN : x / y;
                case '%':
                    return (y == 0f) ? Float.NaN : x % y;
                default:
                    return Float.NaN;
            }
        }
    }

    /** 比较：返回 1 或 0，给 if() 当条件用 */
    private static final class Cmp extends Node {
        final String op;
        final Node a;
        final Node b;

        Cmp(String op, Node a, Node b) {
            this.op = op;
            this.a = a;
            this.b = b;
        }

        @Override
        float eval(Resolver r) {
            float x = a.eval(r);
            float y = b.eval(r);
            if (Float.isNaN(x) || Float.isNaN(y)) return Float.NaN;
            switch (op) {
                case "<":
                    return x < y ? 1f : 0f;
                case ">":
                    return x > y ? 1f : 0f;
                case "<=":
                    return x <= y ? 1f : 0f;
                case ">=":
                    return x >= y ? 1f : 0f;
                case "==":
                    return Math.abs(x - y) < 1e-6f ? 1f : 0f;
                case "!=":
                    return Math.abs(x - y) >= 1e-6f ? 1f : 0f;
                default:
                    return Float.NaN;
            }
        }
    }

    private static final class Call extends Node {
        final String fn;
        final List<Node> args;

        Call(String fn, List<Node> args) {
            this.fn = fn;
            this.args = args;
        }

        @Override
        float eval(Resolver r) {
            int n = args.size();
            float[] v = new float[n];
            for (int i = 0; i < n; i++) {
                v[i] = args.get(i).eval(r);
                if (Float.isNaN(v[i])) return Float.NaN;
            }
            switch (fn) {
                case "abs":
                    return n >= 1 ? Math.abs(v[0]) : Float.NaN;
                case "sqrt":
                    return n >= 1 && v[0] >= 0f ? (float) Math.sqrt(v[0]) : Float.NaN;
                case "pow":
                    return n >= 2 ? (float) Math.pow(v[0], v[1]) : Float.NaN;
                case "round":
                    return n >= 1 ? Math.round(v[0]) : Float.NaN;
                case "floor":
                    return n >= 1 ? (float) Math.floor(v[0]) : Float.NaN;
                case "ceil":
                    return n >= 1 ? (float) Math.ceil(v[0]) : Float.NaN;
                case "min": {
                    if (n == 0) return Float.NaN;
                    float m = v[0];
                    for (int i = 1; i < n; i++) m = Math.min(m, v[i]);
                    return m;
                }
                case "max": {
                    if (n == 0) return Float.NaN;
                    float m = v[0];
                    for (int i = 1; i < n; i++) m = Math.max(m, v[i]);
                    return m;
                }
                case "avg": {
                    if (n == 0) return Float.NaN;
                    float s = 0f;
                    for (float x : v) s += x;
                    return s / n;
                }
                case "clamp":
                    if (n < 3) return Float.NaN;
                    return Math.max(v[1], Math.min(v[2], v[0]));
                case "if":
                    // if(条件, 真值, 假值)。条件是非零即真，NaN 已经在上面被拦掉了
                    if (n < 3) return Float.NaN;
                    return v[0] != 0f ? v[1] : v[2];
                default:
                    return Float.NaN;
            }
        }
    }

    // ------------------------------------------------------------------ 解析

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        Node parseAll() {
            Node n = parseCmp();
            skipSpace();
            if (i < s.length()) {
                throw new IllegalArgumentException("多余的字符：" + s.substring(i));
            }
            return n;
        }

        /** 比较只能出现在最外层，不支持 a < b < c 这种链式写法 */
        Node parseCmp() {
            Node left = parseExpr();
            skipSpace();
            String op = null;
            if (i < s.length()) {
                char c = s.charAt(i);
                if (c == '<' || c == '>') {
                    op = String.valueOf(c);
                    i++;
                    if (i < s.length() && s.charAt(i) == '=') {
                        op += "=";
                        i++;
                    }
                } else if (c == '=' && i + 1 < s.length() && s.charAt(i + 1) == '=') {
                    op = "==";
                    i += 2;
                } else if (c == '!' && i + 1 < s.length() && s.charAt(i + 1) == '=') {
                    op = "!=";
                    i += 2;
                }
            }
            if (op == null) return left;
            return new Cmp(op, left, parseExpr());
        }

        Node parseExpr() {
            Node left = parseTerm();
            while (true) {
                skipSpace();
                if (peek() == '+' || peek() == '-') {
                    char op = s.charAt(i++);
                    left = new Bin(op, left, parseTerm());
                } else {
                    return left;
                }
            }
        }

        Node parseTerm() {
            Node left = parseFactor();
            while (true) {
                skipSpace();
                char c = peek();
                if (c == '*' || c == '/' || c == '%') {
                    i++;
                    left = new Bin(c, left, parseFactor());
                } else {
                    return left;
                }
            }
        }

        Node parseFactor() {
            skipSpace();
            char c = peek();
            if (c == '-') {
                i++;
                return new Neg(parseFactor());
            }
            if (c == '+') {
                i++;
                return parseFactor();
            }
            return parsePrimary();
        }

        Node parsePrimary() {
            skipSpace();
            if (i >= s.length()) throw new IllegalArgumentException("表达式不完整");
            char c = s.charAt(i);

            if (c == '(') {
                i++;
                Node n = parseCmp();
                skipSpace();
                if (peek() != ')') throw new IllegalArgumentException("缺少 )");
                i++;
                return n;
            }

            if (Character.isDigit(c) || c == '.') return parseNumber();

            if (Character.isLetter(c) || c == '_') {
                String name = parseIdent();
                skipSpace();
                if (peek() == '(') {
                    i++;
                    List<Node> args = new ArrayList<>();
                    skipSpace();
                    if (peek() == ')') {
                        i++;
                        return new Call(name.toLowerCase(java.util.Locale.US), args);
                    }
                    while (true) {
                        args.add(parseCmp());
                        skipSpace();
                        char d = peek();
                        if (d == ',') {
                            i++;
                            continue;
                        }
                        if (d == ')') {
                            i++;
                            break;
                        }
                        throw new IllegalArgumentException("函数参数里缺少 , 或 )");
                    }
                    return new Call(name.toLowerCase(java.util.Locale.US), args);
                }
                return new Var(name);
            }
            throw new IllegalArgumentException("无法识别的字符：" + c);
        }

        Node parseNumber() {
            int st = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            // 科学计数法：1e3 / 2.5E-2
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int save = i;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (i < s.length() && Character.isDigit(s.charAt(i))) {
                    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                } else {
                    i = save;
                }
            }
            try {
                return new Const(Float.parseFloat(s.substring(st, i)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("数字格式不对：" + s.substring(st, i));
            }
        }

        String parseIdent() {
            int st = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '_') i++;
                else break;
            }
            return s.substring(st, i);
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        void skipSpace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }

    /** 解析表达式；语法错误抛 IllegalArgumentException（消息可直接给用户看） */
    public static Node parse(String src) {
        if (src == null || src.trim().isEmpty()) {
            throw new IllegalArgumentException("表达式为空");
        }
        return new Parser(src).parseAll();
    }

    public static float eval(Node node, Resolver r) {
        if (node == null) return Float.NaN;
        try {
            return node.eval(r);
        } catch (Exception e) {
            return Float.NaN;
        }
    }
}
