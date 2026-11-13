package ac.cult.cultac.bedrock.replay.offline;

import static org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType.*;
import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockPositionTranslator;
import ac.cult.cultac.bedrock.prediction.integration.BedrockFrameProcessor;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.protocol.*;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

public class BedrockPendingTeleportTickTest {
    // Capture 1790127673259: launch 635, HANDLE_TELEPORT 640, next launch 645.
    @Test
    public void heldJumpKeepsItsTenTickCadenceAcrossDelayedTeleportReceipt() {
        runDelayedTeleport(false);
    }

    @Test
    public void releasingJumpDuringPendingTeleportResetsCooldown() {
        runDelayedTeleport(true);
    }

    @Test
    public void writtenJavaMarkerReceiptMakesChunkAndTeleportReadyForImmediateAuthBeforePong() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            Vec3 ground = new Vec3(1.5, 106, 1.5);
            player.x = player.lastX = ground.x;
            player.y = player.lastY = ground.y;
            player.z = player.lastZ = ground.z;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, ground.x, ground.y, ground.z);
            var teleports = player.getSetbackTeleportUtil();
            var ping = new InboundTransactionAcceptanceTest.DeferredJavaPing(player, () -> {
                assertNotNull(player.compensatedWorld.getChunk(0, 0));
                assertTrue(teleports.bedrockTeleports().mustAcknowledgeBedrockTransportTeleport());
                assertEquals(player.lastTransactionSent.get(), player.lastTransactionReceived.get());
            });
            int transaction = ping.transaction.transaction();
            var chunk = new ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk(
                    new ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection[16], transaction);
            player.compensatedWorld.addToCache(chunk, "minecraft:overworld", transaction, 0, 0);
            player.latencyUtils.addRealTimeTask(transaction, () -> {
                for (int x = 0; x < 4; x++)
                    for (int z = 0; z < 4; z++)
                        player.compensatedWorld.updateBlock(
                                x,
                                105,
                                z,
                                DataTables.defaults()
                                        .registry()
                                        .block("minecraft:stone")
                                        .defaultState());
            });
            teleports
                    .bedrockTeleports()
                    .addImmediateBedrockTransportTeleport(
                            ground, true, BedrockCoordinateFrame.IDENTITY, null, null, transaction);
            assertNull(player.compensatedWorld.getChunk(0, 0));
            assertEquals(0, player.lastTransactionReceived.get());
            ping.reply(ping.packet.getTimestamp());
            assertNull(player.compensatedWorld.getChunk(0, 0));
            assertTrue(ping.pongs.isEmpty());

            ping.write(
                    () -> assertTrue(ac.cult.cultac.events.packets.listeners.PacketPingListener.acceptBedrockResponse(
                            player, ping.transaction.id())));
            player.addBedrockTransactionTask(player.getLastClientboundBedrockTransaction(), () -> {
                player.bedrockState.applyAcknowledgedBoundingBoxMetadata(0.6F, 1.8F);
                player.checkManager
                        .getSimulationProcessor()
                        .applyAcknowledgedBedrockMetadata(0.6F, 1.8F, false, false, false);
            });
            ping.reply(Long.MIN_VALUE);
            assertSame(chunk, player.compensatedWorld.getChunk(0, 0));
            assertEquals(transaction, player.lastTransactionReceived.get());
            assertEquals(1, ping.pongs.size());
            // Keep the original Pong in transit. The next native auth must already see
            // acknowledged world/metadata and perform the actor tick which clears HasTeleported.
            assertNotNull(process(player, startupFrame(player, 1, ground, 0, true)));
            assertFalse(teleports.bedrockTeleports().mustAcknowledgeBedrockTransportTeleport());
            assertEquals(1, entry(player).state().simulationTick());
            assertFalse(entry(player).state().hasTeleported());
            assertNotNull(entry(player).state().acknowledgedPlayerDimensions());
            assertNotNull(process(player, startupFrame(player, 2, ground, -0.0784, false)));
            assertEquals(2, entry(player).state().simulationTick());
            assertFalse(entry(player).state().hasTeleported());
            assertEquals(-0.0784, entry(player).state().velocity().y(), 0.00001);
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
            long clock = player.getPlayerClockAtLeast();
            var late = RecordReceiveTestEvents.pong(
                    player, ping.pongs.removeFirst().getId());
            InboundTransactionAcceptanceTest.dispatchLocalBridgePong(player, late);
            assertFalse(late.isAcceptedTransactionResponse());
            assertEquals(clock, player.getPlayerClockAtLeast());
            assertEquals(2, entry(player).state().simulationTick());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    // A capture of a respawn into a column the server had forgotten, which the client holds empty:
    // the tick after the teleport reports no step and delta y -0.0784, the next steps -0.0784 with
    // -0.155232, the next -0.155232 with -0.230527. Nothing holds the actor and nothing stops it.
    @Test
    public void actorFallsThroughAnEmptyColumnItsChunkViewHolds() {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            Vec3 spawn = new Vec3(1.5, 64, 1.5);
            player.x = player.lastX = spawn.x;
            player.y = player.lastY = spawn.y;
            player.z = player.lastZ = spawn.z;
            player.onGround = player.lastOnGround = false;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, spawn.x, spawn.y, spawn.z);
            assertNull(player.compensatedWorld.getChunk(0, 0));
            player.bedrockState.clientChunks.publish(3, -2, 256);
            assertTrue(player.bedrockState.clientChunks.receive(0, 0, 600));

            double[] steps = {0, -0.0784, -0.155232};
            double[] velocities = {-0.0784, -0.155232, -0.230527};
            Vec3 position = spawn;
            for (int tick = 0; tick < steps.length; tick++) {
                position = position.add(0, steps[tick], 0);
                assertNotNull(process(player, frame(player, 700 + tick, position, velocities[tick], false, false)));
                assertEquals(tick + 1, entry(player).state().simulationTick());
                assertEquals(
                        position.y, entry(player).state().physicalFeetPosition().y(), 1.0E-5);
                assertEquals(velocities[tick], entry(player).state().velocity().y(), 1.0E-5);
            }
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    // A capture of a hop out of the Nether through Geyser: the client starts the dimension change's loading screen
    // before auth 25586 and reports the change's position at rest through the screen. After the screen ends it falls
    // from rest there (deltas -0.0784, -0.155232, -0.230527) until it takes the respawn teleport on auth 25596.
    @Test
    public void aDimensionChangePlacesTheActorWhileItsRespawnTeleportIsPending() {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            for (int x = 0; x < 4; x++)
                for (int z = 0; z < 4; z++)
                    player.compensatedWorld.updateBlock(
                            x,
                            105,
                            z,
                            DataTables.defaults()
                                    .registry()
                                    .block("minecraft:netherrack")
                                    .defaultState());
            Vec3 pad = new Vec3(1.5, 106, 1.5);
            player.x = player.lastX = pad.x;
            player.y = player.lastY = pad.y;
            player.z = player.lastZ = pad.z;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, pad.x, pad.y, pad.z);
            assertNotNull(process(player, startupFrame(player, 25585, pad, -0.0784, false)));

            Vec3 station = new Vec3(18.5, 120, 26.5);
            var teleports = player.getSetbackTeleportUtil();
            teleports
                    .bedrockTeleports()
                    .addImmediateBedrockTransportTeleport(
                            station,
                            true,
                            BedrockCoordinateFrame.IDENTITY,
                            null,
                            new BedrockTeleportOperation(12, BedrockTeleportProvenance.GEYSER, 12),
                            12);
            player.lastTransactionReceived.set(100);
            assertTrue(teleports.bedrockTeleports().mustAcknowledgeBedrockTransportTeleport());

            Vec3 spawn = new Vec3(0, 32767, 0);
            var loading = player.bedrockState.loadingScreen;
            loading.changeDimension(null, spawn);
            assertTrue(loading.accept(START_LOADING_SCREEN, null));
            player.checkManager.getSimulationProcessor().applyBedrockDimensionChange(loading.placement());
            assertEquals(spawn, new Vec3(player.x, player.y, player.z));
            // The client reports float eye positions; the bridge subtracts the double eye offset.
            Vec3 reported = feet(32768.62F);
            for (long tick = 25586; tick <= 25592; tick++) {
                assertNull(process(player, startupFrame(player, tick, reported, 0, false)));
                assertEquals(reported.y, entry(player).state().physicalFeetPosition().y(), 1.0E-9);
                assertEquals(0, entry(player).state().velocity().y(), 0);
            }

            assertTrue(loading.accept(END_LOADING_SCREEN, null));
            float[] eyes = {32768.62F, 32768.543F, 32768.39F};
            double[] velocities = {-0.0784, -0.155232, -0.230527};
            for (int i = 0; i < velocities.length; i++) {
                assertNull(process(player, frame(player, 25593 + i, feet(eyes[i]), velocities[i], false, false)));
                assertEquals(
                        feet(eyes[i]).y, entry(player).state().physicalFeetPosition().y(), 1.0E-9);
                assertEquals(velocities[i], entry(player).state().velocity().y(), 1.0E-5);
                assertEquals(
                        0,
                        player.checkManager.getSimulationProcessor().getLastPrediction().getOffset(),
                        1.0E-5);
            }

            assertNotNull(process(player, frame(player, 25596, station, 0, false, true)));
            assertFalse(teleports.bedrockTeleports().mustAcknowledgeBedrockTransportTeleport());
            assertEquals(station.y, entry(player).state().physicalFeetPosition().y(), 0);
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void acceptingAFarTeleportOpensTheTimerWindowOfALongTeleport() throws Exception {
        assertTrue(timerWindowOpenedAt(acceptTeleportOver(100)));
    }

    @Test
    public void acceptingANearTeleportLeavesTheTimerWindowClosed() throws Exception {
        assertFalse(timerWindowOpenedAt(acceptTeleportOver(8)));
    }

    /** Whether the timer's long-teleport window was opened at or after the given time. */
    private static boolean timerWindowOpenedAt(double[] timer) {
        return timer[1] >= timer[0];
    }

    /** Returns the time just before the accepting frame and the timer's last long teleport after it. */
    private static double[] acceptTeleportOver(double distance) throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            Vec3 ground = new Vec3(1.5, 106, 1.5);
            player.x = player.lastX = ground.x;
            player.y = player.lastY = ground.y;
            player.z = player.lastZ = ground.z;
            player.onGround = player.lastOnGround = false;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, ground.x, ground.y, ground.z);
            assertNotNull(process(player, frame(player, 700, ground, -0.0784, false, false)));

            // The first teleport a client accepts is its spawn teleport, which opens the window at any distance.
            acceptTeleport(player, ground, 701, 10);
            Vec3 target = ground.add(distance, 0, 0);
            var timer = player.checkManager.getListener(ac.cult.cultac.checks.impl.movement.timer.DumbTimer.class);
            var lastLongTeleport = timer.getClass().getDeclaredField("lastLongTeleport");
            lastLongTeleport.setAccessible(true);
            double before = System.nanoTime();
            acceptTeleport(player, target, 702, 11);
            return new double[] {before, lastLongTeleport.getDouble(timer)};
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static void acceptTeleport(CultPlayer player, Vec3 target, long tick, int transaction) {
        var teleports = player.getSetbackTeleportUtil();
        teleports.javaTeleports().addSentTeleport(target, transaction, new RelativeFlag(0), false, transaction);
        teleports.pendingTeleports.removeIf(pending -> pending.getBedrockTransportRevision() < 0);
        teleports
                .bedrockTeleports()
                .addImmediateBedrockTransportTeleport(
                        target,
                        false,
                        BedrockCoordinateFrame.IDENTITY,
                        null,
                        new BedrockTeleportOperation(transaction, BedrockTeleportProvenance.GEYSER, transaction),
                        transaction);
        player.lastTransactionReceived.set(100);
        assertTrue(teleports.bedrockTeleports().mustAcknowledgeBedrockTransportTeleport());
        process(player, frame(player, tick, target, 0, false, true));
        assertFalse(teleports.bedrockTeleports().mustAcknowledgeBedrockTransportTeleport());
    }

    private static Vec3 feet(float eyeY) {
        return new Vec3(0, eyeY - BedrockPositionTranslator.PLAYER_PACKET_Y_OFFSET, 0);
    }

    // Captures of teleports into columns the client's chunk view lacked: the client takes the teleport, then reports
    // every tick no step and its velocity unchanged until the tick after the one that processed the column, which
    // falls from rest (no step, delta y -0.0784) as a normal tick does.
    @Test
    public void actorHoldsInAColumnItsChunkViewHasNotReceived() {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            Vec3 start = new Vec3(1.5, 80, 1.5);
            player.x = player.lastX = start.x;
            player.y = player.lastY = start.y;
            player.z = player.lastZ = start.z;
            player.onGround = player.lastOnGround = false;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, start.x, start.y, start.z);
            var chunks = player.bedrockState.clientChunks;
            chunks.receive(0, 0, 600);
            // A publisher update far away drops the column; one around it again does not bring it back.
            chunks.publish(1000, 1000, 256);
            chunks.publish(0, 0, 80);
            Vec3 spawn = new Vec3(1.5, 64, 1.5);
            var teleports = player.getSetbackTeleportUtil();
            teleports
                    .bedrockTeleports()
                    .addImmediateBedrockTransportTeleport(
                            spawn,
                            true,
                            BedrockCoordinateFrame.IDENTITY,
                            null,
                            new BedrockTeleportOperation(12, BedrockTeleportProvenance.GEYSER, 12),
                            12);
            player.lastTransactionReceived.set(100);

            for (long tick = 700; tick <= 702; tick++) {
                if (tick == 701) assertTrue(chunks.receive(0, 0, 701));
                assertNotNull(process(player, frame(player, tick, spawn, 0, false, tick == 700)));
                assertEquals(spawn.y, entry(player).state().physicalFeetPosition().y(), 0);
                assertEquals(0, entry(player).state().velocity().y(), 0);
            }
            double[] steps = {0, -0.0784};
            double[] velocities = {-0.0784, -0.155232};
            Vec3 position = spawn;
            for (int i = 0; i < steps.length; i++) {
                position = position.add(0, steps[i], 0);
                assertNotNull(process(player, frame(player, 703 + i, position, velocities[i], false, false)));
                assertEquals(position.y, entry(player).state().physicalFeetPosition().y(), 1.0E-5);
                assertEquals(velocities[i], entry(player).state().velocity().y(), 1.0E-5);
            }
            assertFalse(teleports.bedrockTeleports().mustAcknowledgeBedrockTransportTeleport());
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    // stuck-21 (1.26.40.5): a teleport at 1676 into a column whose marker the client answered after 1675. The
    // client commits the column on a worker thread: it held the actor at 1677, two ticks after the answer, and let it
    // fall from 1678. A column commits one to three ticks after that answer; until it moves, either may happen.
    @Test
    public void anActorHeldUntilTheThirdTickAfterItsColumnArrivedIsNotFlagged() {
        assertEquals(0, heldThroughTheCommitWindow(1), 0);
    }

    // The client never kept a column uncommitted three ticks after answering its marker: a hold there flags.
    @Test
    public void anActorHeldPastTheCommitWindowIsFlagged() {
        assertTrue(heldThroughTheCommitWindow(2) > 0);
    }

    private static double heldThroughTheCommitWindow(int heldTicks) {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            Vec3 start = new Vec3(1.5, 80, 1.5);
            player.x = player.lastX = start.x;
            player.y = player.lastY = start.y;
            player.z = player.lastZ = start.z;
            player.onGround = player.lastOnGround = false;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, start.x, start.y, start.z);
            var chunks = player.bedrockState.clientChunks;
            chunks.receive(0, 0, 600);
            chunks.publish(1000, 1000, 256);
            chunks.publish(0, 0, 80);
            Vec3 spawn = new Vec3(1.5, 64, 1.5);
            var teleports = player.getSetbackTeleportUtil();
            teleports
                    .bedrockTeleports()
                    .addImmediateBedrockTransportTeleport(
                            spawn,
                            true,
                            BedrockCoordinateFrame.IDENTITY,
                            null,
                            new BedrockTeleportOperation(12, BedrockTeleportProvenance.GEYSER, 12),
                            12);
            player.lastTransactionReceived.set(100);
            assertTrue(chunks.receive(0, 0, 1675));
            long tick = 1676;
            assertNotNull(process(player, frame(player, tick++, spawn, 0, false, true)));
            for (int i = 0; i < heldTicks; i++) assertNotNull(process(player, frame(player, tick++, spawn, 0, false, false)));
            Vec3 position = spawn;
            double[] steps = {0, -0.0784};
            double[] velocities = {-0.0784, -0.155232};
            for (int i = 0; i < steps.length; i++) {
                position = position.add(0, steps[i], 0);
                assertNotNull(process(player, frame(player, tick++, position, velocities[i], false, false)));
            }
            return player.checkManager.getListener(BedrockMovement.class).violations;
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    // ll40-hold1-1 (1.26.40.5): teleported onto the ground at 943 into a column it had not committed, the client held
    // the actor at 944 and still reported the vertical collision of its last move; it fell from rest at 945. A hold
    // that dropped that collision disagreed with the client's report and sent a correction.
    @Test
    public void aHeldTickKeepsTheCollisionOfTheLastMove() {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            for (int x = 0; x < 8; x++)
                for (int z = 0; z < 8; z++)
                    player.compensatedWorld.updateBlock(
                            x, 63, z, DataTables.defaults().registry().block("minecraft:stone").defaultState());
            Vec3 start = new Vec3(1.5, 80, 1.5);
            player.x = player.lastX = start.x;
            player.y = player.lastY = start.y;
            player.z = player.lastZ = start.z;
            player.onGround = player.lastOnGround = false;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, start.x, start.y, start.z);
            var chunks = player.bedrockState.clientChunks;
            chunks.receive(0, 0, 600);
            chunks.publish(1000, 1000, 256);
            chunks.publish(0, 0, 80);
            Vec3 spawn = new Vec3(2.5, 64, 2.5);
            var teleports = player.getSetbackTeleportUtil();
            teleports.bedrockTeleports().addImmediateBedrockTransportTeleport(
                    spawn, true, BedrockCoordinateFrame.IDENTITY, null,
                    new BedrockTeleportOperation(12, BedrockTeleportProvenance.GEYSER, 12), 12);
            player.lastTransactionReceived.set(100);
            assertTrue(chunks.receive(0, 0, 942));
            assertNotNull(process(player, walk(player, 943, spawn, Vec3.ZERO, true, false, true)));
            assertNotNull(process(player, walk(player, 944, spawn, Vec3.ZERO, true, false, false)));
            var held = entry(player).state();
            assertEquals(spawn.y, held.physicalFeetPosition().y(), 0);
            assertTrue(held.collisionFlags().verticalCollision());
            assertFalse(held.collisionFlags().onGround());
            assertNotNull(process(player, walk(player, 945, spawn, new Vec3(0, -0.0784, 0), false, false, false)));
            assertNotNull(process(player, walk(player, 946, spawn, new Vec3(0, -0.0784, 0), true, false, false)));
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void walkingActorHeldInAColumnLeavesTheHoldAirborne() {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            for (int x = 0; x < 8; x++)
                for (int z = 0; z < 8; z++)
                    player.compensatedWorld.updateBlock(
                            x, 63, z, DataTables.defaults().registry().block("minecraft:stone").defaultState());
            Vec3 start = new Vec3(1.5, 64, 1.5);
            player.x = player.lastX = start.x;
            player.y = player.lastY = start.y;
            player.z = player.lastZ = start.z;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, start.x, start.y, start.z);
            var chunks = player.bedrockState.clientChunks;
            chunks.receive(0, 0, 600);
            assertNotNull(process(player, walk(player, 690, start, new Vec3(0, -0.0784, 0), true, false, false)));
            chunks.publish(1000, 1000, 256);
            chunks.publish(0, 0, 80);
            Vec3 spawn = new Vec3(2.5, 64, 2.5);
            var teleports = player.getSetbackTeleportUtil();
            teleports.bedrockTeleports().addImmediateBedrockTransportTeleport(
                    spawn, true, BedrockCoordinateFrame.IDENTITY, null,
                    new BedrockTeleportOperation(12, BedrockTeleportProvenance.GEYSER, 12), 12);
            player.lastTransactionReceived.set(100);
            for (long tick = 700; tick <= 702; tick++) {
                if (tick == 701) assertTrue(chunks.receive(0, 0, 701));
                assertNotNull(process(player, walk(player, tick, spawn, Vec3.ZERO, true, true, tick == 700)));
            }
            Vec3 p1 = spawn.add(0, 0, 0.0196);
            assertNotNull(process(player, walk(player, 703, p1, new Vec3(0, -0.0784, 0.017836), false, true, false)));
            assertFalse(entry(player).state().collisionFlags().onGround());
            Vec3 p2 = p1.add(0, 0, 0.037436);
            assertNotNull(process(player, walk(player, 704, p2, new Vec3(0, -0.0784, 0.034067), true, true, false)));
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static BedrockAuthInputFrame walk(
            CultPlayer player, long tick, Vec3 position, Vec3 velocity, boolean collided, boolean forward,
            boolean teleport) {
        long flags = teleport ? 1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal() : 0;
        if (collided) flags |= 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal();
        if (forward) flags |= 1L << PlayerAuthInputData.UP.ordinal();
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .position(position)
                .rotation(0, 0, 0)
                .moveVector(0, forward ? 1 : 0)
                .reportedEndOfTickVelocity(velocity)
                .rawInputFlags(flags)
                .build();
    }

    @Test
    public void jumpCooldownSkipsTheTicksTheChunkViewHolds() {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            for (int x = 0; x < 4; x++)
                for (int z = 0; z < 4; z++)
                    player.compensatedWorld.updateBlock(
                            x, 105, z, DataTables.defaults().registry().block("minecraft:stone").defaultState());
            Vec3 ground = new Vec3(1.5, 106, 1.5);
            player.x = player.lastX = ground.x;
            player.y = player.lastY = ground.y;
            player.z = player.lastZ = ground.z;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, ground.x, ground.y, ground.z);
            var chunks = player.bedrockState.clientChunks;
            chunks.receive(0, 0, 600);
            assertNotNull(process(player, walk(player, 634, ground, new Vec3(0, -0.0784, 0), true, false, false)));
            assertNotNull(process(player, jump(player, 635, ground.add(0, 0.4199981689453125, 0), 0.33319998, false, false)));
            assertEquals(10, entry(player).mobJumpComponent().jumpCooldownTicks());
            chunks.publish(1000, 1000, 256);
            chunks.publish(0, 0, 80);
            var teleports = player.getSetbackTeleportUtil();
            teleports.bedrockTeleports().addImmediateBedrockTransportTeleport(
                    ground, true, BedrockCoordinateFrame.IDENTITY, null,
                    new BedrockTeleportOperation(12, BedrockTeleportProvenance.GEYSER, 12), 12);
            player.lastTransactionReceived.set(100);
            assertNotNull(process(player, jump(player, 636, ground, 0, true, true)));
            for (long tick = 637; tick <= 639; tick++) {
                if (tick == 638) assertTrue(chunks.receive(0, 0, 638));
                assertNotNull(process(player, jump(player, tick, ground, 0, true, false)));
            }
            for (long tick = 640; tick <= 647; tick++) {
                assertNotNull(process(player, jump(player, tick, ground, -0.0784, tick > 640, false)));
            }
            assertNotNull(process(player, jump(player, 648, ground.add(0, 0.4199981689453125, 0), 0.33319998, false, false)));
            assertEquals(10, entry(player).mobJumpComponent().jumpCooldownTicks());
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static BedrockAuthInputFrame jump(
            CultPlayer player, long tick, Vec3 position, double velocity, boolean collided, boolean teleport) {
        long flags = teleport ? 1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal() : 0;
        if (collided) flags |= 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal();
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .position(position)
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .jumping(true)
                .jumpCurrentRaw(true)
                .reportedEndOfTickVelocity(new Vec3(0, velocity, 0))
                .rawInputFlags(flags)
                .build();
    }

    private static BedrockAuthInputFrame startupFrame(
            CultPlayer player, long tick, Vec3 position, double futureVelocity, boolean handlesTeleport) {
        long flags = 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal();
        if (handlesTeleport) flags |= 1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal();
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .position(position)
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .reportedEndOfTickVelocity(new Vec3(0, futureVelocity, 0))
                .rawInputFlags(flags)
                .build();
    }

    private static void runDelayedTeleport(boolean releaseJump) {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            for (int x = 0; x < 4; x++)
                for (int z = 0; z < 4; z++)
                    player.compensatedWorld.updateBlock(
                            x,
                            105,
                            z,
                            DataTables.defaults()
                                    .registry()
                                    .block("minecraft:stone")
                                    .defaultState());
            Vec3 ground = new Vec3(1.5, 106, 1.5);
            player.x = player.lastX = ground.x;
            player.y = player.lastY = ground.y;
            player.z = player.lastZ = ground.z;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, ground.x, ground.y, ground.z);
            assertNotNull(process(player, frame(player, 634, ground, -0.0784, false, false)));
            var beforeLaunch = player.checkManager.getSimulationProcessor().getCurrentPredictionCommit();
            assertNotNull(
                    process(player, frame(player, 635, ground.add(0, 0.4199981689453125, 0), 0.33319998, true, false)));
            assertEquals(10, entry(player).mobJumpComponent().jumpCooldownTicks());
            var launchCommit = player.checkManager.getSimulationProcessor().getCurrentPredictionCommit();
            long simulationTick = entry(player).state().simulationTick();
            var teleports = player.getSetbackTeleportUtil();
            teleports.javaTeleports().addSentTeleport(ground, 10, new RelativeFlag(0), false, 1);
            teleports.pendingTeleports.removeIf(pending -> pending.getBedrockTransportRevision() < 0);
            var operation = new BedrockTeleportOperation(1, BedrockTeleportProvenance.CULT_SETBACK, 10);
            teleports
                    .bedrockTeleports()
                    .addImmediateBedrockTransportTeleport(
                            ground, true, BedrockCoordinateFrame.IDENTITY, null, operation, 10);
            player.lastTransactionReceived.set(100);
            assertTrue(teleports.bedrockTeleports().mustAcknowledgeBedrockTransportTeleport());
            // Exercise a teleport becoming pending after prediction but before commit.
            // The launch's complete actor state must survive, not just its timers.
            commitWithPendingTeleport(player, beforeLaunch, launchCommit);
            assertEquals(
                    launchCommit, player.checkManager.getSimulationProcessor().getCurrentPredictionCommit());
            assertEquals(10, entry(player).mobJumpComponent().jumpCooldownTicks());
            assertEquals(
                    106.41999816894531,
                    entry(player).state().physicalFeetPosition().y(),
                    0.001);
            var required = teleports.getRequiredSetBack();
            double acceptedY = player.y;
            for (int tick = 636; tick < 640; tick++) {
                // A forged position must not be adopted or complete the pending setback.
                boolean held = !releaseJump || tick < 637;
                int cooldown = held ? 10 - (tick - 635) : 0;
                var input = frame(player, tick, new Vec3(999, 999, 999), 0, held, false);
                assertNull(process(player, input));
                assertEquals(cooldown, entry(player).mobJumpComponent().jumpCooldownTicks());
                assertEquals(simulationTick + tick - 635, entry(player).state().simulationTick());
                assertEquals(acceptedY, player.y, 0);
                assertTrue(entry(player).state().physicalFeetPosition().y() < 108);
                assertSame(required, teleports.getRequiredSetBack());
                assertTrue(teleports.bedrockTeleports().mustAcknowledgeBedrockTransportTeleport());
                assertNull(process(player, input)); // duplicate ticks never advance twice
                assertEquals(cooldown, entry(player).mobJumpComponent().jumpCooldownTicks());
            }
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
            if (releaseJump) return;
            assertNotNull(process(player, frame(player, 640, ground, 0, true, true)));
            assertFalse(teleports.bedrockTeleports().mustAcknowledgeBedrockTransportTeleport());
            assertEquals(5, entry(player).mobJumpComponent().jumpCooldownTicks());
            for (int tick = 641; tick < 645; tick++) {
                assertNotNull(process(player, frame(player, tick, ground, -0.0784, true, false)));
                assertEquals(106, entry(player).state().physicalFeetPosition().y(), 0.001);
            }
            assertNotNull(
                    process(player, frame(player, 645, ground.add(0, 0.4199981689453125, 0), 0.33319998, true, false)));
            assertEquals(
                    106.41999816894531,
                    entry(player).state().physicalFeetPosition().y(),
                    0.001);
            assertEquals(10, entry(player).mobJumpComponent().jumpCooldownTicks());
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static void commitWithPendingTeleport(
            CultPlayer player,
            ac.cult.cultac.checks.impl.prediction.PredictionCommit before,
            ac.cult.cultac.checks.impl.prediction.PredictionCommit completed) {
        var processor = player.checkManager.getSimulationProcessor();
        try {
            var carry = processor.getClass().getDeclaredField("profileCarry");
            carry.setAccessible(true);
            carry.set(processor, before.carry());
            var commit = processor
                    .getClass()
                    .getDeclaredMethod(
                            "commitPredictionState",
                            ac.cult.cultac.checks.impl.prediction.PredictionResult.class,
                            Vec3.class,
                            Vec3.class,
                            float.class,
                            float.class,
                            ac.cult.cultac.checks.impl.prediction.profile.MovementProfile.class,
                            ac.cult.cultac.checks.impl.prediction.PredictionCommit.class);
            commit.setAccessible(true);
            commit.invoke(
                    processor,
                    processor.getLastPrediction(),
                    Vec3.ZERO,
                    Vec3.ZERO,
                    0F,
                    0F,
                    ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles.forPlayer(player),
                    completed);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static BedrockProfileState.Entry entry(CultPlayer player) {
        return BedrockProfileState.profileEntries(player.checkManager
                        .getSimulationProcessor()
                        .getCurrentPredictionCommit()
                        .carry())
                .getFirst();
    }

    private static Object process(CultPlayer player, BedrockAuthInputFrame frame) {
        return BedrockFrameProcessor.process(player, frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
    }

    private static BedrockAuthInputFrame frame(
            CultPlayer player, long tick, Vec3 position, double velocity, boolean jumping, boolean teleport) {
        long flags = teleport ? 1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal() : 0;
        if (tick == 634 || tick >= 642 && tick <= 644) flags |= 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal();
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .position(position)
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .jumping(jumping)
                .jumpCurrentRaw(jumping)
                .reportedEndOfTickVelocity(new Vec3(0, velocity, 0))
                .rawInputFlags(flags)
                .build();
    }
}
