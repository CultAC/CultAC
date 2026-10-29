package ac.cult.cultac.network.routing;

/** Takes the network messages of one type; see {@link NetworkService#on}. Runs on the service's thread. */
@FunctionalInterface
public interface MessageHandler {

    /** How a message reached this generation. */
    enum Origin {
        /** From another instance, on this instance's direct channel. */
        DIRECT,
        /** From another instance, on the broadcast channel. */
        BROADCAST,
        /** From this instance, possibly this very generation, over the in-process bus. */
        LOCAL
    }

    void handle(Envelope envelope, Origin origin);
}
