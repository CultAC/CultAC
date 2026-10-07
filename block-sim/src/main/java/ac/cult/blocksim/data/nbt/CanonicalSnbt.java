package ac.cult.blocksim.data.nbt;

import java.util.ArrayList;
import java.util.HashMap;
import static ac.cult.blocksim.data.nbt.NbtValue.Kind.*;

/** Decodes the canonical output of nbt/StringTagVisitor and StringTag.quoteAndEscape.
 * Components arrive already decoded and encoded through their vanilla codecs; this
 * reader is not the command-input SNBT grammar. In particular numeric kinds are kept. */
public final class CanonicalSnbt {
    private final String input;
    private int cursor;
    private CanonicalSnbt(String input) { this.input = input; }
    public static NbtValue parse(String input) {
        var parser = new CanonicalSnbt(input);
        NbtValue value = parser.value();
        parser.whitespace();
        if (parser.cursor != input.length()) throw parser.invalid();
        return value;
    }
    private NbtValue value() {
        whitespace();
        char next = peek();
        if (next == '{') return compound();
        if (next == '[') return list();
        if (next == '\'' || next == '"') return new NbtValue.Text(quoted());
        String token = token(false);
        if (token.isEmpty()) throw invalid();
        char suffix = Character.toLowerCase(token.charAt(token.length() - 1));
        String number = token.substring(0, token.length() - 1);
        return switch (suffix) {
            case 'b' -> new NbtValue.Numeric(BYTE, Byte.valueOf(number));
            case 's' -> new NbtValue.Numeric(SHORT, Short.valueOf(number));
            case 'l' -> new NbtValue.Numeric(LONG, Long.valueOf(number));
            case 'f' -> new NbtValue.Numeric(FLOAT, Float.valueOf(number));
            case 'd' -> new NbtValue.Numeric(DOUBLE, Double.valueOf(number));
            default -> new NbtValue.Numeric(INT, Integer.valueOf(token));
        };
    }
    private NbtValue.Compound compound() {
        expect('{'); var values = new HashMap<String, NbtValue>();
        if (!take('}')) {
            do {
                whitespace();
                String key = peek() == '\'' || peek() == '"' ? quoted() : token(true);
                expect(':'); values.put(key, value());
            } while (take(','));
            expect('}');
        }
        return new NbtValue.Compound(values);
    }
    private NbtValue list() {
        expect('['); whitespace();
        if (cursor + 1 < input.length() && input.charAt(cursor + 1) == ';') {
            char type = input.charAt(cursor); cursor += 2;
            var kind = switch (type) { case 'B' -> BYTE_ARRAY; case 'I' -> INT_ARRAY; case 'L' -> LONG_ARRAY; default -> throw invalid(); };
            var values = new ArrayList<Long>();
            if (!take(']')) {
                do { values.add(((NbtValue.Numeric)value()).value().longValue()); } while (take(','));
                expect(']');
            }
            return new NbtValue.PrimitiveArray(kind, values);
        }
        var values = new ArrayList<NbtValue>();
        if (!take(']')) {
            do { values.add(value()); } while (take(','));
            expect(']');
        }
        return new NbtValue.Sequence(values);
    }
    private String quoted() {
        char quote = input.charAt(cursor++); var result = new StringBuilder();
        while (cursor < input.length()) {
            char c = input.charAt(cursor++);
            if (c == quote) return result.toString();
            if (c != '\\') { result.append(c); continue; }
            if (cursor == input.length()) throw invalid();
            char escaped = input.charAt(cursor++);
            result.append(switch (escaped) {
                case '\\', '\'', '"' -> escaped;
                case 'b' -> '\b'; case 't' -> '\t'; case 'n' -> '\n'; case 'f' -> '\f'; case 'r' -> '\r';
                case 'x' -> hex(2); case 'u' -> hex(4);
                default -> throw invalid();
            });
        }
        throw invalid();
    }
    private char hex(int length) {
        if (cursor + length > input.length()) throw invalid();
        char value = (char)Integer.parseInt(input.substring(cursor, cursor + length), 16); cursor += length; return value;
    }
    private String token(boolean key) {
        int start = cursor;
        while (cursor < input.length()) {
            char c = input.charAt(cursor);
            if (c == ',' || c == ']' || c == '}' || key && c == ':' || Character.isWhitespace(c)) break;
            cursor++;
        }
        return input.substring(start, cursor);
    }
    private boolean take(char expected) {
        whitespace();
        if (cursor < input.length() && input.charAt(cursor) == expected) { cursor++; return true; }
        return false;
    }
    private void expect(char expected) { if (!take(expected)) throw invalid(); }
    private char peek() { if (cursor == input.length()) throw invalid(); return input.charAt(cursor); }
    private void whitespace() { while (cursor < input.length() && Character.isWhitespace(input.charAt(cursor))) cursor++; }
    private IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid canonical SNBT at " + cursor); }
}
