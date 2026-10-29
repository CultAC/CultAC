package ac.cult.cultac.network.routing;

/** Takes the network messages of one type; see {@link NetworkService#on}. Runs on the service's thread. */
@FunctionalInterface
public interface MessageHandler {

    void handle(Envelope envelope);
}
