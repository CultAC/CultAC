package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaInsideBlockEffects;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * LivingEntity#handleRelativeFrictionAndCalculateMovement climbs a jumping player in leather boots when
 * Entity#wasInPowderSnow holds: the powder snow the previous tick's Entity#applyEffectsFromBlocks entered.
 */
class JavaPowderSnowClimbTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final double TOP = FLOOR_Y + 1;
    /** The heights a 26.3 client in leather boots reported jumping out of powder snow it stood in. */
    private static final double[] RECORDED_CLIMB = {
        64.62999998033047, 64.8063999837637, 64.98279998719693, 65.15919999063016, 65.27679999291898, 65.3943999952078
    };

    @Test
    void jumpingOutOfPowderSnowInLeatherBootsStaysClean() throws Exception {
        try (var f = fixture(true)) {
            Client client = new Client(true, FLOOR_Y, true);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), new Vec3(0, client.vy, 0));
            f.input(NONE);
            run(f, client, NONE, false, 5);
            f.input(JUMP);
            double start = client.y;
            for (int tick = 0; tick < RECORDED_CLIMB.length; tick++) {
                step(f, client, JUMP, false, tick);
                assertEquals(RECORDED_CLIMB[tick] - FLOOR_Y, client.y - start, 1.0E-9, "tick " + tick);
            }
            run(f, client, JUMP, false, 1);
            f.input(NONE);
            run(f, client, NONE, false, 30);
            assertEquals(TOP, client.y);
        }
    }

    @Test
    void climbingATickAfterThePowderSnowIsRejected() throws Exception {
        try (var f = fixture(true)) {
            Client client = new Client(true, FLOOR_Y, true);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), new Vec3(0, client.vy, 0));
            f.input(NONE);
            run(f, client, NONE, false, 5);
            f.input(JUMP);
            run(f, client, JUMP, false, RECORDED_CLIMB.length);
            double climbed = client.y + (0.2 - 0.08) * 0.98F;
            assertRejected(f.tick(new Vec3(0.5, climbed, 0.5), false), "VerticalOffset");
        }
    }

    @Test
    void jumpingInPowderSnowWithoutLeatherBootsStaysClean() throws Exception {
        try (var f = fixture(false)) {
            Client client = new Client(false, FLOOR_Y, true);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), new Vec3(0, client.vy, 0));
            f.input(NONE);
            run(f, client, NONE, false, 5);
            f.input(JUMP);
            run(f, client, JUMP, false, 4);
            f.input(NONE);
            run(f, client, NONE, false, 20);
            assertEquals(FLOOR_Y, client.y);
        }
    }

    @Test
    void climbingInPowderSnowWithoutLeatherBootsIsRejected() throws Exception {
        try (var f = fixture(false)) {
            Client client = new Client(false, FLOOR_Y, true);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), new Vec3(0, client.vy, 0));
            f.input(NONE);
            run(f, client, NONE, false, 5);
            f.input(JUMP);
            run(f, client, JUMP, false, 1);
            assertRejected(f.tick(new Vec3(0.5, RECORDED_CLIMB[1], 0.5), false), "VerticalOffset");
        }
    }

    /** Past 2.5 blocks PowderSnowBlock#getCollisionShape is the 0.9 high falling shape the player lands on. */
    @Test
    void fallingIntoPowderSnowInLeatherBootsStaysClean() throws Exception {
        try (var f = fixture(true)) {
            Client client = new Client(true, FLOOR_Y + 6, false);
            f.place(new Vec3(0.5, client.y, 0.5), Vec3.ZERO);
            f.input(NONE);
            run(f, client, NONE, false, 40);
            assertEquals(FLOOR_Y, client.y);
        }
    }

    @Test
    void landingOnPowderSnowInLeatherBootsStaysClean() throws Exception {
        try (var f = fixture(true)) {
            Client client = new Client(true, TOP + 2, false);
            f.place(new Vec3(0.5, client.y, 0.5), Vec3.ZERO);
            f.input(NONE);
            run(f, client, NONE, false, 30);
            assertEquals(TOP, client.y);
        }
    }

    @Test
    void sneakingThroughPowderSnowInLeatherBootsStaysClean() throws Exception {
        try (var f = fixture(true)) {
            Client client = new Client(true, TOP + 2, false);
            f.place(new Vec3(0.5, client.y, 0.5), Vec3.ZERO);
            f.input(NONE);
            run(f, client, NONE, false, 30);
            f.player.isSneaking = true;
            f.player.refreshPlayerPose();
            f.input(SNEAK);
            run(f, client, SNEAK, true, 30);
            assertEquals(FLOOR_Y, client.y);
        }
    }

    /**
     * CobwebBlock#entityInside resets the fall distance before the powder snow below is visited in the same
     * Entity#checkInsideBlocks pass, so PowderSnowBlock#getEntityInsideCollisionShape is the full block there.
     */
    @Test
    void fallingThroughACobwebIntoPowderSnowEntersIt() throws Exception {
        try (var f = fixture(true)) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 1, 0), BlockIds.COBWEB.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            double end = FLOOR_Y + 0.95;
            double fallen = 0;
            double vy = 0;
            int ticks = 0;
            while (true) {
                ticks++;
                double movement = Math.abs(vy) < 0.003 ? 0 : vy;
                vy = (movement - 0.08) * 0.98F;
                fallen -= movement;
                if (-movement >= 1.1) break;
            }
            Client client = new Client(true, end + fallen, false);
            f.place(new Vec3(0.5, client.y, 0.5), Vec3.ZERO);
            f.input(NONE);
            run(f, client, NONE, false, ticks);
            assertEquals(end, client.y, 1.0E-9);
            PredictionResult crossing = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertEquals(Boolean.TRUE, JavaInsideBlockEffects.enteredPowderSnow(f.player, crossing));
        }
    }

    private static JavaTickFixture fixture(boolean boots) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        if (boots) {
            f.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.SLOT_BOOTS, new SimItemStack(ItemTypes.LEATHER_BOOTS, 1, Components.EMPTY));
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.POWDER_SNOW.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return f;
    }

    private static void run(JavaTickFixture f, Client client, ServerboundPlayerInput input, boolean sneak, int ticks) {
        for (int tick = 0; tick < ticks; tick++) step(f, client, input, sneak, tick);
    }

    private static void step(JavaTickFixture f, Client client, ServerboundPlayerInput input, boolean sneak, int tick) {
        double before = client.y;
        boolean groundBefore = client.onGround;
        client.tick(input.jump(), sneak);
        PredictionResult result;
        if (client.y == before && client.onGround == groundBefore) {
            f.tickEnd();
            result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        } else {
            result = f.tick(new Vec3(0.5, client.y, 0.5), client.onGround);
        }
        assertTrue(
                result.getFlags().isEmpty() && !result.isExempt(),
                "tick " + tick + " at " + client.y + ": " + describe(result));
    }

    /** A 26.3 LocalPlayer moving only vertically in the powder snow column at x = z = 0.5. */
    private static final class Client {
        final boolean boots;
        double y;
        double vy;
        boolean onGround;
        double fallDistance;
        boolean stuck;
        boolean inPowderSnow;

        Client(boolean boots, double y, boolean onGround) {
            this.boots = boots;
            this.y = y;
            this.onGround = onGround;
            this.vy = onGround ? (0.0 - 0.08) * 0.98F : 0.0;
            this.inPowderSnow = y < TOP;
            this.stuck = y < TOP;
        }

        void tick(boolean jumping, boolean descending) {
            boolean wasInPowderSnow = inPowderSnow;
            if (Math.abs(vy) < 0.003) vy = 0;
            if (jumping && onGround) vy = Math.max(0.42F, vy);
            double movement = vy;
            if (stuck) {
                movement *= 1.5;
                vy = 0;
                stuck = false;
            }
            double floor = FLOOR_Y;
            if (fallDistance > 2.5) {
                floor = FLOOR_Y + (double) 0.9F;
            } else if (boots && !descending && y > TOP - (double) 1.0E-5F) {
                floor = TOP;
            }
            double to = y + movement;
            boolean landed = false;
            if (movement < 0 && y >= floor && to < floor) {
                to = floor;
                landed = true;
            } else if (movement < 0 && to < FLOOR_Y) {
                to = FLOOR_Y;
                landed = true;
            }
            onGround = landed;
            if (landed) {
                fallDistance = 0;
                vy = 0;
            } else if (to < y) {
                fallDistance -= to - y;
            }
            if (jumping && wasInPowderSnow && boots) vy = 0.2;
            vy = (vy - 0.08) * 0.98F;
            double low = Math.min(y, to);
            double shapeTop = fallDistance > 2.5 ? FLOOR_Y + (double) 0.9F : TOP;
            inPowderSnow = low + 1.0E-5F < TOP && low < shapeTop;
            stuck = inPowderSnow && to < TOP;
            if (stuck) fallDistance = 0;
            y = to;
        }
    }
}
