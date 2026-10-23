package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.WorldPackets.TimeUpdate;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ClientLevel#tickEntities runs Camel#tick for every camel, so a camel's 55-tick dash cooldown runs down while nobody
 * rides it (CultAC issue #96, fourth capture). Camel#getRiddenSpeed adds the sprint bonus once the cooldown is zero.
 */
class CamelDashCooldownTest {
    private static final int CAMEL = 124;
    private static final long GAME_TIME = 1090559L;
    /** Camel DASH and LAST_POSE_CHANGE_TICK in the 26.3 metadata layout. */
    private static final int DASH = 19;

    private static final int LAST_POSE_CHANGE_TICK = 20;
    /** Camel#executeRidersJump and Camel#onSyncedDataUpdated's dash cooldown. */
    private static final int DASH_COOLDOWN = 55;

    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 ON_FOOT = new Vec3(4.5, FLOOR_Y, 0.5);
    /** Camel#getRiddenSpeed: movement_speed 0.09 plus 0.1 for a sprinting controller with no dash cooldown. */
    private static final float SPEED = 0.09F + 0.1F;
    /** LivingEntity#travelInAir: a grounded camel's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;

    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    /** LivingEntity#moveRelative for the ridden input's 0.98F on 0.6-friction ground. */
    private static final double GROUND_INPUT = (double) 0.98F * (SPEED * (0.21600002F / (0.6F * 0.6F * 0.6F)));
    /** The ridden input with Camel#getRiddenSpeed's movement_speed alone. */
    private static final double WALK_INPUT = (double) 0.98F * (0.09F * (0.21600002F / (0.6F * 0.6F * 0.6F)));

    private static final ServerboundPlayerInput IDLE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    @Test
    void anUnriddenCamelsDashCooldownRunsDown() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
            f.place(ON_FOOT, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, START, 0, 0, 0);
            PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
            camel.hasSaddle = true;
            camel.onGround = true;
            metadata(f, false);
            assertExact(f.tick(ON_FOOT, true));

            metadata(f, true);
            for (int tick = 0; tick < DASH_COOLDOWN; tick++) assertExact(f.tick(ON_FOOT, true));

            mount(f);
            double velocity = GROUND_INPUT;
            double z = START.z + velocity;
            assertExact(ride(f, new Vec3(START.x, START.y, z)));
            velocity = velocity * GROUND_FRICTION + GROUND_INPUT;
            z += velocity;
            assertExact(ride(f, new Vec3(START.x, START.y, z)));
        }
    }

    /**
     * SynchedEntityData#assignValues calls Camel#onSyncedDataUpdated for every entry it receives, changed or not: a
     * DASH entry repeating the camel's state still sets the spent cooldown to 55, and the ridden tick takes no bonus.
     */
    @Test
    void aRepeatedDashEntrySetsTheSpentCooldown() throws Exception {
        try (var f = new JavaTickFixture()) {
            spawn(f);
            metadata(f, false);
            assertExact(f.tick(ON_FOOT, true));

            mount(f);
            metadata(f, false);
            assertExact(ride(f, new Vec3(START.x, START.y, START.z + WALK_INPUT)));
        }
    }

    /** Camel#onSyncedDataUpdated skips DASH on the camel's first tick: the spawn's entries set no cooldown. */
    @Test
    void aDashEntryBeforeTheFirstTickSetsNoCooldown() throws Exception {
        try (var f = new JavaTickFixture()) {
            spawn(f);
            metadata(f, true);
            assertExact(f.tick(ON_FOOT, true));

            mount(f);
            assertExact(ride(f, new Vec3(START.x, START.y, START.z + GROUND_INPUT)));
        }
    }

    /**
     * Camel#getRiddenSpeed reads only the dash cooldown: a camel still dashing, spawned mid-dash and not yet landed,
     * gives a sprinting rider the bonus once the cooldown is zero.
     */
    @Test
    void aDashingCamelWithASpentCooldownGivesTheBonus() throws Exception {
        try (var f = new JavaTickFixture()) {
            spawn(f);
            PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
            camel.onGround = false;
            metadata(f, true);
            assertExact(f.tick(ON_FOOT, true));

            mount(f);
            assertTrue(camel.dashing && camel.dashCooldown == 0, "the camel is not dashing with a spent cooldown");
            assertExact(ride(f, new Vec3(START.x, START.y, START.z + GROUND_INPUT)));
        }
    }

    /**
     * The rider's own dash arms the cooldown, the rider dismounts before it runs out and the camel's client ticks run
     * it out while nobody rides it: the first ridden tick after the remount carries the sprint bonus.
     */
    @Test
    void aCooldownLeftAtTheDismountRunsOutBeforeTheRemount() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
            f.place(ON_FOOT, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, START, 0, 0, 0);
            PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
            camel.hasSaddle = true;
            camel.onGround = true;
            metadata(f, false);
            assertExact(f.tick(ON_FOOT, true));

            board(f, IDLE);
            command(f, PlayerCommandAction.START_JUMPING_WITH_HORSE, 0);
            // LocalPlayer#aiStep sends the riding jump after the camel's tick, so it arms the next one.
            assertExact(ride(f, START, true));
            Vec3 landed = dash(f, START);

            List<Integer> teleports = teleportIds(f);
            f.setPassengers(CAMEL, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());
            acceptNewTeleports(f, teleports);
            Vec3 dismounted = new Vec3(f.player.x, f.player.y, f.player.z);
            for (int tick = 0; tick < DASH_COOLDOWN; tick++) assertExact(f.tick(dismounted, true));

            board(f, FORWARD_SPRINT);
            command(f, PlayerCommandAction.START_SPRINTING, 0);
            assertExact(ride(f, new Vec3(landed.x, landed.y, landed.z + GROUND_INPUT), true));
        }
    }

    /**
     * Camel#executeRidersJump at a tapped charge, AbstractHorse#onPlayerJump's 0.4 scale, from rest facing +z, then
     * LivingEntity#travelInAir with no ridden input until the camel lands and slows; the camel's resting position.
     */
    private static Vec3 dash(JavaTickFixture f, Vec3 from) {
        final float amount = 0.4F;
        double y = from.y;
        double z = from.z;
        double vy = GROUNDED_Y + 1.4285F * amount * (double) 0.42F;
        double vz = 22.2222F * amount * (double) 0.09F;
        boolean onGround = true;
        for (int tick = 0; tick < 12; tick++) {
            if (Math.abs(vy) < 0.003) vy = 0.0;
            if (Math.abs(vz) < 0.003) vz = 0.0;
            double friction = (onGround ? 0.6F : 1.0F) * 0.91F;
            z += vz;
            y += vy;
            onGround = y <= from.y;
            if (onGround) {
                y = from.y;
                vy = 0.0;
            }
            assertExact(ride(f, new Vec3(from.x, y, z), onGround));
            vz *= friction;
            vy = (vy - 0.08) * 0.98F;
        }
        return new Vec3(from.x, y, z);
    }

    /** The saddled, grounded camel at {@code START} beside the rider on foot. */
    private static void spawn(JavaTickFixture f) {
        f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
        f.place(ON_FOOT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, START, 0, 0, 0);
        PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
        camel.hasSaddle = true;
        camel.onGround = true;
    }

    /** The server's camel DASH and a standing LAST_POSE_CHANGE_TICK, delivered. */
    private static void metadata(JavaTickFixture f, boolean dashing) {
        f.player.compensatedEntities.updateEntityMetadata(
                CAMEL,
                List.of(
                        new EntityMetadata.Entry(DASH, dashing, ByteBuffer.allocate(0)),
                        new EntityMetadata.Entry(LAST_POSE_CHANGE_TICK, GAME_TIME - 1000L, ByteBuffer.allocate(0))));
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** The rider boards the still camel holding forward and sprint. */
    private static void mount(JavaTickFixture f) {
        f.setPassengers(CAMEL, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(FORWARD_SPRINT);
        command(f, PlayerCommandAction.START_SPRINTING, 0);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0.0, GROUNDED_Y, 0.0));
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    /** The rider boards the still camel holding {@code keys}. */
    private static void board(JavaTickFixture f, ServerboundPlayerInput keys) {
        f.setPassengers(CAMEL, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(keys);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0.0, GROUNDED_Y, 0.0));
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action, int data) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, data);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }

    private static void acceptNewTeleports(JavaTickFixture f, List<Integer> before) {
        for (TeleportData teleport : f.player.getSetbackTeleportUtil().pendingTeleports) {
            if (before.contains(teleport.getTeleportId())) continue;
            Vec3 target = teleport.getLocation();
            var packet = new ServerboundAcceptTeleportation(
                    teleport.getTeleportId(),
                    new Vec3d(target.x, target.y, target.z),
                    teleport.getFinalYaw(),
                    teleport.getFinalPitch());
            new PacketServerTeleport()
                    .onAcceptTeleportation(f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet), f.player, packet);
        }
    }

    private static List<Integer> teleportIds(JavaTickFixture f) {
        List<Integer> ids = new ArrayList<>();
        for (TeleportData teleport : f.player.getSetbackTeleportUtil().pendingTeleports) {
            ids.add(teleport.getTeleportId());
        }
        return ids;
    }

    /** One client tick facing +z: the rider's Rot, the camel's grounded move and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to) {
        return ride(f, to, true);
    }

    private static PredictionResult ride(JavaTickFixture f, Vec3 to, boolean onGround) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, 0F, 0F, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), 0F, 0F, onGround, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }

    private static void assertExact(PredictionResult result) {
        StringBuilder report = new StringBuilder(describe(result));
        for (PredictionResult.Flag flag : result.getFlags()) {
            report.append(' ').append(flag.getVerbose().getString());
        }
        assertTrue(exact(result), report.toString());
    }
}
