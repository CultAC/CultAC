package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.player.GrimPlayer;
import org.junit.Test;
import org.bukkit.GameMode;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockMovementProfileTest {
    @Test
    public void bedrockUsesImmediatePacketVelocityModifiers() {
        BedrockMovementProfile profile = new BedrockMovementProfile();

        assertTrue(profile.usesPacketVelocityModifiers(null));
        assertFalse(profile.shouldUseBundledPacketProof(null));
    }

    @Test
    public void flightExemptionRequiresTheCompensatedMayFlyAbility() {
        BedrockMovementProfile profile = new BedrockMovementProfile();
        GrimPlayer player = Mockito.mock(GrimPlayer.class);
        player.gamemode = GameMode.CREATIVE;
        player.canFly = false;

        assertFalse(profile.usesFlyingExemption(player));

        player.canFly = true;
        assertTrue(profile.usesFlyingExemption(player));
    }
}
