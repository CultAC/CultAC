package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.integration.BedrockFrameProcessor;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.protocol.*;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
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
                assertTrue(teleports.mustAcknowledgeBedrockTransportTeleport());
                assertEquals(player.lastTransactionSent.get(), player.lastTransactionReceived.get());
            });
            int transaction = ping.transaction.transaction();
            var chunk = new ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk(
                    new ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection[16], transaction);
            player.compensatedWorld.addToCache(chunk, "minecraft:overworld", transaction, 0, 0);
            player.latencyUtils.addRealTimeTask(transaction, () -> {
                for (int x = 0; x < 4; x++)
                    for (int z = 0; z < 4; z++)
                        player.compensatedWorld.updateBlock(x, 105, z, Blocks.STONE.defaultBlockState());
            });
            teleports.addImmediateBedrockTransportTeleport(
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
            assertFalse(teleports.mustAcknowledgeBedrockTransportTeleport());
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
                    player.compensatedWorld.updateBlock(x, 105, z, Blocks.STONE.defaultBlockState());
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
            teleports.addSentTeleport(ground, 10, new RelativeFlag(0), false, 1);
            teleports.pendingTeleports.removeIf(pending -> pending.getBedrockTransportRevision() < 0);
            var operation = new BedrockTeleportOperation(1, BedrockTeleportProvenance.CULT_SETBACK, 10);
            teleports.addImmediateBedrockTransportTeleport(
                    ground, true, BedrockCoordinateFrame.IDENTITY, null, operation, 10);
            player.lastTransactionReceived.set(100);
            assertTrue(teleports.mustAcknowledgeBedrockTransportTeleport());
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
                assertTrue(teleports.mustAcknowledgeBedrockTransportTeleport());
                assertNull(process(player, input)); // duplicate ticks never advance twice
                assertEquals(cooldown, entry(player).mobJumpComponent().jumpCooldownTicks());
            }
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
            if (releaseJump) return;
            assertNotNull(process(player, frame(player, 640, ground, 0, true, true)));
            assertFalse(teleports.mustAcknowledgeBedrockTransportTeleport());
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
