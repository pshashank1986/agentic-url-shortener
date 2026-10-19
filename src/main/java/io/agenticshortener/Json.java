package io.agenticshortener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small dependency-free JSON codec for the demo's HTTP and model contracts. */
final class Json {
    private Json() {}

    static Object parse(String input) {
        Parser parser = new Parser(input);
        Object value = parser.value();
        parser.space();
        if (!parser.end()) throw new IllegalArgumentException("Unexpected JSON content at " + parser.at);
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String input) {
        Object value = parse(input);
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) value;
    }

    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof String text) { quote(text, out); return; }
        if (value instanceof Number || value instanceof Boolean) { out.append(value); return; }
        if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
            return;
        }
        if (value instanceof Iterable<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
            return;
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String source;
        private int at;

        private Parser(String source) { this.source = source; }
        private boolean end() { return at >= source.length(); }
        private void space() { while (!end() && Character.isWhitespace(source.charAt(at))) at++; }

        private Object value() {
            space();
            if (end()) throw error("Expected a value");
            return switch (source.charAt(at)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            at++;
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (take('}')) return result;
            while (true) {
                space();
                if (end() || source.charAt(at) != '"') throw error("Expected an object key");
                String key = string();
                space();
                if (!take(':')) throw error("Expected ':'");
                result.put(key, value());
                space();
                if (take('}')) return result;
                if (!take(',')) throw error("Expected ',' or '}'");
            }
        }

        private List<Object> array() {
            at++;
            List<Object> result = new ArrayList<>();
            space();
            if (take(']')) return result;
            while (true) {
                result.add(value());
                space();
                if (take(']')) return result;
                if (!take(',')) throw error("Expected ',' or ']'");
            }
        }

        private String string() {
            at++;
            StringBuilder out = new StringBuilder();
            while (!end()) {
                char c = source.charAt(at++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    if (end()) throw error("Unfinished escape");
                    char escaped = source.charAt(at++);
                    switch (escaped) {
                        case '"', '\\', '/' -> out.append(escaped);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (at + 4 > source.length()) throw error("Short unicode escape");
                            try { out.append((char) Integer.parseInt(source.substring(at, at + 4), 16)); }
                            catch (NumberFormatException ex) { throw error("Invalid unicode escape"); }
                            at += 4;
                        }
                        default -> throw error("Invalid escape");
                    }
                } else {
                    if (c < 0x20) throw error("Control character in string");
                    out.append(c);
                }
            }
            throw error("Unterminated string");
        }

        private Object number() {
            int start = at;
            if (take('-')) { /* sign */ }
            while (!end() && Character.isDigit(source.charAt(at))) at++;
            boolean decimal = false;
            if (take('.')) {
                decimal = true;
                while (!end() && Character.isDigit(source.charAt(at))) at++;
            }
            if (!end() && (source.charAt(at) == 'e' || source.charAt(at) == 'E')) {
                decimal = true;
                at++;
                if (!end() && (source.charAt(at) == '+' || source.charAt(at) == '-')) at++;
                while (!end() && Character.isDigit(source.charAt(at))) at++;
            }
            if (start == at) throw error("Expected a number");
            String token = source.substring(start, at);
            try {
                if (decimal) return Double.parseDouble(token);
                return Long.parseLong(token);
            }
            catch (NumberFormatException ex) { throw error("Invalid number"); }
        }

        private Object literal(String token, Object value) {
            if (!source.startsWith(token, at)) throw error("Invalid literal");
            at += token.length();
            return value;
        }

        private boolean take(char expected) {
            if (!end() && source.charAt(at) == expected) { at++; return true; }
            return false;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at " + at);
        }
    }
}
