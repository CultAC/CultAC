package ac.cult.cultac.utils.data;

/**
 * One packet-ordered decision for the Java projection of a Bedrock auth frame.
 */
public final class BedrockTranslatedMovementGate {
    public sealed interface Decision permits Player, Vehicle, Rejected {
    }

    public record Player(boolean canonicalGround) implements Decision {
    }

    public record Vehicle(int entityId) implements Decision {
    }

    public enum Rejected implements Decision {INSTANCE}

    private Decision pending;

    public void allowPlayer(boolean canonicalGround) {
        offer(new Player(canonicalGround));
    }

    public void allowVehicle(int entityId) {
        offer(new Vehicle(entityId));
    }

    public void reject() {
        offer(Rejected.INSTANCE);
    }

    private void offer(Decision decision) {
        // Later outcomes from the same frame cannot replace its first decision.
        if (pending == null) pending = decision;
    }

    public boolean isRejected() {
        return pending == Rejected.INSTANCE;
    }

    public boolean hasPending() {
        return pending != null;
    }

    public Decision take() {
        Decision decision = pending;
        pending = null;
        return decision;
    }

    public Player takePlayer() {
        Decision decision = take();
        return decision instanceof Player player ? player : null;
    }

    public void clear() {
        pending = null;
    }
}
