package ac.cult.cultac.utils.debug;

import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collection;

/**
 * Text kept as the values it prints and printed only when read. A {@link Builder} records what it is given: strings and
 * immutable values by reference, numbers as their bits, and a {@link Recordable}'s state as it is when appended. Any
 * other value is printed as it is appended. A text holds nothing that changes, so it reads the same whenever, and on
 * whichever thread, it is printed.
 */
public final class DebugText {
    /** An immutable value holding no live state. A text keeps it and prints it when read. */
    public interface Part {
        void appendTo(StringBuilder sb);
    }

    /** A mutable value that appends its current state, as its toString prints it. */
    public interface Recordable {
        void appendTo(Builder sb);
    }

    /** Marks the number a {@link #numbers} slot holds. */
    private enum Kind {
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        CHAR
    }

    private static final String[] ASCII = new String[128];

    static {
        for (char c = 0; c < ASCII.length; c++) ASCII[c] = String.valueOf(c);
    }

    /** Records whose components are all primitives, strings, boxed primitives or enums. */
    private static final ClassValue<Boolean> IMMUTABLE_RECORDS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (!type.isRecord()) return false;
            for (RecordComponent component : type.getRecordComponents()) {
                Class<?> value = component.getType();
                if (!value.isPrimitive() && !value.isEnum() && !isBoxedOrString(value)) return false;
            }
            return true;
        }
    };

    private static final Object[] NO_PARTS = {};
    private static final long[] NO_NUMBERS = {};

    private final Object[] parts;
    private final int size;
    private final long[] numbers;

    private DebugText(Object[] parts, int size, long[] numbers) {
        this.parts = parts;
        this.size = size;
        this.numbers = numbers;
    }

    /** {@code value}'s current state as its toString prints it. */
    public static String print(Recordable value) {
        Builder sb = new Builder(32, 16);
        value.appendTo(sb);
        return sb.toString();
    }

    public void appendTo(StringBuilder sb) {
        print(sb, parts, size, numbers);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(size * 16);
        appendTo(sb);
        return sb.toString();
    }

    private static void print(StringBuilder sb, Object[] parts, int size, long[] numbers) {
        int number = 0;
        for (int i = 0; i < size; i++) {
            Object part = parts[i];
            if (part instanceof String text) {
                sb.append(text);
            } else if (part instanceof Kind kind) {
                long bits = numbers[number++];
                switch (kind) {
                    case INT -> sb.append((int) bits);
                    case LONG -> sb.append(bits);
                    case FLOAT -> sb.append(Float.intBitsToFloat((int) bits));
                    case DOUBLE -> sb.append(Double.longBitsToDouble(bits));
                    case CHAR -> sb.append((char) bits);
                }
            } else if (part instanceof Part value) {
                value.appendTo(sb);
            } else {
                sb.append(part);
            }
        }
    }

    private static boolean isBoxedOrString(Class<?> type) {
        return type == String.class
                || type == Integer.class
                || type == Long.class
                || type == Double.class
                || type == Float.class
                || type == Boolean.class
                || type == Character.class
                || type == Short.class
                || type == Byte.class;
    }

    /** Whether {@code value} prints the same whenever it is printed and holds no live state. */
    private static boolean isImmutable(Object value) {
        Class<?> type = value.getClass();
        return value instanceof Enum<?> || type == Vec3.class || isBoxedOrString(type) || IMMUTABLE_RECORDS.get(type);
    }

    /** Appends like a {@link StringBuilder}, recording values instead of printing them; on one thread. */
    public static final class Builder {
        private Object[] parts;
        private long[] numbers;
        private int size;
        private int count;

        public Builder() {
            this(64, 32);
        }

        /** A builder sized for about {@code parts} appends, {@code numbers} of them numbers. */
        public Builder(int parts, int numbers) {
            this.parts = new Object[Math.max(parts, 8)];
            this.numbers = new long[Math.max(numbers, 4)];
        }

        public Builder append(String value) {
            return part(value == null ? "null" : value);
        }

        public Builder append(char value) {
            return value < ASCII.length ? part(ASCII[value]) : number(Kind.CHAR, value);
        }

        public Builder append(boolean value) {
            return part(value ? "true" : "false");
        }

        public Builder append(int value) {
            return number(Kind.INT, value);
        }

        public Builder append(long value) {
            return number(Kind.LONG, value);
        }

        public Builder append(float value) {
            return number(Kind.FLOAT, Float.floatToRawIntBits(value));
        }

        public Builder append(double value) {
            return number(Kind.DOUBLE, Double.doubleToRawLongBits(value));
        }

        /**
         * A {@link Recordable} appends its current state, a {@link Part} or an immutable value is kept, and anything
         * else is printed now.
         */
        public Builder append(Object value) {
            if (value == null) return part("null");
            if (value instanceof String text) return part(text);
            if (value instanceof Recordable recordable) {
                recordable.appendTo(this);
                return this;
            }
            if (value instanceof Part || isImmutable(value)) return part(value);
            return part(String.valueOf(value));
        }

        /** {@code values} as {@link java.util.AbstractCollection#toString} prints them, each as {@link #append}. */
        public Builder appendAll(Collection<?> values) {
            if (values == null) return part("null");
            part("[");
            boolean first = true;
            for (Object value : values) {
                if (!first) part(", ");
                first = false;
                append(value);
            }
            return part("]");
        }

        /** The number of appends recorded. */
        public int parts() {
            return size;
        }

        /** The number of number appends recorded. */
        public int numbers() {
            return count;
        }

        /** What is recorded, as a text. The builder hands its arrays over and starts again empty. */
        public DebugText toText() {
            // Trim only arrays a quarter or more unused; a text keeps them until its slot is reused.
            Object[] keptParts = size * 4 < parts.length * 3 ? Arrays.copyOf(parts, size) : parts;
            long[] keptNumbers = count * 4 < numbers.length * 3 ? Arrays.copyOf(numbers, count) : numbers;
            DebugText text = new DebugText(keptParts, size, keptNumbers);
            parts = NO_PARTS;
            numbers = NO_NUMBERS;
            size = 0;
            count = 0;
            return text;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(size * 8);
            print(sb, parts, size, numbers);
            return sb.toString();
        }

        private Builder part(Object part) {
            if (size == parts.length) parts = Arrays.copyOf(parts, Math.max(size * 2, 16));
            parts[size++] = part;
            return this;
        }

        private Builder number(Kind kind, long bits) {
            if (count == numbers.length) numbers = Arrays.copyOf(numbers, Math.max(count * 2, 8));
            numbers[count++] = bits;
            return part(kind);
        }
    }
}
