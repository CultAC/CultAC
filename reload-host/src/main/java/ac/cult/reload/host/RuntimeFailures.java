package ac.cult.reload.host;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Exceptions crossing the SPI must not retain a retired loader through VM backtraces. */
final class RuntimeFailures {
    private RuntimeFailures() {}

    static RuntimeException ended() {
        return new StateFailure("Anticheat session has ended");
    }

    static RuntimeException detached(Throwable failure) {
        while ((failure instanceof InvocationTargetException
                        || failure instanceof CompletionException
                        || failure instanceof ExecutionException)
                && failure.getCause() != null
                && failure.getCause() != failure) failure = failure.getCause();
        String message = failure.getClass().getName() + ": " + failure.getMessage();
        RuntimeException result = failure instanceof IllegalArgumentException
                ? new ArgumentFailure(message)
                : failure instanceof NullPointerException ? new NullFailure(message) : new StateFailure(message);
        result.setStackTrace(Arrays.stream(failure.getStackTrace())
                .map(frame -> new StackTraceElement(
                        frame.getClassLoaderName(),
                        frame.getModuleName(),
                        frame.getModuleVersion(),
                        frame.getClassName(),
                        frame.getMethodName(),
                        frame.getFileName(),
                        frame.getLineNumber()))
                .toArray(StackTraceElement[]::new));
        return result;
    }

    // Throwable's hidden VM backtrace can retain caller classes even after setStackTrace.
    // Only the copied, string-based frames above may cross the runtime boundary.
    private static final class ArgumentFailure extends IllegalArgumentException {
        ArgumentFailure(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final class NullFailure extends NullPointerException {
        NullFailure(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final class StateFailure extends IllegalStateException {
        StateFailure(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
