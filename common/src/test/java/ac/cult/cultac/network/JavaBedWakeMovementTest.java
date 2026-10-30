package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAnimate;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JavaBedWakeMovementTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final BlockPos HEAD = new BlockPos(0, FLOOR_Y, 0);

    @Test
    void holdingForwardThroughTheWakeUpIsPredictedExactly() throws Exception {
        try (var f = new JavaTickFixture()) {
            var registry = DataTables.defaults().registry();
            int bed = registry.with(registry.block("minecraft:red_bed").defaultState(), "facing", "south");
            f.player.compensatedWorld.updateBlock(HEAD, registry.with(bed, "part", "head"));
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, -1), bed);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            Vec3 standUp = new Vec3(-0.5, FLOOR_Y, 0.5);
            f.place(standUp, Vec3.ZERO);
            f.tick(standUp, true);
            Vec3 sleepPosition = new Vec3(0.5, FLOOR_Y + 0.6875, 0.5);
            f.teleport(sleepPosition, new RelativeFlag(0));
            sleepingPos(f, Optional.of(HEAD));
            f.input(NONE);
            command(f, PlayerCommandAction.STOP_SPRINTING);
            sleepTick(f, sleepPosition, false);
            sleepTick(f, new Vec3(0.5, FLOOR_Y + 0.60909999847412, 0.5), false);
            sleepTick(f, new Vec3(0.5, FLOOR_Y + 0.5625, 0.5), true);
            sleepTick(f, new Vec3(0.5, FLOOR_Y + 0.6150560409655, 0.5), false);
            sleepTick(f, new Vec3(0.5, FLOOR_Y + 0.58816096058824, 0.5), false);
            sleepTick(f, new Vec3(0.5, FLOOR_Y + 0.5625, 0.5), true);
            sleepTick(f, new Vec3(0.5, FLOOR_Y + 0.57505219488203, 0.5), false);
            sleepTick(f, new Vec3(0.5, FLOOR_Y + 0.5625, 0.5), true);
            assertTrue(f.player.isInBed);

            command(f, PlayerCommandAction.STOP_SLEEPING);
            wakeUp(f);
            f.teleport(f.player.sendTrailingTransaction(), standUp, new RelativeFlag(0), 0, -90.0F);
            assertFalse(f.player.isInBed);
            f.input(FORWARD_SPRINT);
            command(f, PlayerCommandAction.START_SPRINTING);
            PredictionResult result =
                    f.tick(standUp.add(ClientMoveInput.of(f, FORWARD_SPRINT, 1.0F, 0.13F, -90.0F)), false);

            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void aSleeperStillMovingWhenItsBedArrivesTravelsOnFromTheBed() throws Exception {
        travelsOnFromTheBed("minecraft:red_bed", 0.6875);
    }

    @Test
    void aStrawBedSleeperTravelsOnFromTheTopOfItsPillow() throws Exception {
        // AbstractBedBlock#getSleepHeight: the straw bed head's pillow tops out at 5/16, plus setPosToBed's 0.125.
        travelsOnFromTheBed("minecraft:straw_bed", 0.4375);
    }

    /**
     * The sleep teleport and the sleeping position arrive in different server ticks. The client travels one tick
     * before it handles the metadata, then LivingEntity#setPosToBed puts it back on the bed while Entity#setPos keeps
     * the deltaMovement that tick left, so its next travel starts from the bed.
     */
    private static void travelsOnFromTheBed(String block, double sleepHeight) throws Exception {
        try (var f = new JavaTickFixture()) {
            var registry = DataTables.defaults().registry();
            int bed = registry.with(registry.block(block).defaultState(), "facing", "south");
            f.player.compensatedWorld.updateBlock(HEAD, registry.with(bed, "part", "head"));
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, -1), bed);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            Vec3 standUp = new Vec3(-0.5, FLOOR_Y, 0.5);
            f.place(standUp, Vec3.ZERO);
            f.tick(standUp, true);
            Vec3 sleepPosition = new Vec3(0.5, FLOOR_Y + sleepHeight, 0.5);
            f.teleport(sleepPosition, new RelativeFlag(0));
            f.input(FORWARD);
            Vec3 airInput = ClientMoveInput.of(f, FORWARD, 1.0F, 0.02F, 0.0F);
            Vec3 beforeBed = sleepPosition.add(airInput);
            assertTrue(exact(f.tick(beforeBed, false)));

            sleepingPos(f, Optional.of(HEAD));
            assertEquals(sleepPosition, new Vec3(f.player.x, f.player.y, f.player.z));
            f.input(NONE);
            Vec3 carried = new Vec3(airInput.x * (double) 0.91F, -0.08D * (double) 0.98F, airInput.z * (double) 0.91F);
            PredictionResult result = f.tick(sleepPosition.add(carried), false);

            assertTrue(exact(result), describe(result));
        }
    }

    private static void sleepTick(JavaTickFixture f, Vec3 at, boolean onGround) {
        f.move(at, onGround);
        f.tickEnd();
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }

    private static void wakeUp(JavaTickFixture f) {
        var packet = new ClientboundAnimate(f.player.entityID, 0);
        var event =
                new PacketSendEvent<>(f.player.user, ConnectionPhase.PLAY, ClientboundPackets.ANIMATE, packet, false);
        new PacketSelfMetadataListener().onAnimate(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void sleepingPos(JavaTickFixture f, Optional<BlockPos> bed) {
        var packet = new EntityMetadata(
                f.player.entityID,
                List.of(new EntityMetadata.Entry(WatchableIndexUtil.LIVING_SLEEPING_POS, bed, ByteBuffer.allocate(0))));
        var event = new PacketSendEvent<>(f.player.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        new PacketSelfMetadataListener().onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }
}
