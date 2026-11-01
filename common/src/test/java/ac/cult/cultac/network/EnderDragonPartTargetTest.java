package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsW;
import ac.cult.cultac.checks.impl.combat.EntityReach;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.Vec3d;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A vanilla 26.3 client punches a perched ender dragon (CultAC issue #160): the capture's dragon is entity 447 (its
 * set_entity_data index 16, EnderDragon#DATA_PHASE, is 5, SittingFlaming) and the attacks target 449, 450 and 452.
 * EnderDragon#recreateFromPacket gives its eight EnderDragonParts the ids 448 to 455, the client picks the part under
 * the crosshair from Level#getEntities, and MultiPlayerGameMode#attack sends that part's id.
 */
class EnderDragonPartTargetTest {
    private static final int DRAGON = 447;
    private static final int ENDERMAN = 556;

    @Test
    void attacksOnTheDragonsPartsAreKnownTargets() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = check(f);
            var reach = reach(f);
            spawn(f, DRAGON, "minecraft:ender_dragon", true);
            for (int part : new int[] {449, 450, 452, DRAGON + 1, DRAGON + 8}) attack(f, check, part);
            spectate(f, check, 450);
            verify(check, never()).flag(anyString());
            assertFalse(reachCancels(f, reach, 450));
        }
    }

    /** ClientLevel#onTrackingStart adds the parts only once the client has processed the dragon's spawn. */
    @Test
    void attacksOnThePartsOfAnUnansweredDragonSpawnAreCancelledLikeItsOwnId() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = check(f);
            var reach = reach(f);
            spawn(f, DRAGON, "minecraft:ender_dragon", false);
            attack(f, check, 450);
            verify(check, never()).flag(anyString());
            assertTrue(reachCancels(f, reach, DRAGON));
            assertTrue(reachCancels(f, reach, 450));
        }
    }

    @Test
    void idsBesideTheDragonsPartsStayUnknown() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = check(f);
            spawn(f, DRAGON, "minecraft:ender_dragon", true);
            spawn(f, ENDERMAN, "minecraft:enderman", true);
            attack(f, check, DRAGON + 9);
            verify(check).flag("entityId=" + (DRAGON + 9));
            attack(f, check, DRAGON - 1);
            verify(check).flag("entityId=" + (DRAGON - 1));
            attack(f, check, ENDERMAN + 1);
            verify(check).flag("entityId=" + (ENDERMAN + 1));
            spectate(f, check, DRAGON + 9);
            verify(check, times(2)).flag("entityId=" + (DRAGON + 9));
        }
    }

    private static BadPacketsW check(RecordReceiveFixture f) {
        var check = spy(new BadPacketsW(f.player));
        doReturn(false).when(check).shouldModifyPackets();
        return check;
    }

    private static EntityReach reach(RecordReceiveFixture f) {
        EntityReach reach = f.player.checkManager.getCheck(EntityReach.class);
        reach.setEnabled(true);
        assertTrue(reach.shouldModifyPackets());
        return reach;
    }

    /** The dragon's spawn, with the client's answer to the ping after it when {@code answered}. */
    private static void spawn(RecordReceiveFixture f, int id, String type, boolean answered) {
        if (f.user.getPlayer() == null) f.user.bind(Mockito.mock(PlatformPlayer.class));
        var packet = new ClientboundAddEntity(
                id, type, new Vec3d(0.5, 64, 0.5), 0, 0, 0, new UUID(0, id), new Vec3d(0, 0, 0));
        var event = new PacketSendEvent<>(f.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, packet, false);
        f.player.packetEntityReplication.onAddEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        if (answered) {
            for (var write : event.writesAfter()) {
                if (write.packet() instanceof ClientboundPing ping)
                    assertTrue(f.player.addTransactionResponse(ping.id()));
            }
        }
        assertTrue(f.player.compensatedEntities.serverPositionsMap.containsKey(id));
        assertEquals(answered, f.player.compensatedEntities.entityMap.containsKey(id));
    }

    private static PacketReceiveEvent<ServerboundInteract> attackEvent(RecordReceiveFixture f, int id) {
        var attack = new ServerboundInteract(id, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
        return new PacketReceiveEvent<>(f.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, attack);
    }

    private static void attack(RecordReceiveFixture f, BadPacketsW check, int id) {
        var event = attackEvent(f, id);
        check.onInteract(event, f.player, event.getPacket());
    }

    private static boolean reachCancels(RecordReceiveFixture f, EntityReach reach, int id) {
        var event = attackEvent(f, id);
        reach.onInteract(event, f.player, event.getPacket());
        return event.isCancelled();
    }

    private static void spectate(RecordReceiveFixture f, BadPacketsW check, int id) {
        var action = new ServerboundSpectatorAction(OptionalInt.of(id));
        check.onSpectatorAction(
                new PacketReceiveEvent<>(f.user, ConnectionPhase.PLAY, ServerboundPackets.SPECTATOR_ACTION, action),
                f.player,
                action);
    }
}
