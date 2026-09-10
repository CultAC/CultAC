package ac.cult.cultac.bedrock.protocol;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class BedrockAuthInputPluginMessageTest {
    private static final UUID PLAYER_UUID = UUID.fromString("7cc5c1f7-4b66-4c7c-bbb6-b1b09478015d");

    @Test
    public void authInputRoundTripPreservesHighInputFlags() {
        long sneakCurrentRaw = 1L << (PlayerAuthInputData.SNEAK_CURRENT_RAW.ordinal() - Long.SIZE);
        BedrockAuthInputFrame frame = BedrockAuthInputFrame.builder(PLAYER_UUID)
                .protocolVersion(1001)
                .clientTick(42L)
                .position(Vec3.ZERO)
                .packetPosition(Vec3.ZERO)
                .delta(Vec3.ZERO)
                .rotation(0.0F, 0.0F, 0.0F)
                .moveVector(0.0F, 0.0F)
                .rawInputFlagsHigh(sneakCurrentRaw)
                .projectedOnGround(true)
                .authorityMode("client-auth-input")
                .build();

        BedrockAuthInputFrame decoded = BedrockAuthInputPluginMessage.decode(
                BedrockAuthInputPluginMessage.encode(frame));

        assertNotNull(decoded);
        assertEquals(sneakCurrentRaw, decoded.getRawInputFlagsHigh());
        assertTrue(decoded.hasRawInputFlag(PlayerAuthInputData.SNEAK_CURRENT_RAW));
        assertTrue(decoded.isProjectedOnGround());
    }

    @Test
    public void authInputRoundTripPreservesReportedVelocity() {
        BedrockAuthInputFrame frame = BedrockAuthInputFrame.builder(PLAYER_UUID)
                .protocolVersion(1001)
                .clientTick(42L)
                .position(Vec3.ZERO)
                .packetPosition(Vec3.ZERO)
                .delta(Vec3.ZERO)
                .reportedEndOfTickVelocity(new Vec3(0.125F, -0.0784F, -0.25F))
                .rotation(0.0F, 0.0F, 0.0F)
                .moveVector(0.0F, 0.0F)
                .authorityMode("client-auth-input")
                .build();

        BedrockAuthInputFrame decoded = BedrockAuthInputPluginMessage.decode(
                BedrockAuthInputPluginMessage.encode(frame));

        assertNotNull(decoded);
        assertEquals(frame.getReportedEndOfTickVelocity(), decoded.getReportedEndOfTickVelocity());
    }

    @Test
    public void acknowledgedMetadataRoundTripsPartialUpdates() {
        var gliding = BedrockAuthInputPluginMessage.decodeAcknowledgedMetadata(
                BedrockAuthInputPluginMessage.encodeAcknowledgedMetadata(
                        PLAYER_UUID, null, 0.6F, true, true, false));
        var standing = BedrockAuthInputPluginMessage.decodeAcknowledgedMetadata(
                BedrockAuthInputPluginMessage.encodeAcknowledgedMetadata(
                        PLAYER_UUID, 0.6F, 1.8F, false, false, false));

        assertNotNull(gliding);
        assertEquals(PLAYER_UUID, gliding.playerUuid());
        assertEquals(null, gliding.width());
        assertEquals(Float.valueOf(0.6F), gliding.height());
        assertEquals(Boolean.TRUE, gliding.gliding());
        assertEquals(Boolean.TRUE, gliding.crawling());
        assertEquals(Boolean.FALSE, gliding.swimming());
        assertNotNull(standing);
        assertEquals(Float.valueOf(0.6F), standing.width());
        assertEquals(Float.valueOf(1.8F), standing.height());
        assertEquals(Boolean.FALSE, standing.gliding());
        assertEquals(Boolean.FALSE, standing.crawling());
        assertEquals(Boolean.FALSE, standing.swimming());
    }

}
