package ac.cult.cultac.bedrock.prediction;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class BedrockRewindDebugTest {
    @Test public void positionsAndSignedOffsetUseFiveDecimalsRegardlessOfLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("rewind c=(1.25000,64.12500,-2.50000) s=(1.00000,64.25000,-2.75000) o=(0.25000,-0.12500,0.25000) [42]",
                    BedrockPredictionDebug.formatRewind(new Vec3(1.25, 64.125, -2.5),
                            new Vec3(1, 64.25, -2.75), 42));
        } finally { Locale.setDefault(previous); }
    }

    @Test public void rewindSubscriptionIsIndependentAndRemovesDisconnectedListeners() {
        OfflineCultTestBootstrap.installConfig();
        EmbeddedChannel channel = new EmbeddedChannel();
        UUID uuid = UUID.randomUUID();
        var player = new CultPlayer(new User(new User.Profile(uuid, ".Rewind_Test"), null, null, null, channel),
                MovementPlatform.BEDROCK, new BedrockPlayerState(uuid));
        try {
            var debug = player.checkManager.getDebugHandler();
            Sender listener = mock(Sender.class);
            when(listener.getUniqueId()).thenReturn(UUID.randomUUID());
            when(listener.isValid()).thenReturn(true);
            when(listener.hasPermission("cult.debug")).thenReturn(true);
            debug.relayRewind(() -> { fail("No rewind subscriber"); return ""; });
            assertTrue(debug.toggleRewindListener(listener));
            debug.relayRewind(() -> "rewind example [42]");
            verify(listener).sendMessage(Component.text(player.getName() + " rewind example [42]"));
            assertFalse(debug.toggleRewindListener(listener));
            debug.relayRewind(() -> { fail("Rewind unsubscribed"); return ""; });
            assertTrue(debug.toggleRewindListener(listener));
            when(listener.isValid()).thenReturn(false);
            debug.relayRewind(() -> { fail("Disconnected listener"); return ""; });
            verify(listener, times(1)).sendMessage(any(Component.class));
        } finally { channel.finishAndReleaseAll(); }
    }
}
