package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.bedrock.prediction.integration.BedrockNextTickStates;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.events.packets.listeners.BedrockAuthInputPluginMessageListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.event.PacketSendEvent;
import com.google.gson.JsonParser;
import java.lang.reflect.Method;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Mojang's PlayerAuthInputPacketPayload describes Position and Pos Delta as
 * end-of-tick position and velocity, and Input Data as per-tick flags:
 * https://github.com/Mojang/bedrock-protocol-docs/blob/main/json/PlayerAuthInputPacketPayload.json
 * Unlike MCP-Reborn ClientPacketListener.handleMovePlayer's immediate Java
 * PosRot echo, HandledTeleport is part of the Bedrock simulation tick.
 * Capture 1788992848677-2e828f41-19f5-4f7e-be92-d6b0b5660959, ticks 1823/1824,
 * reports a teleport at packet Y=64.174156 and then Y=64.169151, with future
 * velocities approximately -0.005 and -0.009. The first comes from the server
 * SetEntityMotion immediately after teleport, not gravity during the teleport tick.
 */
public final class BedrockTeleportTickTest {
    private static final Vec3 TARGET = new Vec3(0.5D, 62.55414581298828D, 0.5D);

    @Test
    public void acceptedTeleportCarriesOrderedServerMotionWithoutWaterTravel() throws Exception {
        CultPlayer player = waterPlayer();
        try {
            queueTeleport(player, TARGET);
            Vec3 motion = new Vec3(0, (double) -0.0050051883F, 0);
            observeMotion(player, motion).run();
            Vec3 velocity = process(player, frame(player, 1823L, TARGET, true));
            assertNotNull("accepted teleport tick must publish calculated velocity", velocity);
            assertEquals(-0.005D, velocity.y, 0.00001D);
            assertFalse(player.getSetbackTeleportUtil().hasPendingBedrockTransportTeleport());
            assertTrue(player.packetStateData.hasPendingBedrockTranslatedMovementDecision());
            assertFalse(player.packetStateData.hasPendingRejectedBedrockTranslatedMovement());
            assertNotNull(player.checkManager.getSimulationProcessor().getLastPrediction());
            long tick = state(player).simulationTick();
            assertEquals(-0.005D, state(player).velocity().y(), 0.00001D);

            player.packetStateData.consumeBedrockTranslatedMovementPermit();
            velocity = process(player, frame(player, 1824L,
                    TARGET.add(0.0D, -0.0050048828125D, 0.0D), false));
            assertNotNull(velocity);
            assertEquals(-0.009D, velocity.y, 0.00001D);
            assertEquals(tick + 1L, state(player).simulationTick());
            assertFalse(player.getSetbackTeleportUtil().isPendingSetback());
            assertTrue(player.checkManager.getSimulationProcessor().getLastPrediction().getOffset() <= 0.001D);

            // Coalescing repeated identical boundaries must still simulate just one tick.
            queueTeleport(player, TARGET);
            queueTeleport(player, TARGET);
            assertNotNull(process(player, frame(player, 1825L, TARGET, true)));
            assertFalse(player.getSetbackTeleportUtil().hasPendingBedrockTransportTeleport());
            assertEquals(tick + 2L, state(player).simulationTick());
            assertEquals(0.0D, state(player).velocity().y(), 0.0D);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void javaVelocityProofCannotOverwriteObservedBedrockMotion() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.getSetbackTeleportUtil().hasFullyLoaded = true;
            player.getSetbackTeleportUtil().hasFullyJoined = true;
            Vec3 target = new Vec3(-55.56788635253906, 62.68053436279297, -60.12018585205078);
            Vec3 motion = new Vec3(0, (double) -0.0050051883F, 0);
            queueTeleport(player, target);
            assertNotNull(process(player, frame(player, 608, target, true)));
            for (long tick = 610; tick <= 616; tick += 2) {
                // Java sends the motion before Geyser observes its Bedrock translation.
                var packet = new ClientboundSetEntityMotionPacket(player.entityID, motion);
                var event = new PacketSendEvent(
                    player.user, packet, ConnectionProtocol.PLAY);
                player.packetEntityReplication.onSetEntityMotion(event, player, packet);
                queueTeleport(player, target);
                queueTeleport(player, target);
                var knockback = player.checkManager.getKnockbackHandler();
                assertNull(knockback.firstBread);
                assertNull(knockback.secondBread);
                Runnable receipt = observeMotion(player, motion);
                receipt.run();
                assertEquals(motion, process(player, frame(player, tick, target, true)));
                assertEquals(motion.y, state(player).velocity().y(), 0.000001);
                Vec3 carried = process(player, frame(player, tick + 1,
                    target.add(0, -0.0050048828125, 0), false));
                assertNotNull(carried);
                // Capture tick 611: ordinary air travel resumes after the teleport.
                assertEquals(-0.08330508, carried.y, 0.000001);
                assertTrue(player.checkManager.getSimulationProcessor().getLastPrediction().getOffset() <= 0.001);
                assertFalse(player.getSetbackTeleportUtil().isPendingSetback());
            }
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void prematureIgnoredAndWrongDestinationFramesRemainBlocked() throws Exception {
        CultPlayer player = waterPlayer();
        try {
            int transaction = player.lastTransactionSent.incrementAndGet();
            player.getSetbackTeleportUtil().addImmediateBedrockTransportTeleport(TARGET, false);
            process(player, frame(player, 10L, TARGET, true));
            assertBlockedWithoutPrediction(player);
            player.lastTransactionReceived.set(transaction);
            process(player, frame(player, 11L, TARGET, false));
            assertBlockedWithoutPrediction(player);
            process(player, frame(player, 12L, TARGET.add(1.0D, 0.0D, 0.0D), true));
            assertBlockedWithoutPrediction(player);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void acceptedFrameStillRunsWithALaterTeleportPending() throws Exception {
        CultPlayer player = waterPlayer();
        try {
            queueTeleport(player, TARGET);
            player.lastTransactionSent.incrementAndGet();
            player.getSetbackTeleportUtil().addImmediateBedrockTransportTeleport(TARGET.add(1.0D, 0.0D, 0.0D), false);
            assertNotNull(process(player, frame(player, 10L, TARGET, true)));
            assertTrue(player.getSetbackTeleportUtil().hasPendingBedrockTransportTeleport());
            assertEquals(0.0D, state(player).velocity().y(), 0.0D);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void inventedTeleportFlagDoesNotExemptIllegalMovement() throws Exception {
        assertIllegalMovementAfterTeleport(true);
    }

    @Test
    public void illegalMovementAfterTeleportRemainsRejected() throws Exception {
        assertIllegalMovementAfterTeleport(false);
    }

    @Test
    public void teleportOverridesEarlierMotionButKeepsLaterMotionInSameTransaction() throws Exception {
        CultPlayer player = waterPlayer();
        try {
            var knockback = player.checkManager.getKnockbackHandler();
            Vec3 oldMotion = new Vec3(0.1, 0.2, 0.3);
            observeMotion(player, oldMotion).run();
            queueTeleport(player, TARGET);
            assertEquals(Vec3.ZERO, process(player, frame(player, 10, TARGET, true)));

            queueTeleport(player, TARGET);
            Vec3 newMotion = new Vec3(0.125, (double) -0.005F, -0.25);
            observeMotion(player, newMotion).run();
            assertEquals(newMotion, process(player, frame(player, 11, TARGET, true)));
            assertEquals(TARGET, new Vec3(player.x, player.y, player.z));

            // A final teleport supersedes the intervening velocity too.
            queueTeleport(player, TARGET);
            observeMotion(player, newMotion).run();
            queueTeleport(player, TARGET);
            assertEquals(Vec3.ZERO, process(player, frame(player, 12, TARGET, true)));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void earlierVelocityCallbackCannotConfirmDifferentLaterMotion() throws Exception {
        CultPlayer player = waterPlayer();
        try {
            var knockback = player.checkManager.getKnockbackHandler();
            queueTeleport(player, TARGET);
            long revision = player.getSetbackTeleportUtil().getBedrockTeleportRevision();
            Vec3 first = new Vec3(0, -0.005, 0);
            Vec3 second = new Vec3(0, 0.2, 0);
            Runnable receiptA = observeMotion(player, first);
            var entryA = knockback.firstBread;
            Runnable receiptB = observeMotion(player, second);
            assertSame(entryA, knockback.firstBread);
            receiptA.run();
            var entryB = knockback.firstBread;
            assertSame(entryA, knockback.secondBread);
            assertNotSame(entryA, entryB);
            receiptB.run();
            assertEquals(second, knockback.secondBread.getVel());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void unconfirmedMotionAfterLaterTeleportStaysInFirstBread() throws Exception {
        CultPlayer player = waterPlayer();
        try {
            queueTeleport(player, TARGET);
            Vec3 later = TARGET.add(1, 0, 0);
            queueTeleport(player, later);
            Vec3 motion = new Vec3(0, (double) -0.02F, 0);
            var knockback = player.checkManager.getKnockbackHandler();
            Runnable receipt = observeMotion(player, motion);
            // A's frame precedes receipt of B and its motion marker on the client.
            assertEquals(Vec3.ZERO, process(player, frame(player, 10, TARGET, true)));
            assertNotNull(knockback.firstBread);
            assertNull(knockback.secondBread);
            assertTrue(player.getSetbackTeleportUtil().hasPendingBedrockTransportTeleport());
            receipt.run();
            assertEquals(motion, process(player, frame(player, 11, later, true)));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void confirmedMotionForADoesNotConsumeUnconfirmedMotionForBInSameTransaction() throws Exception {
        CultPlayer player = waterPlayer();
        try {
            var knockback = player.checkManager.getKnockbackHandler();
            queueTeleport(player, TARGET);
            Vec3 motionA = new Vec3(0, (double) -0.01F, 0);
            observeMotion(player, motionA).run();
            Vec3 later = TARGET.add(1, 0, 0);
            queueTeleport(player, later);
            Vec3 motionB = new Vec3(0, (double) -0.02F, 0);
            Runnable receiptB = observeMotion(player, motionB);
            var pendingB = knockback.firstBread;
            assertEquals(knockback.secondBread.getTransaction(), pendingB.getTransaction());
            assertEquals(motionA, process(player, frame(player, 10, TARGET, true)));
            assertSame(pendingB, knockback.firstBread);
            assertNull(knockback.secondBread);
            receiptB.run();
            assertEquals(motionB, process(player, frame(player, 11, later, true)));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void glideCaptureTeleportsStayStillAndOrdinaryTickResumesTravel() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.getSetbackTeleportUtil().hasFullyLoaded = true;
            player.getSetbackTeleportUtil().hasFullyJoined = true;
            Vec3 target = new Vec3(-36.65447998046875, 63.266326904296875, -46.8120231628418);
            queueTeleport(player, target);
            assertNotNull(process(player, frame(player, 6063, target, true)));
            player.checkManager.getSimulationProcessor().applyAcknowledgedBedrockGliding(true);
            long previousTick = state(player).simulationTick();
            for (long tick = 6064; tick <= 6076; tick++) {
                queueTeleport(player, target);
                queueTeleport(player, target);
                assertEquals(Vec3.ZERO, process(player, glideFrame(player, tick, target, true)));
                assertEquals(++previousTick, state(player).simulationTick());
                assertTrue(state(player).gliding());
                assertTrue(player.checkManager.getSimulationProcessor().getLastPrediction().getOffset() <= 0.001);
            }
            // Capture 1788995473774, auth tick 6077: the one ordinary tick between teleport frames.
            Vec3 moved = target.add(0.00191497802734375, -0.05266571044921875, -0.000560760498046875);
            Vec3 carried = process(player, glideFrame(player, 6077, moved, false));
            assertNotNull(carried);
            assertEquals(-0.052660823, carried.y, 0.00001);
            assertFalse(player.getSetbackTeleportUtil().isPendingSetback());
            for (long tick = 6078; tick <= 6159; tick++) {
                queueTeleport(player, target);
                queueTeleport(player, target);
                assertEquals(Vec3.ZERO, process(player, glideFrame(player, tick, target, true)));
                assertFalse(player.getSetbackTeleportUtil().isPendingSetback());
            }
            player.bedrockState.recordStopGlidingAction();
            queueTeleport(player, target);
            assertEquals(Vec3.ZERO, process(player, glideFrame(player, 6160, target, true)));
            assertFalse("teleport must not discard the pending actor action", state(player).gliding());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static BedrockAuthInputFrame glideFrame(CultPlayer player, long tick, Vec3 feet, boolean teleport) {
        return BedrockAuthInputFrame.builder(player.playerUUID).protocolVersion(2169).clientTick(tick)
            .position(feet).packetPosition(feet.add(0, 1.62, 0)).delta(Vec3.ZERO)
            .rotation(-106.32062F, -50.74109F, -106.32062F).moveVector(0, 0)
            .rawInputFlags(teleport ? 1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal() : 0L).build();
    }

    private static void assertIllegalMovementAfterTeleport(boolean inventedTeleport) throws Exception {
        CultPlayer player = waterPlayer();
        try {
            queueTeleport(player, TARGET);
            assertNotNull(process(player, frame(player, 10L, TARGET, true)));
            player.packetStateData.consumeBedrockTranslatedMovementPermit();
            // No queued teleport: HANDLE_TELEPORT must not authorize this jump.
            assertNull(process(player, frame(player, 11L, TARGET.add(2.0D, 0.0D, 0.0D), inventedTeleport)));
            assertTrue(player.getSetbackTeleportUtil().isPendingSetback());
            assertFalse(player.packetStateData.hasPendingBedrockTranslatedMovementDecision()
                    && !player.packetStateData.hasPendingRejectedBedrockTranslatedMovement());
            assertEquals(TARGET.x, player.x, 0.0D);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static void assertBlockedWithoutPrediction(CultPlayer player) {
        assertTrue(player.packetStateData.hasPendingRejectedBedrockTranslatedMovement());
        assertTrue(player.getSetbackTeleportUtil().hasPendingBedrockTransportTeleport());
        assertNull(player.checkManager.getSimulationProcessor().getLastPrediction());
    }

    @Test
    public void earlierReceiptSurvivesLaterOutboundTeleportAndMotion() throws Exception {
        CultPlayer player = waterPlayer();
        try {
            var kb = player.checkManager.getKnockbackHandler();
            queueTeleport(player, TARGET);
            Vec3 motionA = new Vec3(0, (double) -0.01F, 0);
            Runnable receiptA = observeMotion(player, motionA);
            Vec3 targetB = TARGET.add(1, 0, 0);
            queueTeleport(player, targetB);
            Vec3 motionB = new Vec3(0, (double) -0.02F, 0);
            Runnable receiptB = observeMotion(player, motionB);
            receiptA.run();
            var pendingB = kb.firstBread;
            assertEquals(motionA, process(player, frame(player, 10, TARGET, true)));
            assertSame(pendingB, kb.firstBread);
            receiptB.run();
            assertEquals(motionB, process(player, frame(player, 11, targetB, true)));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void consumingConfirmedMotionKeepsLaterSameRevisionMotion() throws Exception {
        CultPlayer player = waterPlayer();
        try {
            var kb = player.checkManager.getKnockbackHandler();
            queueTeleport(player, TARGET);
            observeMotion(player, new Vec3(0, (double) -0.01F, 0)).run();
            Vec3 motionB = new Vec3(0, (double) -0.02F, 0);
            Runnable receiptB = observeMotion(player, motionB);
            var pendingB = kb.firstBread;
            process(player, frame(player, 10, TARGET, true));
            assertSame(pendingB, kb.firstBread);
            receiptB.run();
            assertSame(pendingB, kb.secondBread);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void identicalMotionsHaveSeparateReceiptCallbacks() throws Exception {
        CultPlayer player = waterPlayer();
        try {
            var kb = player.checkManager.getKnockbackHandler();
            Vec3 motion = new Vec3(0, 0.2, 0);
            Runnable receiptA = observeMotion(player, motion);
            var entryA = kb.firstBread;
            Runnable receiptB = observeMotion(player, motion);
            assertSame(entryA, kb.firstBread);
            receiptA.run();
            var entryB = kb.firstBread;
            assertNotEquals(entryA, entryB);
            assertSame(entryA, kb.secondBread);
            assertSame(entryB, kb.firstBread);
            receiptB.run();
            assertSame(entryB, kb.secondBread);
            assertNull(kb.firstBread);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void receiptCannotReplayMotionConsumedBeforeItsMarker() throws Exception {
        CultPlayer player = waterPlayer();
        try {
            queueTeleport(player, TARGET);
            process(player, frame(player, 10, TARGET, true));
            Runnable receipt = observeMotion(player, new Vec3(0.125, 0, 0));
            var kb = player.checkManager.getKnockbackHandler();
            process(player, frame(player, 11, TARGET.add(0.125, 0, 0), false));
            assertFalse(player.getSetbackTeleportUtil().isPendingSetback());
            assertNull(kb.firstBread);
            receipt.run();
            assertNull(kb.secondBread);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static Runnable observeMotion(CultPlayer player, Vec3 motion) {
        var before = player.getLastClientboundBedrockTransaction();
        var receipt = player.createBedrockTransactionAfterClientbound();
        player.checkManager.getKnockbackHandler().handleObservedEntityVelocity(
            motion, player.entityID, player.getSetbackTeleportUtil().getBedrockTeleportRevision(),
            before, receipt);
        player.markBedrockTransactionClientbound(receipt.id());
        return () -> assertTrue(player.addTransactionResponse(receipt.id()));
    }

    private static void queueTeleport(CultPlayer player, Vec3 target) {
        player.getSetbackTeleportUtil().addImmediateBedrockTransportTeleport(target, false);
        player.lastTransactionReceived.set(player.lastTransactionSent.get());
    }

    private static ac.cult.cultac.bedrock.prediction.state.BedrockMovementState state(CultPlayer player) {
        BedrockNextTickStates carry = (BedrockNextTickStates) player.checkManager.getSimulationProcessor()
                .getCurrentPredictionCommit().carry();
        assertNotNull(carry);
        return carry.profileEntries().getFirst().state();
    }

    private static CultPlayer waterPlayer() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 60; y <= 66; y++) {
                    OfflineBedrockReplayEvents.apply(player, JsonParser.parseString(
                        "{\"type\":\"set_block\",\"position\":{\"x\":" + x + ",\"y\":" + y
                            + ",\"z\":" + z + "},\"block\":\"minecraft:water\"}").getAsJsonObject());
                }
            }
        }
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.getSetbackTeleportUtil().hasFullyJoined = true;
        return player;
    }

    private static BedrockAuthInputFrame frame(CultPlayer player, long tick, Vec3 feet, boolean teleport) {
        return BedrockAuthInputFrame.builder(player.playerUUID).protocolVersion(2169).clientTick(tick)
            .position(feet).packetPosition(feet.add(0.0D, 1.62D, 0.0D)).delta(Vec3.ZERO)
            .rotation(0.0F, 0.0F, 0.0F).moveVector(0.0F, 0.0F)
            .rawInputFlags(teleport ? 1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal() : 0L).build();
    }

    private static Vec3 process(CultPlayer player, BedrockAuthInputFrame frame) throws Exception {
        Method process = BedrockAuthInputPluginMessageListener.class.getDeclaredMethod(
            "processAuthInputFrameAndSelectVelocity", CultPlayer.class, BedrockAuthInputFrame.class);
        process.setAccessible(true);
        return (Vec3) process.invoke(new BedrockAuthInputPluginMessageListener(), player, frame);
    }
}
