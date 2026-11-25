package ac.cult.cultac.utils.visibility;

import java.lang.reflect.Field;
import java.math.BigInteger;

/** Test-side reflection over the private state of {@link ExactFraction} and {@link ParentExactFraction}. */
final class FractionFields {
    private static final Field[] CURRENT = fields(ExactFraction.class);
    private static final Field[] PARENT = fields(ParentExactFraction.class);
    private static final Field[] SMALL = smallFields();

    private FractionFields() {}

    /** Reduced numerator, positive denominator and the raw bits of both cached bounds. */
    record State(BigInteger numerator, BigInteger denominator, long lowerBits, long upperBits) {}

    static State of(ExactFraction value) {
        State wide = read(CURRENT, value);
        if (wide.numerator() != null) return wide;
        try {
            return new State(BigInteger.valueOf(SMALL[0].getLong(value)), BigInteger.valueOf(SMALL[1].getLong(value)),
                    wide.lowerBits(), wide.upperBits());
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    /** Whether {@code value} is held in its long terms. */
    static boolean small(ExactFraction value) {
        return read(CURRENT, value).numerator() == null;
    }

    static State of(ParentExactFraction value) {
        return read(PARENT, value);
    }

    static BigInteger denominator(ExactFraction value) {
        return of(value).denominator();
    }

    private static State read(Field[] fields, Object value) {
        try {
            return new State((BigInteger) fields[0].get(value), (BigInteger) fields[1].get(value),
                    Double.doubleToRawLongBits(fields[2].getDouble(value)),
                    Double.doubleToRawLongBits(fields[3].getDouble(value)));
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static Field[] smallFields() {
        try {
            Field[] fields = {ExactFraction.class.getDeclaredField("num"), ExactFraction.class.getDeclaredField("den")};
            for (Field field : fields) field.setAccessible(true);
            return fields;
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    private static Field[] fields(Class<?> type) {
        try {
            Field[] fields = {
                type.getDeclaredField("numerator"),
                type.getDeclaredField("denominator"),
                type.getDeclaredField("lowerBound"),
                type.getDeclaredField("upperBound")
            };
            for (Field field : fields) field.setAccessible(true);
            return fields;
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }
}
