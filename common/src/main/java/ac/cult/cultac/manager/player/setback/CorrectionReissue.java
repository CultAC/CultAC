package ac.cult.cultac.manager.player.setback;

import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;

/** What answer matching asks of the correction in flight when the client ignores or cannot take it. */
public interface CorrectionReissue {
    /** The client ignored the correction's teleport: send it again. */
    void resendIgnored(String reason);

    /** Observe-only: stop waiting for the correction and take {@code position} as safe. */
    void release(Vec3 position);

    /** The client is off the vehicle a correction moved: send it as the player's, from where the vehicle left it. */
    void sendDismounted(SetBackData data);

    boolean isDebug();
}
