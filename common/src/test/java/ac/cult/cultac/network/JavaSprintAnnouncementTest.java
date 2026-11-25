package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#aiStep sets the sprint flag only through canStartSprinting, which needs forward impulse and, from 1.21.5,
 * a player not moving slowly or under water; shouldStopRunSprinting clears it without forward impulse. The tick's
 * sendIsSprintingIfNeeded announces the result after PLAYER_INPUT, and the server's Player#attack reads it.
 */
class JavaSprintAnnouncementTest {
    private static final int TARGET = 300;
    private static final ServerboundPlayerInput NONE = keys(false, false);
    private static final ServerboundPlayerInput SNEAK = keys(false, true);
    private static final ServerboundPlayerInput FORWARD = keys(true, false);
    private static final ServerboundPlayerInput SNEAK_FORWARD = keys(true, true);
    private static final ServerboundPlayerInput BACK =
            new ServerboundPlayerInput(false, true, false, false, false, false, false);
    private static final float WALK = 0.1F;
    private static final float SPRINT = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    private static final byte SPRINTING = 0x08;
    private static final byte SWIMMING = 0x10;
    private static final int TOP_SLAB = DataTables.defaults()
            .registry()
            .with(DataTables.defaults().registry().block("minecraft:stone_slab").defaultState(), "type", "top");

    @Test
    void aStandingPlayerCannotStartASprint() throws Exception {
        for (ServerboundPlayerInput held : new ServerboundPlayerInput[] {NONE, SNEAK}) {
            try (var f = fixture()) {
                Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
                f.input(held);
                for (int tick = 0; tick < 3; tick++) assertTrue(exact(f.tick(at, true)), "warmup " + tick);
                assertTrue(command(f, PlayerCommandAction.START_SPRINTING).isCancelled(), "start with " + held);
                PredictionResult result = f.tick(at, true);
                assertTrue(exact(result), describe(result));
                assertTrue(attack(f).isCancelled(), "attack with " + held);
            }
        }
    }

    @Test
    void aPlayerHoldingBackCannotStartASprint() throws Exception {
        try (var f = fixture()) {
            Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
            f.input(NONE);
            assertTrue(exact(f.tick(at, true)));
            f.input(BACK);
            assertTrue(command(f, PlayerCommandAction.START_SPRINTING).isCancelled());
        }
    }

    @Test
    void aPlayerDriftingBackwardWithoutKeysCannotStartASprint() throws Exception {
        try (var f = fixture()) {
            Vec3 at = new Vec3(0.5, FLOOR_Y + 12, 0.5);
            Vec3 velocity = new Vec3(0.0, 0.0, -0.3);
            f.place(at, velocity);
            f.input(NONE);
            assertTrue(command(f, PlayerCommandAction.START_SPRINTING).isCancelled());
            at = at.add(velocity.x, velocity.y, velocity.z);
            PredictionResult result = f.tick(at, false);
            assertTrue(exact(result), describe(result));
            assertTrue(attack(f).isCancelled());
        }
    }

    @Test
    void releasingForwardEndsTheSprint() throws Exception {
        for (boolean stopAnnounced : new boolean[] {false, true}) {
            try (var f = fixture()) {
                Vec3[] state = {new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO};
                groundTick(f, state, FORWARD, 1.0F, WALK);
                assertFalse(command(f, PlayerCommandAction.START_SPRINTING).isCancelled());
                groundTick(f, state, FORWARD, 1.0F, SPRINT);
                assertFalse(attack(f).isCancelled(), "a sprint hit");
                f.input(NONE);
                if (stopAnnounced) command(f, PlayerCommandAction.STOP_SPRINTING);
                groundTick(f, state, NONE, 1.0F, SPRINT);
                assertTrue(attack(f).isCancelled() != stopAnnounced, "stop announced: " + stopAnnounced);
                // A new press of forward starts the next sprint.
                f.input(FORWARD);
                if (stopAnnounced)
                    assertFalse(command(f, PlayerCommandAction.START_SPRINTING).isCancelled());
            }
        }
    }

    @Test
    void aCrouchingPlayerCannotStartASprintButKeepsOne() throws Exception {
        for (boolean sprintFirst : new boolean[] {false, true}) {
            try (var f = fixture()) {
                Vec3[] state = {new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO};
                groundTick(f, state, FORWARD, 1.0F, WALK);
                if (sprintFirst) {
                    assertFalse(command(f, PlayerCommandAction.START_SPRINTING).isCancelled());
                    groundTick(f, state, FORWARD, 1.0F, SPRINT);
                }
                float speed = sprintFirst ? SPRINT : WALK;
                // The first sneaking tick's aiStep crouches only after travel reads the previous keys.
                groundTick(f, state, SNEAK_FORWARD, 1.0F, speed);
                for (int tick = 0; tick < 2; tick++) groundTick(f, state, SNEAK_FORWARD, 0.3F, speed);
                if (!sprintFirst) {
                    // The crouch comes from the previous tick's keys, so the start is decided before the move.
                    assertTrue(command(f, PlayerCommandAction.START_SPRINTING).isCancelled());
                    groundTick(f, state, SNEAK_FORWARD, 0.3F, SPRINT);
                }
                assertTrue(attack(f).isCancelled() != sprintFirst, "sprint first: " + sprintFirst);
            }
        }
    }

    @Test
    void aStartAnnouncedAfterAStopNeedsForwardInput() throws Exception {
        try (var f = fixture()) {
            Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
            f.input(NONE);
            assertTrue(exact(f.tick(at, true)));
            command(f, PlayerCommandAction.START_SPRINTING);
            assertTrue(exact(f.tick(at, true)));
            command(f, PlayerCommandAction.STOP_SPRINTING);
            assertTrue(command(f, PlayerCommandAction.START_SPRINTING).isCancelled());
        }
    }

    @Test
    void onlyASwimmerAnnouncesTheServersSprintWithoutForwardInput() throws Exception {
        for (boolean swimming : new boolean[] {false, true}) {
            try (var f = fixture()) {
                if (swimming) f.water(new BlockPos(-8, FLOOR_Y, -8), new BlockPos(8, FLOOR_Y + 2, 8));
                f.input(NONE);
                sharedFlags(f, swimming ? (byte) (SPRINTING | SWIMMING) : SPRINTING, true);
                assertTrue(
                        command(f, PlayerCommandAction.START_SPRINTING).isCancelled() != swimming,
                        "swimming: " + swimming);
            }
        }
    }

    @Test
    void aSwimEndsOutOfWaterBeforeTheSprintIsAnnounced() throws Exception {
        try (var f = fixture()) {
            Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
            f.input(NONE);
            assertTrue(exact(f.tick(at, true)));
            sharedFlags(f, (byte) (SPRINTING | SWIMMING), true);
            assertTrue(command(f, PlayerCommandAction.START_SPRINTING).isCancelled());
        }
    }

    @Test
    void theServersEchoOfTheSprintStartsNoSprintBeforeItsPing() throws Exception {
        try (var f = fixture()) {
            Vec3[] state = {new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO};
            groundTick(f, state, FORWARD, 1.0F, WALK);
            assertFalse(command(f, PlayerCommandAction.START_SPRINTING).isCancelled());
            groundTick(f, state, FORWARD, 1.0F, SPRINT);
            sharedFlags(f, SPRINTING, false);
            f.input(NONE);
            command(f, PlayerCommandAction.STOP_SPRINTING);
            assertTrue(command(f, PlayerCommandAction.START_SPRINTING).isCancelled());
        }
    }

    @Test
    void theClientAttacksBeforeItsTickStartsASprint() throws Exception {
        try (var f = fixture()) {
            Vec3[] state = {new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO};
            groundTick(f, state, FORWARD, 1.0F, WALK);
            assertFalse(command(f, PlayerCommandAction.START_SPRINTING).isCancelled());
            assertTrue(attack(f).isCancelled(), "before the move");
            Vec3 delta = state[1].add(ClientMoveInput.of(f, FORWARD, 1.0F, SPRINT, 0.0F));
            f.move(state[0].add(delta.x, 0.0, delta.z), true);
            assertTrue(attack(f).isCancelled(), "before the tick end");
            f.tickEnd();
            assertFalse(attack(f).isCancelled(), "the next tick's sprint hit");
        }
    }

    @Test
    void aPlayerAgainstAWallStartsASprintWithoutMoving() throws Exception {
        try (var f = fixture()) {
            wall(f);
            f.place(new Vec3(0.5, FLOOR_Y, 0.7), Vec3.ZERO);
            f.input(FORWARD);
            // The tick into the wall reports the collision; LocalPlayer#sendPosition sends nothing for the next one.
            f.statusTick(true, true);
            assertFalse(command(f, PlayerCommandAction.START_SPRINTING).isCancelled());
            f.tickEnd();
            assertFalse(attack(f).isCancelled());
        }
    }

    @Test
    void aStartWithoutMovementEndsWithItsTickAfterACancelledTickEnd() throws Exception {
        try (var f = fixture()) {
            wall(f);
            Vec3 at = new Vec3(0.5, FLOOR_Y, 0.7);
            f.place(at, Vec3.ZERO);
            // The client has the chunk, so the timer's setback blocks the movement.
            f.pongThrough(f.player.sendTrailingTransaction());
            f.input(NONE);
            f.tick(at, true);
            f.input(FORWARD);
            TimerCheck timer = f.player.checkManager.getCheck(TimerCheck.class);
            timer.setEnabled(true);
            JavaTickFixture.setField(
                    AbstractTimerCheck.class, timer, "timerBalanceRealTime", System.nanoTime() + 1_000_000_000L);
            var status = new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, true, true, false, false);
            var move = f.event(ServerboundPackets.MOVE_PLAYER, status);
            f.listener.onMovePlayer(move, f.player, status);
            assertTrue(move.isCancelled(), "the timer accepted the movement");
            var tickEnd = f.event(ServerboundPackets.CLIENT_TICK_END, ServerboundPackets.CLIENT_TICK_END.opaqueValue());
            f.listener.processClientTickEndReceive(tickEnd, f.player);
            assertTrue(tickEnd.isCancelled(), "the timer accepted the tick end");
            f.resetTimerBalances();
            // The next tick sends no movement: its tick end follows the start.
            assertFalse(command(f, PlayerCommandAction.START_SPRINTING).isCancelled());
            f.tickEnd();
            assertFalse(f.player.sprintState.startedSinceTickEnd(), "the start's tick end ended its window");
        }
    }

    @Test
    void aStartAfterAStopIsTheTicksOwnStart() throws Exception {
        try (var f = fixture()) {
            ceiling(f, TOP_SLAB);
            Vec3[] state = {new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO};
            for (int tick = 0; tick < 2; tick++) groundTick(f, state, FORWARD, 0.3F, WALK);
            assertFalse(attack(f).isCancelled(), "a crouching hit");
            // A ceiling without shift crouches in aiStep, so its canStartSprinting fails.
            command(f, PlayerCommandAction.START_SPRINTING);
            groundTick(f, state, FORWARD, 0.3F, SPRINT);
            assertTrue(attack(f).isCancelled(), "after the start");
            command(f, PlayerCommandAction.STOP_SPRINTING);
            command(f, PlayerCommandAction.START_SPRINTING);
            groundTick(f, state, FORWARD, 0.3F, SPRINT);
            assertTrue(attack(f).isCancelled(), "after the stop and start");
        }
    }

    @Test
    void aCeilingCrouchesThePlayerBeforeItsSprintStart() throws Exception {
        try (var f = fixture()) {
            ceiling(f, TOP_SLAB);
            Vec3[] state = {new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO};
            for (int tick = 0; tick < 2; tick++) groundTick(f, state, FORWARD, 0.3F, WALK);
            assertFalse(attack(f).isCancelled(), "a crouching hit");
            move(f, state, FORWARD, 0.3F, WALK);
            command(f, PlayerCommandAction.STOP_SPRINTING);
            // The next aiStep crouches where the standing box does not fit, without shift.
            assertTrue(command(f, PlayerCommandAction.START_SPRINTING).isCancelled(), "the start");
            f.tickEnd();
            assertTrue(attack(f).isCancelled(), "the next tick's hit");
        }
    }

    @Test
    void aCrawlingPlayerCannotStartASprint() throws Exception {
        try (var f = fixture()) {
            ceiling(f, BlockIds.STONE.defaultState());
            Vec3[] state = {new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO};
            // Player#updatePlayerPose crawls only after the entry tick's travel.
            groundTick(f, state, FORWARD, 1.0F, WALK);
            for (int tick = 0; tick < 2; tick++) groundTick(f, state, FORWARD, 0.3F, WALK);
            assertFalse(attack(f).isCancelled(), "a crawling hit");
            // LocalPlayer#sendPosition announces a start before its tick's movement, so this one is the next tick's.
            move(f, state, FORWARD, 0.3F, WALK);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            assertTrue(attack(f).isCancelled(), "before the start's move");
            move(f, state, FORWARD, 0.3F, SPRINT);
            command(f, PlayerCommandAction.STOP_SPRINTING);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.tickEnd();
            assertTrue(attack(f).isCancelled(), "after a stop and start");
        }
    }

    @Test
    void aSprintKeptThroughARespawnEndsWithAStop() throws Exception {
        for (boolean stopAnnounced : new boolean[] {false, true}) {
            try (var f = fixture()) {
                Vec3[] state = {new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO};
                groundTick(f, state, FORWARD, 1.0F, WALK);
                assertFalse(command(f, PlayerCommandAction.START_SPRINTING).isCancelled());
                groundTick(f, state, FORWARD, 1.0F, SPRINT);
                respawn(f, ClientboundRespawn.KEEP_ENTITY_DATA);
                f.player.packetStateData.playerLoadedIntoLevel = true;
                Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
                f.teleport(at, new RelativeFlag(0));
                f.place(at, Vec3.ZERO);
                // The new LocalPlayer's wasSprinting is the kept flag, so the aiStep that stops it announces STOP.
                f.input(NONE);
                if (stopAnnounced) assertFalse(command(f, PlayerCommandAction.STOP_SPRINTING).isCancelled());
                for (int tick = 0; tick < 2; tick++) {
                    PredictionResult result = f.tick(at, true);
                    assertTrue(exact(result), describe(result));
                }
                assertTrue(attack(f).isCancelled() != stopAnnounced, "stop announced: " + stopAnnounced);
            }
        }
    }

    private static JavaTickFixture fixture() throws Exception {
        var f = new JavaTickFixture();
        f.player.xRot = 0.0F;
        f.player.yRot = 0.0F;
        f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);
        f.addRemotePlayer(TARGET, new Vec3(0.5, FLOOR_Y, 2.5), GameMode.SURVIVAL);
        return f;
    }

    private static void groundTick(
            JavaTickFixture f, Vec3[] state, ServerboundPlayerInput keys, float slow, float speed) {
        move(f, state, keys, slow, speed);
        f.tickEnd();
    }

    /** A ground tick's PLAYER_INPUT and movement, without its tick end. */
    private static void move(JavaTickFixture f, Vec3[] state, ServerboundPlayerInput keys, float slow, float speed) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        f.input(keys);
        Vec3 delta = state[1].add(ClientMoveInput.of(f, keys, slow, speed, 0.0F));
        state[0] = state[0].add(delta.x, 0.0, delta.z);
        state[1] = new Vec3(delta.x * GROUND_FRICTION, 0.0, delta.z * GROUND_FRICTION);
        f.move(state[0], true);
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != before, "the move committed no prediction");
        assertTrue(exact(result), describe(result));
    }

    /** A two-block stone wall along z = 1. */
    private static void wall(JavaTickFixture f) {
        for (int x = -8; x <= 8; x++) {
            for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, y, 1), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
    }

    /** {@code block} over every column around the origin, one block above the floor. */
    private static void ceiling(JavaTickFixture f, int block) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 1, z), block);
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
    }

    private static PacketReceiveEvent<ServerboundPlayerCommand> command(JavaTickFixture f, PlayerCommandAction action) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        var event = f.event(ServerboundPackets.PLAYER_COMMAND, packet);
        new PacketEntityAction().onPlayerCommand(event, f.player, packet);
        CheckManagerListener.receiveGeneric(event, f.player);
        return event;
    }

    private static PacketReceiveEvent<ServerboundInteract> attack(JavaTickFixture f) {
        var packet = new ServerboundInteract(TARGET, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
        var event = f.event(ServerboundPackets.INTERACT, packet);
        f.player.checkManager.getCheck(PlayerReach.class).onInteract(event, f.player, packet);
        return event;
    }

    /** The server's shared flags for the player; the client answers their ping when {@code applied}. */
    private static void sharedFlags(JavaTickFixture f, byte flags, boolean applied) {
        var packet = new EntityMetadata(
                f.player.entityID,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.ENTITY_SHARED_FLAGS, flags, ByteBuffer.allocate(0))));
        var event = new PacketSendEvent<>(f.player.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        new PacketSelfMetadataListener().onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        if (applied) f.pongThrough(f.player.lastTransactionSent.get());
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }

    /** A RESPAWN into the same level with {@code kept}, answered through its pings. */
    private static void respawn(JavaTickFixture f, byte kept) {
        var spawnInfo = new PlayerSpawnInfo(
                ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:the_end"),
                f.player.compensatedWorld.getLastClientboundDimension().dimension(),
                GameMode.SURVIVAL);
        var packet = new ClientboundRespawn(spawnInfo, kept);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
        new PacketPlayerRespawn().onRespawn(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static ServerboundPlayerInput keys(boolean forward, boolean shift) {
        return new ServerboundPlayerInput(forward, false, false, false, false, shift, false);
    }
}
