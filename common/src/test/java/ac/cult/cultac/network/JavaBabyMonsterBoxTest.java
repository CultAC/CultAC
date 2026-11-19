package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Zombie, Zoglin and Piglin define their own DATA_BABY_ID outside AgeableMob and size themselves by it. */
class JavaBabyMonsterBoxTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    private static final int MOB = 100;

    @Test
    void aBabyMonsterTakesItsBabyDimensions() throws Exception {
        Map<Integer, Double> babyWidths = Map.of(
                EntityTypeIds.ZOMBIE, 0.49,
                EntityTypeIds.HUSK, 0.49,
                EntityTypeIds.DROWNED, 0.49,
                EntityTypeIds.ZOMBIE_VILLAGER, 0.49,
                EntityTypeIds.ZOMBIFIED_PIGLIN, 0.49,
                EntityTypeIds.PIGLIN, 0.49,
                EntityTypeIds.ZOGLIN, 0.75);
        for (var type : babyWidths.entrySet()) {
            try (var f = new JavaTickFixture()) {
                PacketEntity mob = spawn(f, type.getKey(), new Vec3(0.5, FLOOR_Y, 0.5));
                double adultWidth = width(mob);
                setBaby(f, WatchableIndexUtil.babyIndex(type.getKey()));
                assertTrue(mob.isBaby, "type " + type.getKey());
                assertEquals(type.getValue(), width(mob), 1.0E-6, "type " + type.getKey());
                assertTrue(width(mob) < adultWidth, "type " + type.getKey());
            }
        }
    }

    /** Index 16 on a piglin is AbstractPiglin's DATA_IMMUNE_TO_ZOMBIFICATION; Piglin's DATA_BABY_ID follows it. */
    @Test
    void aPiglinImmuneToZombificationStaysAdult() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity piglin = spawn(f, EntityTypeIds.PIGLIN, new Vec3(0.5, FLOOR_Y, 0.5));
            setBaby(f, 16);
            assertFalse(piglin.isBaby);
            assertEquals(0.6, width(piglin), 1.0E-6);
        }
    }

    /** A baby zombie's 0.49 box misses a player 0.56 away on both axes; the adult's 0.6 box would push it. */
    @Test
    void aBabyZombieOutOfItsOwnReachDoesNotPush() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 feet = new Vec3(0.5, FLOOR_Y, 0.5);
            spawn(f, EntityTypeIds.ZOMBIE, new Vec3(feet.x + 0.56, FLOOR_Y, feet.z + 0.56));
            setBaby(f, WatchableIndexUtil.ZOMBIE_BABY);

            f.place(feet, GROUNDED);
            f.player.onGround = f.player.lastOnGround = true;
            f.input(NONE);
            // A pusher ticking after the player moves it on the following tick.
            for (int tick = 0; tick < 3; tick++) {
                PredictionResult still = f.tick(feet, true);
                assertTrue(exact(still), "tick " + tick + ": " + describe(still));
            }
        }
    }

    private static PacketEntity spawn(JavaTickFixture f, int type, Vec3 at) {
        f.player.compensatedEntities.addEntity(MOB, type, at, 0, 0, 0);
        return f.player.compensatedEntities.getEntity(MOB);
    }

    private static void setBaby(JavaTickFixture f, int index) {
        f.player.compensatedEntities.updateEntityMetadata(
                MOB, List.of(new EntityMetadata.Entry(index, true, ByteBuffer.allocate(0))));
    }

    private static double width(PacketEntity entity) {
        var box = entity.getPossibleMovementCollisionBoxes();
        return box.maxX - box.minX;
    }
}
