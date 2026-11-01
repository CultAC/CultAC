package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import ac.cult.cultac.checks.impl.combat.MultiInteractA;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.Vec3d;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#handleKeybinds sends a tick's attack and interactions with one LocalPlayer#isShiftKeyDown. Below 26.1
 * MultiPlayerGameMode#attack sends ServerboundInteractPacket#createAttackPacket with it; from 26.1 it sends
 * ServerboundAttackPacket with the entity id.
 */
class MultiInteractASneakTest {
    private static final int BOAT = 835;

    @Test
    void aSneakingInteractAfterAnAttackInTheSameTickIsNotFlagged() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_1)) {
            var check = check(f, ProtocolVersion.V26_1);
            assertFalse(interact(f, check, Hand.MAIN_HAND, false).isCancelled());
            move(f, check);

            interact(
                    f,
                    check,
                    new ServerboundInteract(BOAT, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false));
            assertFalse(interact(f, check, Hand.MAIN_HAND, true).isCancelled());
            assertFalse(interact(f, check, Hand.OFF_HAND, true).isCancelled());
        }
    }

    @Test
    void interactsWithDifferentShiftStatesInOneTickAreFlagged() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_1)) {
            var check = check(f, ProtocolVersion.V26_1);
            assertFalse(interact(f, check, Hand.MAIN_HAND, true).isCancelled());
            assertTrue(interact(f, check, Hand.OFF_HAND, false).isCancelled());
        }
    }

    @Test
    void anAttackAndAnInteractWithDifferentShiftStatesInOneTickAreFlaggedBelow26_1() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V1_21_4)) {
            var check = check(f, ProtocolVersion.V1_21_4);
            interact(
                    f,
                    check,
                    new ServerboundInteract(BOAT, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), true));
            assertTrue(interact(f, check, Hand.MAIN_HAND, false).isCancelled());
        }
    }

    private static MultiInteractA check(RecordReceiveFixture f, ProtocolVersion version) {
        when(f.connection.platform().getClientProtocol()).thenReturn(version);
        VerboseCodecs.ensureRegistered();
        f.player.setExperimentalChecks(true);
        var check = spy(new MultiInteractA(f.player));
        doReturn(true).when(check).alert(any(Supplier.class));
        doReturn(true).when(check).shouldModifyPackets();
        return check;
    }

    private static PacketReceiveEvent<ServerboundInteract> interact(
            RecordReceiveFixture f, MultiInteractA check, Hand hand, boolean sneaking) {
        return interact(
                f,
                check,
                new ServerboundInteract(
                        BOAT, InteractAction.INTERACT_AT, hand, Optional.of(new Vec3d(0, 0.5, 0)), sneaking));
    }

    private static PacketReceiveEvent<ServerboundInteract> interact(
            RecordReceiveFixture f, MultiInteractA check, ServerboundInteract packet) {
        var event = new PacketReceiveEvent<>(f.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, packet);
        check.onInteractEntity(event, f.player, packet);
        return event;
    }

    private static void move(RecordReceiveFixture f, MultiInteractA check) {
        var packet = new ServerboundMovePlayer(0, 0, 0, 0, 0, true, false, false, true);
        check.onMovePlayer(
                new PacketReceiveEvent<>(f.user, ConnectionPhase.PLAY, ServerboundPackets.MOVE_PLAYER, packet),
                f.player,
                packet);
    }
}
