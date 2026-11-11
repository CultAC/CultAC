package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertFalse;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import org.junit.Test;
import org.mockito.Mockito;

public final class BedrockMovementProfileTest {
    @Test
    public void bedrockUsesImmediatePacketVelocityModifiers() {
        BedrockMovementProfile profile = new BedrockMovementProfile();

        assertFalse(profile.shouldUseBundledPacketProof(null));
    }

    @Test
    public void flightIsCheckedEvenWithTheMayFlyAbility() {
        BedrockMovementProfile profile = new BedrockMovementProfile();
        CultPlayer player = Mockito.mock(CultPlayer.class);
        player.gamemode = GameMode.CREATIVE;
        player.canFly = true;

        assertFalse(profile.usesFlyingExemption(player));
    }
}
