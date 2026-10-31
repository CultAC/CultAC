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
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.enums.Pose;
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
        holdsForwardThroughTheWakeUp(false);
    }

    /**
     * A ceiling two blocks above the floor leaves no room to stand or crouch at the bed's height, but
     * LivingEntity#stopSleeping still sets STANDING, so the first travel after the wake-up does not crawl.
     */
    @Test
    void holdingForwardThroughTheWakeUpUnderALowCeilingIsPredictedExactly() throws Exception {
        holdsForwardThroughTheWakeUp(true);
    }

    private static void holdsForwardThroughTheWakeUp(boolean lowCeiling) throws Exception {
        try (var f = new JavaTickFixture()) {
            var registry = DataTables.defaults().registry();
            int bed = registry.with(registry.block("minecraft:red_bed").defaultState(), "facing", "south");
            f.player.compensatedWorld.updateBlock(HEAD, registry.with(bed, "part", "head"));
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, -1), bed);
            if (lowCeiling) {
                int stone = registry.block("minecraft:stone").defaultState();
                for (int x = -2; x <= 1; x++)
                    for (int z = -2; z <= 1; z++)
                        f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 2, z), stone);
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            Vec3 standUp = new Vec3(-0.5, FLOOR_Y, 0.5);
            f.place(standUp, Vec3.ZERO);
            f.tick(standUp, true);
            Vec3 sleepPosition = new Vec3(0.5, FLOOR_Y + 0.6875, 0.5);
            f.teleport(sleepPosition, new RelativeFlag(0));
            sleepingPos(f, Optional.of(HEAD));
            // The stripped pose metadata leaves the pose to Player#updatePlayerPose after the next aiStep.
            assertEquals(Pose.STANDING, f.player.pose);
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
            assertEquals(Pose.SLEEPING, f.player.pose);

            command(f, PlayerCommandAction.STOP_SLEEPING);
            wakeUp(f);
            assertEquals(Pose.STANDING, f.player.pose);
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
        travelsOnFromTheBed("minecraft:red_bed", 0.6875, 0.5625);
    }

    @Test
    void aStrawBedSleeperTravelsOnFromTheTopOfItsFoot() throws Exception {
        // StrawBedBlock#getSleepHeight measures the head's foot, whose shape tops out at 4/16, under the pillow's
        // 5/16; LivingEntity#setPosToBed adds 0.125. The sleeper then lands on the head's pillow.
        travelsOnFromTheBed("minecraft:straw_bed", 0.375, 0.3125);
    }

    /** StrawBedBlock#getSleepHeight is empty for a head without its foot, so LivingEntity#setPosToBed leaves it. */
    @Test
    void aStrawBedHeadWithoutItsFootLeavesTheSleeper() throws Exception {
        try (var f = new JavaTickFixture()) {
            var registry = DataTables.defaults().registry();
            int bed = registry.with(registry.block("minecraft:straw_bed").defaultState(), "facing", "south");
            f.player.compensatedWorld.updateBlock(HEAD, registry.with(bed, "part", "head"));
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            Vec3 position = new Vec3(-0.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            f.tick(position, true);
            sleepingPos(f, Optional.of(HEAD));
            assertTrue(f.player.isInBed);
            assertEquals(position, new Vec3(f.player.x, f.player.y, f.player.z));
        }
    }

    /**
     * The sleep teleport and the sleeping position arrive in different server ticks. The client travels one tick
     * before it handles the metadata, then LivingEntity#setPosToBed puts it back on the bed while Entity#setPos keeps
     * the deltaMovement that tick left, so its next travel starts from the bed and stops on its top at
     * {@code restHeight}.
     */
    private static void travelsOnFromTheBed(String block, double sleepHeight, double restHeight) throws Exception {
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
            Vec3 travelled = sleepPosition.add(carried);
            boolean lands = travelled.y < FLOOR_Y + restHeight;
            if (lands) travelled = new Vec3(travelled.x, FLOOR_Y + restHeight, travelled.z);
            PredictionResult result = f.tick(travelled, lands);

            assertTrue(exact(result), describe(result));
        }
    }

    /**
     * A remote LivingEntity#pushEntities reaches only the LocalPlayer, through player.push(pusher), which
     * LivingEntity#push(Entity) skips while the player isSleeping. With no bed at the sleeping position, 26.3's
     * LivingEntity#setPosToBed leaves the sleeper standing beside the pusher, as after a pig ride into a bed.
     */
    @Test
    void aSleeperBesideALivingEntityIsNotPushed() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, -0.08 * (double) 0.98F, 0));
            f.tick(position, true);
            sleepingPos(f, Optional.of(new BlockPos(0, FLOOR_Y, 3)));
            assertTrue(f.player.isInBed);
            assertEquals(position, new Vec3(f.player.x, f.player.y, f.player.z));
            f.addRemotePlayer(100, position.add(0.2, 0, 0.1), GameMode.SURVIVAL);
            for (int tick = 0; tick < 3; tick++) {
                PredictionResult result = f.tick(position, true);
                assertTrue(exact(result), describe(result));
            }
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
