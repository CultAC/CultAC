package ac.cult.cultac.utils.debug;

import ac.cult.cultac.utils.anticheat.StringReturner;

/**
 * Contract for engine components that publish optional diagnostic output.
 *
 * <p>A component exposes a short, stable label identifying where a diagnostic
 * originated, plus an entry point that accepts a lazily evaluated message
 * supplier. Callers must only evaluate the supplier when a debug listener is
 * attached, so that leaving diagnostics disabled costs nothing.</p>
 */
public interface Debuggable {

    /**
     * Pushes a diagnostic message into the debug pipeline.
     *
     * @param message supplier producing the message text on demand
     */
    void debug(StringReturner message);

    /**
     * The short, stable label identifying this component in debug output.
     *
     * @return diagnostic source label
     */
    String debugName();
}
