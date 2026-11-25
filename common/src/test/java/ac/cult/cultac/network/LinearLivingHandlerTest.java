package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * AntiStutter and Interpolation Fix give living entities LinearInterpolationHandler: three steps toward the end of
 * each path. A 26.3 client that declared it is modelled that way for entities created after the declaration.
 */
class LinearLivingHandlerTest {
    private static final double Y = JavaTickFixture.FLOOR_Y;
    /** Linear steps from 0.5 toward 1.7: a third, a half, then the rest of the remaining distance. */
    private static final double[] LINEAR_X = {0.9, 1.3, 1.7};

    @Test
    void anOptedInZombieAndRemotePlayerLerpLinearlyToThePathEnd() throws Exception {
        for (int type : new int[] {EntityTypeIds.ZOMBIE, EntityTypeIds.PLAYER}) {
            try (var f = new JavaTickFixture()) {
                optIn(f);
                PacketEntity entity = spawn(f, type);
                assertTrue(entity.usesLinearInterpolationHandler());
                assertFalse(entity.newPacketLocation.usesSteppedInterpolation());
                steppedPath(f, entity);
                assertFalse(entity.newPacketLocation.usesSteppedInterpolation());
                assertLinear(f, entity);
            }
        }
    }

    @Test
    void withoutTheOptInTheSamePathStaysStepped() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity entity = spawn(f, EntityTypeIds.ZOMBIE);
            steppedPath(f, entity);
            assertFalse(entity.usesLinearInterpolationHandler());
            assertTrue(entity.newPacketLocation.usesSteppedInterpolation());
            tick(f);
            assertNotEquals(LINEAR_X[0], centerX(entity), 1.0E-9);
        }
        try (var f = new JavaTickFixture()) {
            f.player.interpolationOptIn.loginHandled(false);
            ClientInterpolationOptInTest.channels(f, ConnectionPhase.PLAY, "minecraft:register", "antistutter:linear_3");
            PacketEntity entity = spawn(f, EntityTypeIds.ZOMBIE);
            assertFalse(entity.usesLinearInterpolationHandler());
            assertTrue(entity.newPacketLocation.usesSteppedInterpolation());
        }
    }

    @Test
    void shulkersBoatsAndDisplaysAreUnchanged() throws Exception {
        for (int type : new int[] {EntityTypeIds.SHULKER, EntityTypeIds.OAK_BOAT, EntityTypeIds.ITEM_DISPLAY}) {
            assertEquals(
                    trace(ClientVersion.V_26_3, false, type, LinearLivingHandlerTest::steppedPath),
                    trace(ClientVersion.V_26_3, true, type, LinearLivingHandlerTest::steppedPath));
        }
    }

    @Test
    void olderAndBedrockClientsAreUnchanged() throws Exception {
        BiConsumer<JavaTickFixture, PacketEntity> move = (f, entity) ->
                entity.onBundleTransaction(false, true, 1.7, Y, 0.5, 90.0F, 0.0F, f.player);
        for (int type : new int[] {EntityTypeIds.ZOMBIE, EntityTypeIds.PLAYER}) {
            assertEquals(
                    trace(ClientVersion.V_1_21_11, false, type, move),
                    trace(ClientVersion.V_1_21_11, true, type, move));
            assertEquals(trace(null, false, type, move), trace(null, true, type, move));
        }
    }

    @Test
    void aDeclarationBeforeTheFirstTickGivesExistingEntitiesTheLinearHandler() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.interpolationOptIn.loginHandled(true);
            PacketEntity entity = spawn(f, EntityTypeIds.ZOMBIE);
            steppedPath(f, entity);
            assertTrue(entity.newPacketLocation.usesSteppedInterpolation());
            declare(f);
            assertTrue(entity.usesLinearInterpolationHandler());
            assertEquals(0.5, centerX(entity), 1.0E-9);
            assertLinear(f, entity);
        }
    }

    @Test
    void aDeclarationAfterTheFirstTickLeavesExistingEntitiesStepped() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.interpolationOptIn.loginHandled(true);
            PacketEntity entity = spawn(f, EntityTypeIds.ZOMBIE);
            steppedPath(f, entity);
            f.tickEnd();
            declare(f);
            assertFalse(entity.usesLinearInterpolationHandler());
            assertTrue(entity.newPacketLocation.usesSteppedInterpolation());
            PacketEntity later = spawn(f, 101, EntityTypeIds.ZOMBIE);
            assertTrue(later.usesLinearInterpolationHandler());
        }
    }

    @Test
    void anUnregisterLeavesAnExistingLinearEntityLinear() throws Exception {
        try (var f = new JavaTickFixture()) {
            optIn(f);
            PacketEntity entity = spawn(f, EntityTypeIds.ZOMBIE);
            ClientInterpolationOptInTest.channels(
                    f, ConnectionPhase.PLAY, "minecraft:unregister", "antistutter:linear_3");
            assertTrue(entity.usesLinearInterpolationHandler());
            steppedPath(f, entity);
            assertLinear(f, entity);
            assertFalse(spawn(f, 101, EntityTypeIds.ZOMBIE).usesLinearInterpolationHandler());
        }
    }

    private static void optIn(JavaTickFixture f) {
        f.player.interpolationOptIn.loginHandled(true);
        declare(f);
    }

    private static void declare(JavaTickFixture f) {
        ClientInterpolationOptInTest.channels(f, ConnectionPhase.PLAY, "minecraft:register", "antistutter:linear_3");
    }

    private static PacketEntity spawn(JavaTickFixture f, int type) {
        return spawn(f, 100, type);
    }

    private static PacketEntity spawn(JavaTickFixture f, int id, int type) {
        f.player.compensatedEntities.addEntity(id, type, new Vec3(0.5, Y, 0.5), 0, 0, 0);
        return f.player.compensatedEntities.getEntity(id);
    }

    /** A three-step path whose steps are not where a linear lerp passes. */
    private static void steppedPath(JavaTickFixture f, PacketEntity entity) {
        List<PositionPath.Step> steps = List.of(
                new PositionPath.Step(new Vec3d(1.5, Y, 0.5), 1),
                new PositionPath.Step(new Vec3d(1.6, Y, 0.5), 1),
                new PositionPath.Step(new Vec3d(1.7, Y, 0.5), 1));
        entity.onPositionPath(
                EntityPositionPath.fromProtocol(new PositionPath.Stepped(steps)), true, null, null, f.player);
    }

    private static void assertLinear(JavaTickFixture f, PacketEntity entity) {
        double halfWidth = (entity.getPossibleCollisionBoxes().maxX - entity.getPossibleCollisionBoxes().minX) / 2;
        for (double x : LINEAR_X) {
            tick(f);
            SimpleCollisionBox box = entity.getPossibleCollisionBoxes();
            assertEquals(x - halfWidth, box.minX, 1.0E-9);
            assertEquals(x + halfWidth, box.maxX, 1.0E-9);
            assertEquals(Y, box.minY, 1.0E-9);
            assertEquals(0.5 - halfWidth, box.minZ, 1.0E-9);
        }
        tick(f);
        assertEquals(LINEAR_X[2], centerX(entity), 1.0E-9);
    }

    private static void tick(JavaTickFixture f) {
        f.player.compensatedEntities.tickClientEntities(entity -> entity.onMovement(f.player, true));
    }

    private static double centerX(PacketEntity entity) {
        SimpleCollisionBox box = entity.getPossibleCollisionBoxes();
        return (box.minX + box.maxX) / 2;
    }

    /** The boxes of a {@code type} spawn moved once and ticked; a null version is a Bedrock player. */
    private static List<String> trace(
            ClientVersion version, boolean optedIn, int type, BiConsumer<JavaTickFixture, PacketEntity> move)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            if (version == null) f.player.movementPlatform = MovementPlatform.BEDROCK;
            else JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            if (optedIn) optIn(f);
            PacketEntity entity = spawn(f, type);
            assertFalse(entity.usesLinearInterpolationHandler());
            move.accept(f, entity);
            List<String> boxes = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                tick(f);
                boxes.add(String.valueOf(entity.getPossibleCollisionBoxes()));
            }
            return boxes;
        }
    }
}
