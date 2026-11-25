package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.player.ClientClassification;
import ac.cult.cultac.player.ClientClassification.Type;
import ac.cult.cultac.player.ClientProfile;
import org.junit.jupiter.api.Test;

/** When a segment's client profile goes out again. */
class ProfileUploadsTest {
    private static final ClientClassification TYPES = new ClientClassification(Type.VANILLA, Type.VANILLA, Type.UNSET);

    private static ClientProfile profile(double sensitivity) {
        return new ClientProfile(false, 775, "26.3", "vanilla", TYPES, sensitivity, sensitivity, false);
    }

    @Test
    void aProfileGoesOutAgainWhenItChangesOrTheHeartbeatComes() {
        ProfileUploads uploads = new ProfileUploads();
        long start = 1_000;
        assertTrue(uploads.due(profile(Double.NaN), start));
        uploads.sent(profile(Double.NaN), start);

        assertFalse(uploads.due(profile(Double.NaN), start + ProfileUploads.CHECK_NANOS));
        long changed = start + 2 * ProfileUploads.CHECK_NANOS;
        assertTrue(uploads.due(profile(100), changed));
        uploads.sent(profile(100), changed);

        assertFalse(uploads.due(profile(100), changed + ProfileUploads.HEARTBEAT_NANOS - 1));
        assertTrue(uploads.due(profile(100), changed + ProfileUploads.HEARTBEAT_NANOS));
        uploads.reset();
        assertTrue(uploads.due(profile(100), changed + 1));
    }
}
