package ac.cult.cultac.protocol;

/** A packet cannot be decoded and its frame is dropped, never forwarded unchanged. */
public final class DroppedPacketException extends RuntimeException {
    public DroppedPacketException(String message) {
        super(message);
    }
}
