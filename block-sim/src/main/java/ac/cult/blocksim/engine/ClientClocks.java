package ac.cult.blocksim.engine;

import java.util.HashMap;
import java.util.Map;

/** ClientClockManager's received clocks. Registry keys and game-time ticks come from the adapter. */
public final class ClientClocks {
    public record State(long totalTicks, float partialTick, float rate) { }
    private final Map<String, State> clocks = new HashMap<>();
    private long lastGameTime;

    public State state(String clock) { return clocks.computeIfAbsent(clock, ignored -> new State(0, 0, 1)); }
    public Map<String, State> snapshot() { return Map.copyOf(clocks); }
    public void reset() { clocks.clear(); lastGameTime = 0; }
    public void tick(long gameTime) {
        long delta = gameTime - lastGameTime;
        lastGameTime = gameTime;
        clocks.replaceAll((key, state) -> {
            double partial = state.partialTick() + (double) delta * state.rate();
            // ClientClockManager uses the int-valued floor, even for unusually large updates.
            long whole = (int) Math.floor(partial);
            return new State(state.totalTicks() + whole, (float) (partial - whole), state.rate());
        });
    }
    public void handleUpdates(long gameTime, Map<String, State> updates) {
        tick(gameTime);
        clocks.putAll(updates);
    }
}
