package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.prediction.state.BedrockActorAttributes;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.latency.LatencyUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.junit.Test;

public class GeyserEntityAttributesTest {
    @Test
    public void remoteAttributesWaitForReceiptAndSpawnWithoutRequiringAMount() {
        var harness = new Harness();
        harness.capture(4, 0.35245588F);
        assertTrue(harness.horse.bedrockAttributes.values().isEmpty());
        harness.boundary();
        assertTrue(harness.horse.bedrockAttributes.values().isEmpty());
        harness.receipts.removeFirst().run();
        assertTrue(harness.horse.bedrockAttributes.values().isEmpty());
        harness.spawns.removeFirst().run();
        assertEquals(0.35245588F, harness.horse.bedrockAttributes.movement().current(), 0);
        assertTrue(harness.self.bedrockAttributes.values().isEmpty());
    }

    @Test
    public void separateActorsAndOmittedAttributesRemainIndependent() {
        var harness = new Harness();
        harness.capture(3, 0.14F);
        harness.capture(4, 0.35F);
        harness.boundary();
        harness.receipts.removeFirst().run();
        harness.spawns.forEach(Runnable::run);
        assertEquals(0.14F, harness.self.bedrockAttributes.movement().current(), 0);
        assertEquals(0.35F, harness.horse.bedrockAttributes.movement().current(), 0);
    }

    // Replays of the jump strength attribute set to 0.8, 0.1 and 0.5 on the player: every
    // jump start still rises 0.42, while a ridden horse launches by its own value.
    @Test
    public void jumpStrengthReachesAHorseButNeverThePlayersAttributes() {
        var harness = new Harness();
        harness.capture(3, new AttributeData("minecraft:horse.jump_strength", 0, 2, 0.8F, 0.7F));
        assertFalse(harness.tracker.hasPending());
        harness.capture(
                3,
                new AttributeData("minecraft:movement", 0, 1024, 0.14F, 0.1F),
                new AttributeData("minecraft:horse.jump_strength", 0, 2, 0.1F, 0.7F));
        harness.capture(4, new AttributeData("minecraft:horse.jump_strength", 0, 2, 0.8F, 0.7F));
        harness.boundary();
        harness.receipts.removeFirst().run();
        harness.spawns.forEach(Runnable::run);
        assertEquals(0.14F, harness.self.bedrockAttributes.movement().current(), 0);
        assertFalse(harness.self.bedrockAttributes.values().containsKey("minecraft:horse.jump_strength"));
        assertEquals(
                0.42F,
                harness.self
                        .bedrockAttributes
                        .apply(ac.cult.cultac.bedrock.prediction.model.AttributeState.DEFAULT)
                        .jumpStrength(),
                0);
        assertEquals(0.8F, harness.horse.bedrockAttributes.current("minecraft:horse.jump_strength", 0), 0);
    }

    @Test
    public void entityIdReuseCannotReceiveAnOldRuntimeActorsAttributes() {
        var harness = new Harness();
        harness.capture(4, 0.35F);
        harness.boundary();
        harness.receipts.removeFirst().run();
        harness.horse.bedrockRuntimeId = 5;
        harness.spawns.removeFirst().run();
        assertTrue(harness.horse.bedrockAttributes.values().isEmpty());
    }

    private static final class Harness {
        final CultPlayer player = mock(CultPlayer.class);
        final PacketEntitySelf self = mock(PacketEntitySelf.class);
        final PacketEntity horse = mock(PacketEntity.class);
        final GeyserSession session = mock(GeyserSession.class, RETURNS_DEEP_STUBS);
        final GeyserEntityAttributes tracker = new GeyserEntityAttributes();
        final List<Runnable> receipts = new ArrayList<>();
        final List<Runnable> spawns = new ArrayList<>();

        Harness() {
            player.compensatedEntities = mock(CompensatedEntities.class, RETURNS_DEEP_STUBS);
            player.latencyUtils = mock(LatencyUtils.class);
            player.bedrockState = new BedrockPlayerState(UUID.randomUUID());
            self.bedrockRuntimeId = 3;
            horse.bedrockRuntimeId = 4;
            self.bedrockAttributes = BedrockActorAttributes.EMPTY;
            horse.bedrockAttributes = BedrockActorAttributes.EMPTY;
            when(player.compensatedEntities.getSelf()).thenReturn(self);
            when(player.compensatedEntities.getEntity(71)).thenReturn(horse);
            when(session.getPlayerEntity().geyserId()).thenReturn(3L);
            when(session.getPlayerEntity().getEntityId()).thenReturn(1);
            when(session.getEntityCache().getEntityByGeyserId(4).getEntityId()).thenReturn(71);
            doAnswer(call -> {
                        receipts.add(call.getArgument(1));
                        return null;
                    })
                    .when(player)
                    .addBedrockTransactionTask(any(), any());
            doAnswer(call -> {
                        spawns.add(call.getArgument(1));
                        return null;
                    })
                    .when(player.latencyUtils)
                    .addRealTimeTask(anyInt(), any(Runnable.class));
        }

        void capture(long actor, float value) {
            capture(actor, new AttributeData("minecraft:movement", 0, 1024, value, 0.1F));
        }

        void capture(long actor, AttributeData... attributes) {
            var packet = new UpdateAttributesPacket();
            packet.setRuntimeEntityId(actor);
            packet.setAttributes(List.of(attributes));
            tracker.capture(session, player, GeyserReplayUpdate.capture(packet));
        }

        void boundary() {
            try {
                var constructor = CultPlayer.BedrockTransaction.class.getDeclaredConstructor(int.class, int.class);
                constructor.setAccessible(true);
                tracker.boundary(player, constructor.newInstance(0, 1));
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
