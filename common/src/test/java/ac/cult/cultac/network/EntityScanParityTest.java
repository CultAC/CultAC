package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The type-first entity scan predicates decide exactly what the predicates they replaced decided. */
class EntityScanParityTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 START = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);

    private static List<Integer> modelTypes() {
        List<Integer> ids = new ArrayList<>();
        for (EntityTypes.Type type : EntityTypes.defaults().types()) ids.add(type.id());
        return ids;
    }

    private static boolean oldPusherType(PacketEntity entity) {
        boolean hard = EntityTypeUtil.isBoat(entity.type)
                || EntityTypeUtil.isMinecart(entity.type)
                || entity.type == EntityTypeIds.STRIDER
                || EntityTypeUtil.isHappyGhast(entity.type);
        return !(!EntityTypeUtil.isLiving(entity.type) && !hard || entity.type == EntityTypeIds.ARMOR_STAND);
    }

    private static boolean oldPoseCollide(CultPlayer player, PacketEntity riding, PacketEntity entity) {
        if (entity == null || entity.isDead || entity.getEntityId() == player.entityID) return false;
        if (riding != null && (riding == entity || riding.hasPassenger(entity) || entity.hasPassenger(riding)))
            return false;
        return EntityTypeUtil.isBoat(entity.type)
                || EntityTypeUtil.isMinecart(entity.type)
                || entity.type == EntityTypeIds.SHULKER
                || EntityTypeUtil.isHappyGhast(entity.type);
    }

    @Test
    void pusherTypeTestMatchesForEveryModelType() {
        int accepted = 0;
        for (int id : modelTypes()) {
            PacketEntity entity = new PacketEntity(id, 1);
            boolean expected = oldPusherType(entity);
            assertEquals(
                    expected,
                    entity.mayPushOrCollide(),
                    EntityTypeUtil.getKey(id).toString());
            if (expected) accepted++;
        }
        assertTrue(accepted > 0);
    }

    @Test
    void poseCollisionTestMatchesForEveryTypeAndRidingState() throws Exception {
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            Method canCollide = CultPlayer.class.getDeclaredMethod(
                    "canCollideWithForPoseCheck", PacketEntity.class, PacketEntity.class);
            canCollide.setAccessible(true);
            int accepted = 0;
            for (int id : modelTypes()) {
                for (int entityId : new int[] {500, player.entityID}) {
                    for (boolean dead : new boolean[] {false, true}) {
                        for (int ridingState = 0; ridingState < 5; ridingState++) {
                            PacketEntity entity = new PacketEntity(id, entityId);
                            entity.isDead = dead;
                            PacketEntity riding = switch (ridingState) {
                                case 0 -> null;
                                case 1 -> entity;
                                default -> new PacketEntity(EntityTypeIds.OAK_BOAT, 600);
                            };
                            if (ridingState == 2) entity.mount(riding);
                            if (ridingState == 3) riding.mount(entity);
                            boolean expected = oldPoseCollide(player, riding, entity);
                            assertEquals(
                                    expected,
                                    canCollide.invoke(player, riding, entity),
                                    EntityTypeUtil.getKey(id) + " id=" + entityId + " dead=" + dead + " riding="
                                            + ridingState);
                            if (expected) accepted++;
                        }
                    }
                }
            }
            assertEquals(false, canCollide.invoke(player, null, null));
            assertTrue(accepted > 0);
        }
    }

    @Test
    void recountMatchesTheTickOrderFirstScan() throws Exception {
        Method pusherBox = WorldStageBuilder.class.getDeclaredMethod(
                "collidingPusherBox",
                CultPlayer.class,
                CompensatedEntities.class,
                PacketEntity.class,
                PacketEntity.class,
                PacketEntity.class,
                PacketEntity.class,
                SimpleCollisionBox.class,
                SimpleCollisionBox.class,
                SimpleCollisionBox.class);
        pusherBox.setAccessible(true);
        Method sweepBox = WorldStageBuilder.class.getDeclaredMethod("playerSweep", SimulationContext.class);
        sweepBox.setAccessible(true);
        Method contactBox = WorldStageBuilder.class.getDeclaredMethod("pusherContactBox", SimpleCollisionBox.class);
        contactBox.setAccessible(true);
        Method pushes = WorldStageBuilder.class.getDeclaredMethod(
                "pushesPlayer", PacketEntity.class, SimpleCollisionBox.class, SimpleCollisionBox.class);
        pushes.setAccessible(true);
        try (var f = new JavaTickFixture()) {
            f.place(START, Vec3.ZERO);
            f.input(NONE);
            PredictionResult prediction = f.tick(START, true);
            CompensatedEntities entities = f.player.compensatedEntities;
            int entityId = 1000;
            List<Integer> types = modelTypes();
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i) == EntityTypeIds.ITEM || types.get(i) == EntityTypeIds.PLAYER) continue;
                double offset = (i % 5) * 0.3 - 0.6;
                entities.addEntity(entityId++, types.get(i), new Vec3(0.5 + offset, START.y, 1.0 + offset), 0, 0, 0);
                if (i == types.size() / 2) entities.appendSelfToClientTickOrder();
            }
            PacketEntity riddenBoat = entities.getEntity(1000 + types.indexOf(EntityTypeIds.OAK_BOAT));
            PacketEntity rider = entities.getEntity(1000 + types.indexOf(EntityTypeIds.ZOMBIE));
            rider.mount(riddenBoat);

            SimulationContext context = prediction.getSimulationContext();
            SimpleCollisionBox sweep = (SimpleCollisionBox) sweepBox.invoke(null, context);
            SimpleCollisionBox playerBox = (SimpleCollisionBox) contactBox.invoke(null, sweep);
            long playerTickOrder = entities.getSelf().getClientTickOrder();
            int expected = 0;
            int colliding = 0;
            for (PacketEntity entity : entities.entityMap.values()) {
                SimpleCollisionBox entityBox = (SimpleCollisionBox) pusherBox.invoke(
                        null,
                        f.player,
                        entities,
                        entity,
                        entities.rootOfSelf(),
                        entities.selfRidingRoot(),
                        entities.serverVehicleRoot(),
                        playerBox,
                        new SimpleCollisionBox(),
                        new SimpleCollisionBox());
                if (entityBox != null) colliding++;
                if (entities.clientTickOrder(entity) < playerTickOrder) continue;
                // The recount keeps the movement's own count of riders outside the player's riding trees.
                if (entities.isUntrackedRider(entity)) continue;
                if (entityBox != null && (boolean) pushes.invoke(null, entity, entityBox, sweep)) expected++;
            }
            WorldStageBuilder.recountCollidingTickingAfterPlayer(f.player, prediction);
            assertEquals(
                    expected + context.getWorldData().getNumRidersTickingAfterPlayer(),
                    context.getWorldData().getNumCollidingTickingAfterPlayer());
            assertTrue(expected > 0 && expected < colliding, "expected=" + expected + " colliding=" + colliding);
        }
    }
}
