package ac.cult.cultac.cloud.capture;

import ac.cult.cultac.player.ClientProfile;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/** When a segment's client profile goes out: right after its JOIN, then on change, and at least every heartbeat. */
public final class ProfileUploads {
    /** How often the profile is compared with the one last sent. */
    public static final long CHECK_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** The longest an unchanged profile goes unsent. */
    public static final long HEARTBEAT_NANOS = TimeUnit.MINUTES.toNanos(5);

    private @Nullable ClientProfile sent;
    private long sentNanos;

    /** Whether {@code current} goes out at {@code nowNanos}. */
    public boolean due(ClientProfile current, long nowNanos) {
        return sent == null || !sent.equals(current) || nowNanos - sentNanos >= HEARTBEAT_NANOS;
    }

    public void sent(ClientProfile profile, long nowNanos) {
        sent = profile;
        sentNanos = nowNanos;
    }

    /** A new socket knows no profile: the next one is due. */
    public void reset() {
        sent = null;
    }
}
