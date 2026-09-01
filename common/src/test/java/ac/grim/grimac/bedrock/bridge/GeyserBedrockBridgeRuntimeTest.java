package ac.grim.grimac.bedrock.bridge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.junit.Test;
import org.mockito.Mockito;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class GeyserBedrockBridgeRuntimeTest {
    private static final float JUMP_VELOCITY = 0.42F;

    @Test
    public void bootstrapUsesZeroTrustedVelocityWithSignClamp() {
        Vector3f reported = Vector3f.from(0.1F, -0.08F, -0.2F);
        Vector3f rewritten = GeyserBedrockBridgeRuntime.rewriteDelta(reported, Vector3f.ZERO);
        assertEquals(0.0F, rewritten.getX(), 0.0F);
        assertEquals(-0.01F, rewritten.getY(), 0.0F);
        assertEquals(0.0F, rewritten.getZ(), 0.0F);
        assertSame(Vector3f.ZERO, GeyserBedrockBridgeRuntime.rewriteDelta(
                Vector3f.from(0.1F, 0.08F, -0.2F), Vector3f.ZERO));
        assertNull(GeyserBedrockBridgeRuntime.rewriteDelta(null, Vector3f.ZERO));
        assertSame(reported, GeyserBedrockBridgeRuntime.rewriteDelta(reported, null));
    }

    @Test
    public void matchingVerticalSignInstallsFullTrustedVector() {
        Vector3f trusted = Vector3f.from(0.1F, -0.0784F, 0.2F);
        assertSame(trusted, GeyserBedrockBridgeRuntime.rewriteDelta(
                Vector3f.from(9.0F, -0.08F, -9.0F), trusted));

        Vector3f trustedUp = Vector3f.from(-0.2F, 0.42F, 0.1F);
        assertSame(trustedUp, GeyserBedrockBridgeRuntime.rewriteDelta(
                Vector3f.from(0.0F, 0.0F, 0.0F), trustedUp));
    }

    @Test
    public void signMismatchKeepsTrustedHorizontalAndClampsClaimedY() {
        Vector3f trusted = Vector3f.from(0.3F, 0.42F, -0.1F);

        // A modified client claiming +1000 Y must not cache that magnitude;
        // only its (negative) sign class survives.
        Vector3f rewritten = GeyserBedrockBridgeRuntime.rewriteDelta(
                Vector3f.from(1000.0F, -1000.0F, 1000.0F), trusted);
        assertEquals(0.3F, rewritten.getX(), 0.0F);
        assertEquals(-0.01F, rewritten.getY(), 0.0F);
        assertEquals(-0.1F, rewritten.getZ(), 0.0F);

        // Trusted falling, client claiming huge upward velocity: +0.01 only.
        rewritten = GeyserBedrockBridgeRuntime.rewriteDelta(
                Vector3f.from(0.0F, 1000.0F, 0.0F), Vector3f.from(0.05F, -0.5F, 0.05F));
        assertEquals(0.05F, rewritten.getX(), 0.0F);
        assertEquals(0.01F, rewritten.getY(), 0.0F);
        assertEquals(0.05F, rewritten.getZ(), 0.0F);
    }

    @Test
    public void clampedYKeepsOnlyTheSignClass() {
        assertEquals(-0.01F, GeyserBedrockBridgeRuntime.clampSignPreservingY(-1000.0F), 0.0F);
        assertEquals(-0.01F, GeyserBedrockBridgeRuntime.clampSignPreservingY(-0.08F), 0.0F);
        assertEquals(0.01F, GeyserBedrockBridgeRuntime.clampSignPreservingY(1000.0F), 0.0F);
        assertEquals(0.01F, GeyserBedrockBridgeRuntime.clampSignPreservingY(0.42F), 0.0F);
        // Zero is in the non-negative class of the y < 0 comparison.
        assertEquals(0.01F, GeyserBedrockBridgeRuntime.clampSignPreservingY(0.0F), 0.0F);
    }

    @Test
    public void rewrittenChainDerivesIdenticalOnGroundToStockGeyser() {
        // Client truth: falling, landing, resting, jumping, airborne, landing.
        float[] clientY = {-0.08F, -0.5F, -0.08F, 0.42F, 0.3F, -0.2F, -0.08F};
        boolean[] startJumping = {false, false, false, true, false, false, false};
        boolean[] verticalCollision = {true, true, true, true, false, false, true};
        // Trusted engine values deliberately disagree in magnitude and twice
        // in sign (ticks 2 and 5) to exercise the clamped fallback.
        float[] trustedY = {-0.0784F, -0.4F, 0.01F, 0.42F, 0.25F, 0.01F, -0.07F};

        // Stock Geyser chain: each packet stores the client's own delta.
        float stockStoredY = 0.0F;
        // Bridge chain: each packet stores the rewritten delta; the async
        // completion overwrites it only when the Y-sign class is preserved.
        float bridgeStoredY = 0.0F;
        Vector3f lastKnownGood = null;
        boolean stockOnGround = false;
        boolean bridgeOnGround = false;

        List<Boolean> stockDerivation = new ArrayList<>();
        List<Boolean> bridgeDerivation = new ArrayList<>();
        for (int tick = 0; tick < clientY.length; tick++) {
            // Geyser's START_JUMPING adjustment (BedrockMovePlayer.java:101-104).
            float stockAdjusted = stockOnGround && startJumping[tick]
                    ? Math.max(stockStoredY, JUMP_VELOCITY) : stockStoredY;
            float bridgeAdjusted = bridgeOnGround && startJumping[tick]
                    ? Math.max(bridgeStoredY, JUMP_VELOCITY) : bridgeStoredY;

            // BedrockMovePlayer.java:122 without vehicle/noClip.
            stockOnGround = verticalCollision[tick] && stockAdjusted < 0.0F;
            bridgeOnGround = verticalCollision[tick] && bridgeAdjusted < 0.0F;
            stockDerivation.add(stockOnGround);
            bridgeDerivation.add(bridgeOnGround);

            // Stock stores the client delta; the bridge stores the rewrite.
            stockStoredY = clientY[tick];
            Vector3f rewritten = GeyserBedrockBridgeRuntime.rewriteDelta(
                    Vector3f.from(0.0F, clientY[tick], 0.0F), lastKnownGood);
            bridgeStoredY = rewritten.getY();

            // Async completion lands after this packet and before the next.
            Vector3f trusted = Vector3f.from(0.0F, trustedY[tick], 0.0F);
            if ((trusted.getY() < 0.0F) == (bridgeStoredY < 0.0F)) {
                bridgeStoredY = trusted.getY();
            }
            lastKnownGood = trusted;
        }

        assertEquals(stockDerivation, bridgeDerivation);
    }

    @Test
    public void javaUserBindsToExactGeyserDownstreamSocket() {
        io.netty.channel.Channel geyserDownstream = Mockito.mock(io.netty.channel.Channel.class);
        io.netty.channel.Channel matchingServer = Mockito.mock(io.netty.channel.Channel.class);
        io.netty.channel.Channel otherServer = Mockito.mock(io.netty.channel.Channel.class);
        InetSocketAddress endpoint = new InetSocketAddress("127.0.0.1", 32145);

        Mockito.when(geyserDownstream.localAddress()).thenReturn(endpoint);
        Mockito.when(matchingServer.remoteAddress()).thenReturn(endpoint);
        Mockito.when(otherServer.remoteAddress())
                .thenReturn(new InetSocketAddress("127.0.0.1", 32146));

        assertTrue(GeyserBedrockBridgeRuntime.sameJavaConnection(
                geyserDownstream, matchingServer));
        assertFalse(GeyserBedrockBridgeRuntime.sameJavaConnection(
                geyserDownstream, otherServer));
        assertFalse(GeyserBedrockBridgeRuntime.sameJavaConnection(
                geyserDownstream, new Object()));
    }

    @Test
    public void authInputIsForwardedBeforeGeyserTranslatesMovement() {
        List<String> order = new ArrayList<>();

        String result = GeyserBedrockBridgeRuntime.forwardAuthInputBeforeTranslation(
                () -> order.add("auth-plugin-message"),
                error -> order.add("forwarding-failure"),
                () -> {
                    order.add("translated-movement");
                    return "translated";
                });

        assertEquals("translated", result);
        assertEquals(List.of("auth-plugin-message", "translated-movement"), order);
    }

    @Test
    public void failedAuthForwardStillDelegatesButCannotSatisfyBackendOrderProof() {
        List<String> order = new ArrayList<>();

        String result = GeyserBedrockBridgeRuntime.forwardAuthInputBeforeTranslation(
                () -> {
                    order.add("auth-plugin-message");
                    throw new IllegalStateException("forwarding failed");
                },
                error -> order.add("forwarding-failure"),
                () -> {
                    order.add("translated-movement");
                    return "translated";
                });

        assertEquals("translated", result);
        assertEquals(List.of(
                "auth-plugin-message",
                "forwarding-failure",
                "translated-movement"), order);
    }

    @Test
    public void burstAuthInputsRemainPairedBeforeTranslation() {
        Deque<Runnable> tickLoop = new ArrayDeque<>();
        List<String> order = new ArrayList<>();

        assertEquals(PacketSignal.HANDLED, GeyserBedrockBridgeRuntime.scheduleAuthInputBeforeTranslation(
                tickLoop::addLast,
                () -> order.add("auth-a"),
                error -> order.add("failure-a"),
                () -> {
                    order.add("movement-a");
                    return PacketSignal.HANDLED;
                }));
        assertEquals(PacketSignal.HANDLED, GeyserBedrockBridgeRuntime.scheduleAuthInputBeforeTranslation(
                tickLoop::addLast,
                () -> order.add("auth-b"),
                error -> order.add("failure-b"),
                () -> {
                    order.add("movement-b");
                    return PacketSignal.HANDLED;
                }));

        assertTrue(order.isEmpty());
        tickLoop.removeFirst().run();
        tickLoop.removeFirst().run();
        assertEquals(List.of("auth-a", "movement-a", "auth-b", "movement-b"), order);
    }

    @Test
    public void channelCloseRaceMakesTapRemovalIdempotent() {
        ChannelPipeline pipeline = Mockito.mock(ChannelPipeline.class);
        ChannelHandler handler = Mockito.mock(ChannelHandler.class);
        Mockito.when(pipeline.get("tap")).thenReturn(handler);
        Mockito.when(pipeline.remove("tap")).thenThrow(new NoSuchElementException("tap"));

        GeyserBedrockBridgeRuntime.removeHandlerIfPresent(pipeline, "tap");

        Mockito.verify(pipeline).remove("tap");
    }

    @Test
    public void projectedGroundMatchesGeyserPreviousVelocityRule() {
        assertTrue(GeyserBedrockBridgeRuntime.projectedOnGround(
                true, false, false, false, false, false, false, -0.08F, 0.42F));
        assertFalse(GeyserBedrockBridgeRuntime.projectedOnGround(
                true, false, false, false, false, false, false, 0.0F, 0.42F));
        assertFalse(GeyserBedrockBridgeRuntime.projectedOnGround(
                false, false, false, false, false, false, false, -0.08F, 0.42F));
    }

    @Test
    public void projectedGroundAppliesGeyserJumpVehicleAndNoClipOrdering() {
        assertFalse(GeyserBedrockBridgeRuntime.projectedOnGround(
                true, false, false, true, true, false, false, -0.08F, 0.42F));
        assertFalse(GeyserBedrockBridgeRuntime.projectedOnGround(
                true, false, false, false, false, true, true, -0.08F, 0.42F));
        assertFalse(GeyserBedrockBridgeRuntime.projectedOnGround(
                true, true, false, false, false, false, false, -0.08F, 0.42F));
        assertFalse(GeyserBedrockBridgeRuntime.projectedOnGround(
                true, false, true, false, false, false, false, -0.08F, 0.42F));
    }

    @Test
    public void everyClientVisibleMovePlayerTeleportCreatesPositionBoundary() {
        assertTrue(GeyserBedrockBridgeRuntime.isGeyserPositionTeleport(
                MovePlayerPacket.Mode.RESPAWN));
        assertTrue(GeyserBedrockBridgeRuntime.isGeyserPositionTeleport(
                MovePlayerPacket.Mode.TELEPORT));
        assertFalse(GeyserBedrockBridgeRuntime.isGeyserPositionTeleport(
                MovePlayerPacket.Mode.NORMAL));
    }

    @Test
    public void selfNormalCorrectionIsConvertedToOwnedTeleportBoundary() {
        MovePlayerPacket packet = new MovePlayerPacket();
        packet.setMode(MovePlayerPacket.Mode.NORMAL);

        GeyserBedrockBridgeRuntime.convertSelfCorrectionToTeleport(packet);

        assertEquals(MovePlayerPacket.Mode.TELEPORT, packet.getMode());
        assertEquals(MovePlayerPacket.TeleportationCause.BEHAVIOR, packet.getTeleportationCause());
    }

    @Test
    public void geyserSelfEntityTeleportPositionIsConvertedToPhysicalFeet() {
        Vector3f packetPosition = Vector3f.from(223.7F, 67.1152F, 416.82675F);

        net.minecraft.world.phys.Vec3 physicalFeet =
                GeyserBedrockBridgeRuntime.toJavaPosition(packetPosition);

        assertEquals((double) packetPosition.getX(), physicalFeet.x, 0.0D);
        assertEquals((double) packetPosition.getY() - 1.6200103759765625D,
                physicalFeet.y, 0.0D);
        assertEquals((double) packetPosition.getZ(), physicalFeet.z, 0.0D);
    }

}
