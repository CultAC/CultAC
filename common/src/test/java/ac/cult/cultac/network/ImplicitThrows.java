package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Compares what two operations threw when the JIT may have swapped an implicit exception for its preallocated one,
 * which has no stack trace and no message. Such an exception matches only an implicit exception of the same class on
 * the other side: another preallocated one, or one carrying the JVM's own message for that failure. Every other
 * exception compares class and message exactly.
 */
public final class ImplicitThrows {
    /** The JVM's messages for the implicit exceptions compiled code may throw preallocated. */
    private static final Map<Class<?>, Pattern> JVM_MESSAGES = Map.of(
            NullPointerException.class,
            Pattern.compile("Cannot (invoke|read|load|store|assign|throw|enter) .+", Pattern.DOTALL),
            ArrayIndexOutOfBoundsException.class,
            Pattern.compile("Index -?\\d+ out of bounds for length \\d+"),
            ClassCastException.class,
            Pattern.compile("class \\S+ cannot be cast to class \\S+( \\(.+\\))?", Pattern.DOTALL),
            ArithmeticException.class,
            Pattern.compile("/ by zero"),
            ArrayStoreException.class,
            Pattern.compile("[\\w.$]+|arraycopy: .+", Pattern.DOTALL));

    private ImplicitThrows() {}

    /** What an operation returned, or the exception it threw. */
    public record Outcome(String returned, RuntimeException thrown) {
        public static Outcome of(Supplier<?> operation) {
            try {
                return new Outcome(String.valueOf(operation.get()), null);
            } catch (RuntimeException failure) {
                return new Outcome(null, failure);
            }
        }

        @Override
        public String toString() {
            return thrown == null ? "returns " + returned : "throws " + describe(thrown);
        }
    }

    public static void assertSameOutcome(Outcome expected, Outcome actual, String where) {
        if (expected.thrown() != null && actual.thrown() != null) assertSameThrow(expected.thrown(), actual.thrown(), where);
        else assertEquals(expected.toString(), actual.toString(), where);
    }

    /** Asserts both threw alike, or neither threw. */
    public static void assertSameThrow(Throwable expected, Throwable actual, String where) {
        if (expected == null || actual == null || !(preallocated(expected) || preallocated(actual))) {
            assertEquals(describe(expected), describe(actual), where);
            return;
        }
        assertEquals(expected.getClass(), actual.getClass(), where);
        assertTrue(
                implicit(expected) && implicit(actual),
                () -> where + ": a preallocated " + expected.getClass().getName() + " against an explicit one: "
                        + describe(expected) + " / " + describe(actual));
    }

    /** Whether {@code failure} looks like an exception the JIT preallocated: an implicit class, no trace, no message. */
    public static boolean preallocated(Throwable failure) {
        return JVM_MESSAGES.containsKey(failure.getClass())
                && failure.getStackTrace().length == 0
                && failure.getMessage() == null;
    }

    private static boolean implicit(Throwable failure) {
        if (preallocated(failure)) return true;
        Pattern message = JVM_MESSAGES.get(failure.getClass());
        return message != null
                && failure.getStackTrace().length > 0
                && failure.getMessage() != null
                && message.matcher(failure.getMessage()).matches();
    }

    public static String describe(Throwable failure) {
        return failure == null ? "nothing" : failure.getClass().getName() + ": " + failure.getMessage();
    }
}
