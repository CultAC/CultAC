package ac.cult.cultac.platform.api;

/** What a platform reports about a command it was asked to run: {@code ok} when it ran or was handed on. */
public record CommandOutcome(boolean ok, String message) {
    public static final CommandOutcome RAN = new CommandOutcome(true, "dispatched");
    public static final CommandOutcome UNKNOWN = new CommandOutcome(false, "unknown command");
    /** Handed to a backend that did not answer in time; it may still run there. */
    public static final CommandOutcome SENT_TO_BACKEND = new CommandOutcome(true, "sent to backend");
    /** Dispatched on a platform that does not say whether the command ran. */
    public static final CommandOutcome SENT = new CommandOutcome(true, "sent");

    /** {@link #RAN} when the platform found and ran the command, else {@link #UNKNOWN}. */
    public static CommandOutcome of(boolean ran) {
        return ran ? RAN : UNKNOWN;
    }
}
