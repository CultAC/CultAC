package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class OfflineBedrockReplayEventsTest {
    @Test
    public void offlineBootstrapLoadsVanillaBlockTags() {
        OfflineCultTestBootstrap.installConfig();

        assertTrue(Blocks.LADDER.defaultBlockState().is(BlockTags.CLIMBABLE));
        assertTrue(Blocks.VINE.defaultBlockState().is(BlockTags.CLIMBABLE));
    }

    @Test
    public void timingSupportsTwentyTimesReplay() {
        assertEquals(20.0D, OfflineBedrockReplayEvents.DEFAULT_SPEED_MULTIPLIER, 0.0D);
        assertEquals(2_500_000L, OfflineBedrockReplayEvents.scaledDelayNanos(50_000_000L));
    }

    @Test
    public void velocityAndExplosionEventsEnterPacketModifierState() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            OfflineBedrockReplayEvents.apply(player, json("""
                    {"type":"velocity","entityId":0,"velocity":{"x":0.4,"y":0.2,"z":-0.1}}
                    """));
            assertTrue(player.checkManager.getKnockbackHandler().hasPacketVelocity());
            assertEquals(0.4D, player.checkManager.getKnockbackHandler().secondBread.getVel().x, 1.0E-9D);

            OfflineBedrockReplayEvents.apply(player, json("""
                    {"type":"explosion","knockback":{"x":-0.2,"y":0.5,"z":0.3}}
                    """));
            assertTrue(player.checkManager.getExplosionHandler().hasPacketVelocity());
            assertEquals(0.5D, player.checkManager.getExplosionHandler().secondBread.getVel().y, 1.0E-9D);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void pistonAndSlimePistonEventsEnterCompensatedPistonState() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            OfflineBedrockReplayEvents.apply(player, json("""
                    {"type":"set_block","position":{"x":1,"y":82,"z":0},"block":"minecraft:slime_block"}
                    """));
            OfflineBedrockReplayEvents.apply(player, json("""
                    {"type":"piston","position":{"x":0,"y":82,"z":0},"block":"minecraft:piston[facing=east,extended=false]","triggerType":0,"direction":"east"}
                    """));

            assertFalse(player.compensatedWorld.pistons.activePistons().isEmpty());
            assertTrue(player.compensatedWorld.pistons.activePistons().iterator().next().hasSlimeBlock);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void bedrockPistonMovementAdvancesAtClientTickEnd() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            OfflineBedrockReplayEvents.apply(player, json("""
                    {"type":"set_block","position":{"x":1,"y":82,"z":0},"block":"minecraft:slime_block"}
                    """));
            OfflineBedrockReplayEvents.apply(player, json("""
                    {"type":"piston","position":{"x":0,"y":82,"z":0},"block":"minecraft:piston[facing=east,extended=false]","triggerType":0,"direction":"east"}
                    """));

            var piston = player.compensatedWorld.pistons.activePistons().iterator().next();
            assertFalse(piston.canAffectMovement());

            player.compensatedWorld.onClientTickEnd();
            assertTrue(piston.canAffectMovement());

            // Piston animation advances from the client's explicit tick
            // boundary, independently of Geyser's server-authored velocity.
            player.compensatedWorld.onClientTickEnd();
            assertTrue(player.compensatedWorld.pistons.activePistons().contains(piston));

            player.compensatedWorld.onClientTickEnd();
            assertFalse(player.compensatedWorld.pistons.activePistons().contains(piston));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void bedrockTransportCorrectionUsesNormalTeleportQueueAndCommit() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            Vec3 target = new Vec3(player.x + 0.12723159790039062D, player.y, player.z);
            Vec3 packetTarget = target.add(0.0D, 1.62D, 0.0D);
            long revision = player.getSetbackTeleportUtil()
                    .addImmediateBedrockTransportTeleport(target, true);

            assertTrue(player.getSetbackTeleportUtil().hasPendingPlayerPositionTeleport());
            var accepted = player.getSetbackTeleportUtil()
                    .acknowledgeBedrockTeleportFrame(target);
            assertTrue(accepted.isTeleport());
            assertFalse(accepted.getTeleportData().isRelativeX());
            assertFalse(accepted.getTeleportData().isRelativeY());
            assertFalse(accepted.getTeleportData().isRelativeZ());
            assertFalse(accepted.getTeleportData().isRelativeDeltaX());
            assertFalse(accepted.getTeleportData().isRelativeDeltaY());
            assertFalse(accepted.getTeleportData().isRelativeDeltaZ());
            assertEquals(Boolean.TRUE, accepted.getTeleportData().getBedrockOnGround());

            player.checkManager.getSimulationProcessor().applyAcceptedBedrockTeleport(accepted);

            assertEquals(target.x, player.x, 0.0D);
            assertEquals(target.y, player.y, 0.0D);
            assertEquals(target.z, player.z, 0.0D);
            assertFalse(player.getSetbackTeleportUtil().hasPendingPlayerPositionTeleport());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void bedrockTransportTeleportsRetainNormalQueueOrder() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            Vec3 firstSweep = new Vec3(player.x + 0.05D, player.y, player.z);
            Vec3 secondSweep = new Vec3(player.x + 0.10D, player.y, player.z);
            Vec3 firstPacket = firstSweep.add(0.0D, 1.62D, 0.0D);
            Vec3 secondPacket = secondSweep.add(0.0D, 1.62D, 0.0D);
            long firstRevision = player.getSetbackTeleportUtil()
                    .addImmediateBedrockTransportTeleport(firstSweep, true);
            long secondRevision = player.getSetbackTeleportUtil()
                    .addImmediateBedrockTransportTeleport(secondSweep, true);

            var firstAccepted = player.getSetbackTeleportUtil()
                    .acknowledgeBedrockTeleportFrame(firstSweep);
            assertTrue(firstAccepted.isTeleport());
            assertEquals(firstSweep, firstAccepted.getTeleportData().getLocation());

            var secondAccepted = player.getSetbackTeleportUtil()
                    .acknowledgeBedrockTeleportFrame(secondSweep);
            assertTrue(secondAccepted.isTeleport());
            assertEquals(secondSweep, secondAccepted.getTeleportData().getLocation());
            assertFalse(player.getSetbackTeleportUtil().hasPendingPlayerPositionTeleport());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void bedrockHandleTeleportCompletesRepeatedIdenticalOutboundBoundaries() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            Vec3 target = new Vec3(player.x + 0.125D, player.y, player.z);
            player.getSetbackTeleportUtil().addImmediateBedrockTransportTeleport(target, true);
            player.getSetbackTeleportUtil().addImmediateBedrockTransportTeleport(target, true);

            var ordinaryFrame = player.getSetbackTeleportUtil()
                    .acknowledgeBedrockTeleportFrame(target, false);
            assertFalse(ordinaryFrame.isTeleport());
            assertTrue(player.getSetbackTeleportUtil().hasPendingBedrockTransportTeleport());

            var accepted = player.getSetbackTeleportUtil()
                    .acknowledgeBedrockTeleportFrame(target, true);
            assertTrue(accepted.isTeleport());
            assertEquals(target, accepted.getTeleportData().getLocation());
            assertFalse(player.getSetbackTeleportUtil().hasPendingBedrockTransportTeleport());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void bedrockTeleportFrameRequiresExistingTransactionAndExactTarget() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            Vec3 target = new Vec3(player.x + 0.25D, player.y, player.z);
            int teleportTransaction = player.lastTransactionSent.incrementAndGet();
            player.getSetbackTeleportUtil().addSentTeleport(
                    target, teleportTransaction, new RelativeFlag(0), false, 42);
            long revision = player.getSetbackTeleportUtil()
                    .addImmediateBedrockTransportTeleport(target, false);

            // Geyser and Cult use the same exact correction echo. Cult consumes
            // it as a teleport boundary without offering it to ActorMove.
            var beforeTransaction = player.getSetbackTeleportUtil()
                    .acknowledgeBedrockTeleportFrame(target);
            assertFalse(beforeTransaction.isTeleport());
            assertTrue(player.getSetbackTeleportUtil().hasPendingPlayerPositionTeleport());

            player.lastTransactionReceived.set(teleportTransaction);
            var accepted = player.getSetbackTeleportUtil()
                    .acknowledgeBedrockTeleportFrame(target);
            assertTrue(accepted.isTeleport());
            assertEquals(target, accepted.getTeleportData().getLocation());

            var replayed = player.getSetbackTeleportUtil()
                    .acknowledgeBedrockTeleportFrame(target);
            assertFalse(replayed.isTeleport());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void geyserTeleportWithoutJavaPacketUsesOrderedBoundaryWithoutPositionMatching() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            Vec3 target = new Vec3(228.72578D, 82.875D, -77.0519D);
            var rollbackBefore = player.getSetbackTeleportUtil().lastKnownGoodPosition;
            long revision = player.getSetbackTeleportUtil()
                    .addImmediateBedrockTransportTeleport(target, false);

            assertTrue(player.getSetbackTeleportUtil().hasPendingPlayerPositionTeleport());
            assertTrue(player.getSetbackTeleportUtil().pendingTeleports.peek().isBedrockTransportOnly());
            assertNull(player.getSetbackTeleportUtil().getRequiredSetBack());
            assertSame(rollbackBefore, player.getSetbackTeleportUtil().lastKnownGoodPosition);
            var accepted = player.getSetbackTeleportUtil()
                    .acknowledgeBedrockTeleportFrame(target);
            assertTrue(accepted.isTeleport());
            assertNull(accepted.getSetback());
            assertEquals(target, accepted.getTeleportData().getLocation());
            assertFalse(accepted.getTeleportData().isRelativeX());
            assertFalse(accepted.getTeleportData().isRelativeY());
            assertFalse(accepted.getTeleportData().isRelativeZ());
            assertFalse(accepted.getTeleportData().isRelativeDeltaX());
            assertFalse(accepted.getTeleportData().isRelativeDeltaY());
            assertFalse(accepted.getTeleportData().isRelativeDeltaZ());
            assertNull(player.getSetbackTeleportUtil().getRequiredSetBack());
            assertSame(rollbackBefore, player.getSetbackTeleportUtil().lastKnownGoodPosition);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void geyserTransportMarkerPreservesObservedJavaTeleportVelocity() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            Vec3 target = new Vec3(player.x + 2.0D, player.y + 1.0D, player.z);
            Vec3 deltaMovement = new Vec3(0.25D, 0.5D, -0.125D);
            RelativeFlag flags = new RelativeFlag(
                    RelativeFlag.DELTA_X.getMask() | RelativeFlag.DELTA_Z.getMask());
            player.getSetbackTeleportUtil().addImmediatePlayerTeleport(
                    target, deltaMovement, flags, player.lastTransactionSent.get(),
                    0.0F, 0.0F, 0.0F, 0.0F);
            Vec3 packetTarget = target.add(0.0D, 1.62D, 0.0D);
            long revision = player.getSetbackTeleportUtil()
                    .addImmediateBedrockTransportTeleport(target, false);

            assertEquals(1, player.getSetbackTeleportUtil().pendingTeleports.size());
            var accepted = player.getSetbackTeleportUtil()
                    .acknowledgeBedrockTeleportFrame(target);
            assertTrue(accepted.isTeleport());
            assertEquals(flags.getMask(), accepted.getTeleportData().getFlags().getMask());
            assertEquals(deltaMovement, accepted.getTeleportData().getDeltaMovement());
            assertEquals(new Vec3(1.25D, 0.5D, 0.875D),
                    accepted.getTeleportData().applyToVelocity(new Vec3(1.0D, 1.0D, 1.0D)));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void outboundBedrockMarkerRestoresJavaTeleportConsumedByInternalMove() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            Vec3 target = new Vec3(player.x + 0.25D, player.y + 0.5D, player.z);
            Vec3 packetTarget = target.add(0.0D, 1.62D, 0.0D);
            Vec3 deltaMovement = new Vec3(0.125D, 0.25D, -0.0625D);
            RelativeFlag flags = new RelativeFlag(RelativeFlag.DELTA_X.getMask());
            int transaction = player.lastTransactionSent.get();

            player.getSetbackTeleportUtil().addSentTeleport(
                    target, deltaMovement, transaction, flags, false, 42);
            player.getSetbackTeleportUtil().pendingTeleports.clear();

            long revision = player.getSetbackTeleportUtil()
                    .addImmediateBedrockTransportTeleport(target, false);

            assertEquals(1, player.getSetbackTeleportUtil().pendingTeleports.size());
            var queued = player.getSetbackTeleportUtil().pendingTeleports.peek();
            assertNotNull(queued);
            assertEquals(transaction, queued.getTransaction());
            assertTrue(queued.getTransaction() != Integer.MAX_VALUE);
            assertEquals(deltaMovement, queued.getDeltaMovement());

            player.lastTransactionReceived.set(transaction);
            var accepted = player.getSetbackTeleportUtil()
                    .acknowledgeBedrockTeleportFrame(target);
            assertTrue(accepted.isTeleport());
            assertNotNull(accepted.getSetback());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void shulkerAndHardCollidingEntitiesAreReplayable() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            OfflineBedrockReplayEvents.apply(player, json("""
                    {"type":"add_entity","entityId":101,"entityType":"minecraft:oak_boat","position":{"x":2.0,"y":82.0,"z":0.0},"yaw":90.0}
                    """));
            OfflineBedrockReplayEvents.apply(player, json("""
                    {"type":"add_entity","entityId":102,"entityType":"minecraft:happy_ghast","position":{"x":4.0,"y":82.0,"z":0.0},"yaw":0.0}
                    """));
            OfflineBedrockReplayEvents.apply(player, json("""
                    {"type":"add_entity","entityId":103,"entityType":"minecraft:shulker","position":{"x":6.0,"y":82.0,"z":0.0}}
                    """));
            OfflineBedrockReplayEvents.apply(player, json("""
                    {"type":"shulker_block","position":{"x":7,"y":82,"z":0},"open":true}
                    """));

            PacketEntity boat = player.compensatedEntities.getEntity(101);
            PacketEntity happyGhast = player.compensatedEntities.getEntity(102);
            PacketEntity shulker = player.compensatedEntities.getEntity(103);
            assertNotNull(boat);
            assertNotNull(happyGhast);
            assertNotNull(shulker);
            assertTrue(boat.isBoat());
            assertSame(EntityTypesCompat.HAPPY_GHAST, happyGhast.type);
            assertSame(EntityTypesCompat.SHULKER, shulker.type);
            assertFalse(player.compensatedWorld.openShulkerBoxes.isEmpty());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void acknowledgedBoundingBoxCallbackIsReplayable() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            BedrockMovementState nativeSneak = BedrockMovementState.fromPhysicalFeet(
                    new Vec3d(0.0D, 64.0D, 0.0D),
                    Vec3d.ZERO,
                    BedrockInputFrame.idle(0L),
                    BedrockCollisionFlags.AIR)
                    .withPlayerDimensions(new PlayerDimensionsState(0.6D, 1.49D), false);
            OfflineBedrockReplayEvents.apply(player, json("""
                    {"type":"bounding_box_ack","width":0.6,"height":1.5}
                    """));
            BedrockMovementState acknowledged = player.bedrockState.applyConfirmedBoundingBoxSize(
                    nativeSneak, nativeSneak.inputFrame());
            assertEquals(1.5D, acknowledged.playerDimensions().height(), 0.0D);
            assertTrue(acknowledged.explicitPlayerDimensions());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}
