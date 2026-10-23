package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketPlayerDigging;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A riptide spin through a living entity. LivingEntity#aiStep runs LivingEntity#checkAutoSpinAttack after
 * LivingEntity#travel while autoSpinAttackTicks is positive; the first LivingEntity that Level#getEntities finds in the
 * box spanning the player's boxes before and after the travel scales the stored velocity by -0.2 and sets
 * autoSpinAttackTicks to 0.
 */
class JavaRiptideSpinAttackTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 START = new Vec3(-4.5, FLOOR_Y + 1, 0.5);
    private static final Vec3 SPIN = new Vec3(1.5, 0, 0);
    private static final Vec3 BELOW_THE_PATH = START.add(new Vec3(1.0, -1, 0));
    private static final Vec3 SHALLOWS = new Vec3(0.5, FLOOR_Y, 0.5);
    /** LivingEntity#travelInFluid's gravity on a player resting on the floor of the water. */
    private static final Vec3 SETTLED = new Vec3(0, -0.005, 0);

    private static final float LIFT = 1.1999999F;

    @ParameterizedTest
    @CsvSource({
        "ARMOR_STAND, 10, true",
        "ARMOR_STAND, 1, true",
        "ZOMBIE, 10, true",
        "ZOMBIE, 1, true",
        "EXPERIENCE_ORB, 10, false"
    })
    void theSpinReboundsOffALivingEntity(String type, int spinTicks, boolean living) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(71, typeId(type), BELOW_THE_PATH, 0, 0, 0);
            f.player.riptideSpinAttackTicks = spinTicks;
            PredictionResult rebound = spinThenRebound(f);
            assertFalse(rebound.isExempt(), describe(rebound));
            assertEquals(living, exact(rebound), describe(rebound));
        }
    }

    /** EntitySelector#NO_SPECTATORS keeps a spectator out of the search. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aSpectatorTakesNoHit(boolean spectator) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.addRemotePlayer(71, BELOW_THE_PATH, spectator ? GameMode.SPECTATOR : GameMode.SURVIVAL);
            f.player.riptideSpinAttackTicks = 10;
            PredictionResult rebound = spinThenRebound(f);
            assertFalse(rebound.isExempt(), describe(rebound));
            assertEquals(!spectator, exact(rebound), describe(rebound));
        }
    }

    /** The box before the travel is the player's at its scale. */
    @Test
    void theSearchSpansTheScaledBoxBeforeTheTravel() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.getSelf().scale = 2.0F;
            f.player.refreshPlayerPose();
            // The armor stand's 0.5 wide box ends 0.45 behind the player's center, inside its 1.2 wide box.
            f.player.compensatedEntities.addEntity(
                    71, EntityTypeIds.ARMOR_STAND, START.add(new Vec3(-0.7, -1, 0)), 0, 0, 0);
            f.player.riptideSpinAttackTicks = 10;
            PredictionResult rebound = spinThenRebound(f);
            assertTrue(exact(rebound), describe(rebound));
        }
    }

    /** AABB#intersects is strict, so an entity whose box only touches the player's takes no hit. */
    @Test
    void aBoxThatOnlyTouchesTakesNoHit() throws Exception {
        try (var f = new JavaTickFixture()) {
            double playerMinX = START.x - 0.3F;
            f.player.compensatedEntities.addEntity(
                    71, EntityTypeIds.ARMOR_STAND, new Vec3(playerMinX - 0.25F, START.y - 1, START.z), 0, 0, 0);
            f.player.riptideSpinAttackTicks = 10;
            PredictionResult rebound = spinThenRebound(f);
            assertFalse(rebound.isExempt(), describe(rebound));
            assertFalse(exact(rebound), describe(rebound));
        }
    }

    /** After the hit autoSpinAttackTicks is 0, so the next tick searches no box and the spin rebounds once. */
    @Test
    void aHitEndsTheSpin() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(71, EntityTypeIds.ARMOR_STAND, BELOW_THE_PATH, 0, 0, 0);
            f.player.riptideSpinAttackTicks = 10;
            PredictionResult rebound = spinThenRebound(f);
            assertTrue(exact(rebound), describe(rebound));

            Vec3 reboundMove = drag(SPIN).multiply(-0.2, -0.2, -0.2);
            Vec3 there = START.add(SPIN).add(reboundMove);
            PredictionResult again = f.tick(there.add(drag(reboundMove).multiply(-0.2, -0.2, -0.2)), false);
            assertFalse(again.isExempt(), describe(again));
            assertFalse(exact(again), describe(again));
        }
    }

    /**
     * A horizontal collision leaves autoSpinAttackTicks alone while Level#getEntities finds any entity, here an
     * experience orb, so a living entity met afterwards still takes the hit.
     */
    @Test
    void aLivingEntityMetAfterAWallStillTakesTheHit() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 2; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(-3, y, 0), BlockIds.STONE.defaultState());
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.player.compensatedEntities.addEntity(
                    72, EntityTypeIds.EXPERIENCE_ORB, new Vec3(-4.0, START.y, START.z), 0, 0, 0);
            f.player.riptideSpinAttackTicks = 10;
            f.place(START, SPIN);
            f.input(NONE);
            Vec3 atTheWall = new Vec3(-3.3, START.y, START.z);
            PredictionResult spun = f.tick(atTheWall, false);
            assertTrue(exact(spun), describe(spun));

            Vec3 fall = drag(new Vec3(0, 0, 0));
            Vec3 there = atTheWall.add(fall);
            f.player.compensatedEntities.addEntity(71, EntityTypeIds.ARMOR_STAND, there, 0, 0, 0);
            PredictionResult fell = f.tick(there, false);
            assertTrue(exact(fell), describe(fell));

            PredictionResult rebound = f.tick(there.add(drag(fall).multiply(-0.2, -0.2, -0.2)), false);
            assertTrue(exact(rebound), describe(rebound));
        }
    }

    /**
     * An experience orb in the box keeps the spin past the wall. LivingEntity#checkAutoSpinAttack stops sprinting after
     * the travel only through Player#causeExtraKnockback, which Player#attack reaches only when the hit hurts the
     * target, and RemotePlayer is the one LivingEntity whose Entity#hurtClient returns true. With no remote player in
     * its box, the travel ran with the sprint flag the client sends.
     */
    @ParameterizedTest
    @ValueSource(strings = {"NONE", "ARMOR_STAND", "PLAYER"})
    void onlyAHitOnARemotePlayerStopsTheSprint(String inReach) throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_3);
            for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(-3, y, 0), BlockIds.STONE.defaultState());
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.player.compensatedEntities.addEntity(
                    72, EntityTypeIds.EXPERIENCE_ORB, new Vec3(-4.0, FLOOR_Y, 0.5), 0, 0, 0);
            f.player.riptideSpinAttackTicks = 10;
            f.place(new Vec3(-4.5, FLOOR_Y, 0.5), SPIN);
            f.input(NONE);
            f.player.xRot = 0.0F;
            f.player.yRot = 0.0F;
            Vec3 atTheWall = new Vec3(-3.3, FLOOR_Y, 0.5);
            PredictionResult hit = f.tick(atTheWall, true);
            assertTrue(exact(hit), describe(hit));

            Vec3 beside = atTheWall.add(new Vec3(0, 0, 0.6));
            if (inReach.equals("PLAYER")) {
                f.addRemotePlayer(71, beside, GameMode.SURVIVAL);
            } else if (inReach.equals("ARMOR_STAND")) {
                f.player.compensatedEntities.addEntity(71, EntityTypeIds.ARMOR_STAND, beside, 0, 0, 0);
            }
            f.input(new ServerboundPlayerInput(true, false, false, false, false, false, false));
            // LivingEntity#getFrictionInfluencedSpeed at the sprinting speed on a 0.6F block, then the 0.98F input.
            float slipperiness = 0.6F;
            float speed = 0.13F * (0.21600002F / (slipperiness * slipperiness * slipperiness));
            PredictionResult walk = f.tick(atTheWall.add(new Vec3(0, 0, 0.98F * speed)), true);
            if (inReach.equals("PLAYER")) {
                assertTrue(exact(walk), describe(walk));
            } else {
                assertFalse(walk.isExempt(), describe(walk));
                assertFalse(exact(walk), describe(walk));
            }
        }
    }

    /**
     * After the hit autoSpinAttackTicks is 0, so the rebound's tick runs no LivingEntity#checkAutoSpinAttack and travels
     * with the sprint flag the client sent.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theReboundTravelsWithTheSentSprintFlag(boolean sprintSpeed) throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_3);
            f.player.compensatedEntities.addEntity(71, EntityTypeIds.ARMOR_STAND, BELOW_THE_PATH, 0, 0, 0);
            f.player.riptideSpinAttackTicks = 10;
            f.player.xRot = 0.0F;
            f.player.yRot = 0.0F;
            f.place(START, SPIN);
            f.input(NONE);
            Vec3 through = START.add(SPIN);
            PredictionResult spun = f.tick(through, false);
            assertTrue(exact(spun), describe(spun));

            f.input(new ServerboundPlayerInput(true, false, false, false, false, false, false));
            // Player#getFlyingSpeed with the sprint flag off, or the 0.025999999F it gives with the flag set.
            float speed = sprintSpeed ? 0.025999999F : 0.02F;
            Vec3 rebound = drag(SPIN).multiply(-0.2, -0.2, -0.2).add(new Vec3(0, 0, 0.98F * speed));
            PredictionResult result = f.tick(through.add(rebound), false);
            if (sprintSpeed) {
                assertFalse(result.isExempt(), describe(result));
                assertFalse(exact(result), describe(result));
            } else {
                assertTrue(exact(result), describe(result));
            }
        }
    }

    /**
     * Player#causeExtraKnockback damps the attacker's X/Z by 0.6 before the -0.2 rebound, and Player#attack reaches it
     * only for a RemotePlayer, whose Entity#hurtClient alone returns true.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyARemotePlayerDampsTheRebound(boolean player) throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_3);
            if (player) {
                f.addRemotePlayer(71, BELOW_THE_PATH, GameMode.SURVIVAL);
            } else {
                f.player.compensatedEntities.addEntity(71, EntityTypeIds.ARMOR_STAND, BELOW_THE_PATH, 0, 0, 0);
            }
            f.player.riptideSpinAttackTicks = 10;
            f.player.isSprinting = true;
            f.place(START, SPIN);
            f.input(NONE);
            Vec3 through = START.add(SPIN);
            PredictionResult spun = f.tick(through, false);
            assertTrue(exact(spun), describe(spun));

            Vec3 carried = drag(SPIN);
            Vec3 damped = new Vec3(carried.x * 0.6, carried.y, carried.z * 0.6);
            PredictionResult rebound = f.tick(through.add(damped.multiply(-0.2, -0.2, -0.2)), false);
            if (player) {
                assertTrue(exact(rebound), describe(rebound));
            } else {
                assertFalse(rebound.isExempt(), describe(rebound));
                assertFalse(exact(rebound), describe(rebound));
            }
        }
    }

    /**
     * A spin the client certainly runs, through a LivingEntity at rest in a box the travel certainly crosses, certainly
     * hits it, so the next tick starts from the rebound.
     */
    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void aCertainHitRebounds(boolean certain, boolean rebound) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(71, EntityTypeIds.ARMOR_STAND, BELOW_THE_PATH, 0, 0, 0);
            f.player.riptideSpinAttackTicks = 10;
            f.player.riptideSpinCertain = certain;
            f.place(START, SPIN);
            f.input(NONE);
            Vec3 through = START.add(SPIN);
            PredictionResult spun = f.tick(through, false);
            assertTrue(exact(spun), describe(spun));

            Vec3 next = rebound ? drag(SPIN).multiply(-0.2, -0.2, -0.2) : drag(SPIN);
            PredictionResult result = f.tick(through.add(next), false);
            if (rebound || !certain) {
                assertTrue(exact(result), describe(result));
            } else {
                assertFalse(result.isExempt(), describe(result));
                assertFalse(exact(result), describe(result));
            }
        }
    }

    /** A small armor stand's box ends under the path and a marker's is empty, so the spin passes the stand. */
    @ParameterizedTest
    @ValueSource(bytes = {1, 16})
    void aSpinPastASmallOrMarkerStandContinues(byte flags) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(71, EntityTypeIds.ARMOR_STAND, BELOW_THE_PATH, 0, 0, 0);
            f.player.compensatedEntities.updateEntityMetadata(
                    71,
                    List.of(new EntityMetadata.Entry(15, flags, ByteBuffer.allocate(0))),
                    f.player.lastTransactionReceived.get());
            PredictionResult result = certainSpinThenDrag(f);
            assertTrue(exact(result), describe(result));
        }
    }

    /**
     * The client sizes a calf by the entity data it has applied, and a calf's box ends under the path, so the spin
     * passes over the calf until the client applies its growth.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSpinPassesOverACalfUntilTheClientAppliesItsGrowth(boolean applied) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(71, EntityTypeIds.COW, BELOW_THE_PATH, 0, 0, 0);
            f.player.compensatedEntities.updateEntityMetadata(
                    71,
                    List.of(new EntityMetadata.Entry(16, true, ByteBuffer.allocate(0))),
                    f.player.lastTransactionReceived.get());
            f.player.compensatedEntities.updateEntityMetadata(
                    71,
                    List.of(new EntityMetadata.Entry(16, false, ByteBuffer.allocate(0))),
                    applied ? f.player.lastTransactionReceived.get() : Integer.MAX_VALUE);
            PredictionResult result = certainSpinThenDrag(f);
            if (applied) {
                assertFalse(result.isExempt(), describe(result));
                assertFalse(exact(result), describe(result));
            } else {
                assertTrue(exact(result), describe(result));
            }
        }
    }

    /**
     * Entity#rideTick runs LivingEntity#aiStep, whose LivingEntity#checkAutoSpinAttack finds the mount in the
     * passenger's box and ends the spin, so after the ride the spin takes no hit.
     */
    @Test
    void aSpinEndsOnTheMount() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 seat = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(seat, Vec3.ZERO);
            f.input(NONE);
            f.player.riptideSpinAttackTicks = 15;
            f.player.riptideSpinCertain = true;
            f.player.compensatedEntities.addEntity(90, EntityTypeIds.PIG, seat, 0, 0, 0);
            f.setPassengers(90, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            for (int tick = 0; tick < 3; tick++) f.rideTick();
            f.setPassengers(90, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());

            f.player.compensatedEntities.addEntity(71, EntityTypeIds.ARMOR_STAND, BELOW_THE_PATH, 0, 0, 0);
            f.place(START, SPIN);
            f.input(NONE);
            Vec3 through = START.add(SPIN);
            PredictionResult spun = f.tick(through, false);
            assertTrue(exact(spun), describe(spun));

            PredictionResult result = f.tick(through.add(drag(SPIN)), false);
            assertTrue(exact(result), describe(result));
        }
    }

    /**
     * TridentItem#releaseUsing lifts a player on the ground before LivingEntity#aiStep takes the box before the travel,
     * so an entity under the lifted box takes no hit and the spin continues.
     */
    @Test
    void aReleaseOnTheGroundMissesAnEntityUnderTheLift() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(71, EntityTypeIds.ARMOR_STAND, SHALLOWS.add(0, -1, 0), 0, 0, 0);
            Vec3 pushed = releaseOnTheGround(f, -10.0F);
            Vec3 lifted = SHALLOWS.add(0, LIFT, 0).add(pushed);
            PredictionResult released = f.tick(lifted, false);
            assertTrue(exact(released), describe(released));

            PredictionResult result = f.tick(lifted.add(drag(pushed)), false);
            assertTrue(exact(result), describe(result));
        }
    }

    /**
     * The box before the travel of a release on the ground is the lifted one, so the spin hits an entity over the box
     * the player released from and the box it lands in.
     */
    @Test
    void aReleaseOnTheGroundHitsAnEntityInTheLiftedBox() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(71, EntityTypeIds.ARMOR_STAND, SHALLOWS.add(0, 2.0, 0), 0, 0, 0);
            Vec3 pushed = releaseOnTheGround(f, 30.0F);
            Vec3 landed = new Vec3(SHALLOWS.x, FLOOR_Y, SHALLOWS.z + pushed.z);
            PredictionResult released = f.tick(landed, true);
            assertTrue(exact(released), describe(released));

            Vec3 stored = new Vec3(0, (0 - 0.08) * 0.98F, pushed.z * 0.91F);
            PredictionResult result = f.tick(landed.add(stored.multiply(-0.2, -0.2, -0.2)), false);
            assertTrue(exact(result), describe(result));
        }
    }

    /** A respawn or a dimension change places a new LocalPlayer, which has no spin, with a teleport. */
    @Test
    void aRespawnTeleportPlacesAClientWithoutASpin() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(71, EntityTypeIds.ARMOR_STAND, BELOW_THE_PATH, 0, 0, 0);
            f.player.riptideSpinAttackTicks = 10;
            f.player.riptideSpinCertain = true;
            f.teleport(START, new RelativeFlag(0));
            f.place(START, SPIN);
            f.input(NONE);
            Vec3 through = START.add(SPIN);
            PredictionResult spun = f.tick(through, false);
            assertTrue(exact(spun), describe(spun));

            PredictionResult result = f.tick(through.add(drag(SPIN)), false);
            assertTrue(exact(result), describe(result));
        }
    }

    /**
     * A riptide 3 trident used for eleven ticks standing in water at {@link #SHALLOWS}, then released at
     * {@code pitch} through PacketPlayerDigging; the velocity the release tick moves by.
     */
    private static Vec3 releaseOnTheGround(JavaTickFixture f, float pitch) {
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.WATER.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        SimItemStack trident = OfflineCultTestBootstrap.item("minecraft:trident");
        trident.setComponent("minecraft:enchantments", CanonicalSnbt.parse("{'minecraft:riptide':3}"));
        f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.HOTBAR_OFFSET, trident);
        f.place(SHALLOWS, SETTLED);
        f.input(NONE);
        var use = new ServerboundUseItem(Hand.MAIN_HAND, 1, 0, pitch);
        new PacketPlayerDigging().onUseItem(f.event(ServerboundPackets.USE_ITEM, use), f.player, use);
        for (int tick = 0; tick < 11; tick++) f.tick(SHALLOWS, true);
        f.player.xRot = 0.0F;
        f.player.yRot = pitch;
        var release =
                new ServerboundPlayerAction(PlayerAction.RELEASE_USE_ITEM, new BlockPos(0, 0, 0), Direction.DOWN, 0);
        new PacketPlayerDigging().onPlayerAction(f.event(ServerboundPackets.PLAYER_ACTION, release), f.player, release);
        assertTrue(f.player.riptideSpinCertain, "the release is not certain");
        return SETTLED.add(push(pitch));
    }

    /** TridentItem#releaseUsing at riptide 3, looking along +Z at {@code pitch}. */
    private static Vec3 push(float pitch) {
        float xRot = pitch * (float) (Math.PI / 180.0);
        float xd = -VanillaMath.sin(0.0F) * VanillaMath.cos(xRot);
        float yd = -VanillaMath.sin(xRot);
        float zd = VanillaMath.cos(0.0F) * VanillaMath.cos(xRot);
        float dist = (float) Math.sqrt(xd * xd + yd * yd + zd * zd);
        return new Vec3(xd * (3.0F / dist), yd * (3.0F / dist), zd * (3.0F / dist));
    }

    /** The spin through {@link #BELOW_THE_PATH}, then the tick after it with the stored velocity scaled by -0.2. */
    private static PredictionResult spinThenRebound(JavaTickFixture f) {
        f.place(START, SPIN);
        f.input(NONE);
        Vec3 through = START.add(SPIN);
        PredictionResult spun = f.tick(through, false);
        assertTrue(exact(spun), describe(spun));
        return f.tick(through.add(drag(SPIN).multiply(-0.2, -0.2, -0.2)), false);
    }

    /** A certain spin through {@link #BELOW_THE_PATH}, then the tick after it with the stored velocity. */
    private static PredictionResult certainSpinThenDrag(JavaTickFixture f) {
        f.player.riptideSpinAttackTicks = 10;
        f.player.riptideSpinCertain = true;
        f.place(START, SPIN);
        f.input(NONE);
        Vec3 through = START.add(SPIN);
        PredictionResult spun = f.tick(through, false);
        assertTrue(exact(spun), describe(spun));
        return f.tick(through.add(drag(SPIN)), false);
    }

    /** LivingEntity#travelInAir: the 0.08 gravity, then the 0.91F horizontal and 0.98F vertical drag. */
    private static Vec3 drag(Vec3 movement) {
        return new Vec3(movement.x * 0.91F, (movement.y - 0.08) * 0.98F, movement.z * 0.91F);
    }

    private static int typeId(String type) throws ReflectiveOperationException {
        return (int) EntityTypeIds.class.getField(type).get(null);
    }
}
