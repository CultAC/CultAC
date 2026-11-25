package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsQ;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.RidingJumpCharge;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * LocalPlayer#aiStep charges a riding jump while the jump key is held on a jumpable vehicle, jumpRidingScale rising by
 * 0.1 a tick to 1.0 on the tenth, and on the key's release sends START_RIDING_JUMP with Mth.floor(scale * 100).
 * AbstractHorse#onPlayerJump arms that jump for the horse's next tick. The engine arms the jump the server receives.
 */
class HorseJumpChargeTest {
    private static final int HORSE = 100;
    private static final int OTHER_HORSE = 101;
    private static final Vec3 GROUND = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final double JUMP_STRENGTH = 0.7;
    private static final Vec3 JUMP = new Vec3(0, JUMP_STRENGTH, 0);
    private static final double GROUNDED_Y = -0.0784000015258789;
    private static final double GRAVITY = 0.08D;
    private static final double VERTICAL_DRAG = 0.98F;
    /** CompensatedVehicleState's vehicle-switch window after a mount, in client ticks. */
    private static final int VEHICLE_SWITCH_TICKS = 5;
    private static final ServerboundPlayerInput JUMP_HELD =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final ServerboundPlayerInput RELEASED =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    static Stream<ClientVersion> clients() {
        return Stream.of(ClientVersion.V_1_21_2, ClientVersion.V_1_21_9, ClientVersion.V_26_3);
    }

    private static JavaTickFixture fixture(ClientVersion client) throws Exception {
        ProtocolVersion protocol = switch (client) {
            case V_1_21_2 -> ProtocolVersion.V1_21_3;
            case V_1_21_9 -> ProtocolVersion.V1_21_9;
            default -> ProtocolVersion.V26_3;
        };
        JavaTickFixture f = new JavaTickFixture(protocol, client);
        f.player.checkManager.getCheck(BadPacketsQ.class).setEnabled(true);
        f.place(GROUND, Vec3.ZERO);
        return f;
    }

    private static JavaTickFixture mount() throws Exception {
        return mount(ClientVersion.V_26_3);
    }

    private static JavaTickFixture mount(ClientVersion client) throws Exception {
        JavaTickFixture f = fixture(client);
        spawnHorse(f, HORSE);
        ride(f, HORSE);
        return f;
    }

    private static void spawnHorse(JavaTickFixture f, int id) {
        f.player.compensatedEntities.addEntity(id, EntityTypeIds.HORSE, GROUND, 0, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(id);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.jumpStrength = JUMP_STRENGTH;
        horse.onGround = true;
    }

    /** The server seats the player on {@code id}, the client answers, and its first tick ends. */
    private static void ride(JavaTickFixture f, int id) {
        f.setPassengers(id, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.pongThrough(f.player.sendTrailingTransaction());
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, GROUNDED_Y, 0));
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        ((PacketEntityHorse) f.player.compensatedEntities.getEntity(id)).onGround = true;
    }

    private static PacketEntityHorse horse(JavaTickFixture f) {
        return (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
    }

    private static double violations(JavaTickFixture f) {
        return f.player.checkManager.getCheck(BadPacketsQ.class).violations;
    }

    /** AbstractHorse#onPlayerJump's scale for {@code data}. */
    private static double scale(int data) {
        data = Math.max(data, 0);
        return data >= 90 ? 1.0F : 0.4F + 0.4F * data / 90.0F;
    }

    /** LocalPlayer#aiStep's release data after a press and {@code held} more ticks with the key held. */
    private static int chargeData(int held) {
        float scale = held < 10 ? held * 0.1F : 0.8F + 2.0F / (held - 9) * 0.1F;
        return (int) Math.floor(scale * 100.0F);
    }

    /** The START_RIDING_JUMP LocalPlayer#sendRidingJump sends, as the receive listener handles it. */
    private static PacketReceiveEvent<ServerboundPlayerCommand> release(JavaTickFixture f, int data) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_JUMPING_WITH_HORSE, data);
        var event = f.event(ServerboundPackets.PLAYER_COMMAND, packet);
        new PacketEntityAction().onPlayerCommand(event, f.player, packet);
        return event;
    }

    /**
     * A client tick in LocalPlayer#tick's order: the release from aiStep when {@code release} is not null, the keys
     * when {@code input} is not null, then the rider's Rot, the horse's move and the tick end.
     */
    private static PredictionResult tick(
            JavaTickFixture f, Vec3 to, boolean onGround, Integer release, ServerboundPlayerInput input) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        if (release != null) release(f, release);
        if (input != null) f.input(input);
        var rot = new ServerboundMovePlayer(0, 0, 0, 0, 0, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), 0, 0, onGround, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the horse's move was not predicted");
        return result;
    }

    private static void idle(JavaTickFixture f, int ticks) {
        for (int i = 0; i < ticks; i++) assertVehicleExact(tick(f, GROUND, true, null, null), i);
    }

    /** Presses jump and holds it {@code held} more ticks on the ground. */
    private static void charge(JavaTickFixture f, int held) {
        assertVehicleExact(tick(f, GROUND, true, null, JUMP_HELD), 0);
        for (int i = 0; i < held; i++) assertVehicleExact(tick(f, GROUND, true, null, null), i + 1);
    }

    private static void releaseArms(JavaTickFixture f, int sent, int charged) {
        release(f, sent);
        assertEquals(scale(charged), horse(f).nextHorseJump);
    }

    /**
     * The release tick on the ground, then the jump it arms: LivingEntity#travel moves by the jump's Y, then by gravity
     * and drag, until the horse lands.
     */
    private static void releaseAndJump(JavaTickFixture f, int data) {
        assertVehicleExact(tick(f, GROUND, true, data, RELEASED), 0);
        jump(f, data);
    }

    /** The jump a release of {@code data} armed, from the floor until the horse lands. */
    private static void jump(JavaTickFixture f, int data) {
        List<Vec3> path = jumpPath(f, (float) JUMP_STRENGTH * (float) scale(data));
        for (int i = 0; i < path.size(); i++) {
            Vec3 at = path.get(i);
            assertVehicleExact(tick(f, at, at.y == GROUND.y, null, null), i + 1);
        }
    }

    /**
     * Where a horse jumping from the floor with {@code impulse} is after each client tick, through the landing.
     * AbstractHorse#executeRidersJump sets the Y movement the jump tick travels; each later LivingEntity#aiStep first
     * scales the movement by 0.98 on a client before 1.21.5, where the horse is not Entity#isEffectiveAi, drops a Y
     * below 0.003, then travels and applies gravity and drag.
     */
    private static List<Vec3> jumpPath(JavaTickFixture f, double impulse) {
        boolean scaledBeforeTravel = f.player.getClientVersion().isOlderThan(ClientVersion.V_1_21_5);
        List<Vec3> path = new ArrayList<>();
        Vec3 at = GROUND;
        double motion = impulse;
        while (true) {
            if (at.y + motion <= GROUND.y) {
                path.add(GROUND);
                return path;
            }
            at = at.add(0, motion, 0);
            path.add(at);
            motion = (motion - GRAVITY) * VERTICAL_DRAG;
            if (scaledBeforeTravel) motion *= 0.98;
            if (Math.abs(motion) < 0.003) motion = 0.0;
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aReleaseWithoutAChargeLaunchesNoJump(ClientVersion client) throws Exception {
        try (JavaTickFixture f = mount(client)) {
            idle(f, 20);
            var sent = release(f, 100);
            assertTrue(sent.isCancelled(), "the server received the release");
            assertEquals(1, violations(f));
            PredictionResult jump = tick(f, GROUND.add(JUMP), false, null, null);
            assertFalse(jump.getFlags().isEmpty(), describe(jump));
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aFullyChargedReleaseJumpsFully(ClientVersion client) throws Exception {
        try (JavaTickFixture f = mount(client)) {
            idle(f, 20);
            charge(f, 10);
            assertVehicleExact(tick(f, GROUND, true, 100, RELEASED), 11);
            assertVehicleExact(tick(f, GROUND.add(JUMP), false, null, null), 12);
            assertEquals(0, violations(f));
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aTappedReleaseArmsItsShortCharge(ClientVersion client) throws Exception {
        for (int held = 0; held < 10; held++) {
            try (JavaTickFixture f = mount(client)) {
                idle(f, 20);
                charge(f, held);
                int data = chargeData(held);
                releaseArms(f, data, data);
                assertEquals(0, violations(f), "held " + held);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aChargeHeldPastItsPeakFallsBack(ClientVersion client) throws Exception {
        try (JavaTickFixture f = mount(client)) {
            idle(f, 20);
            charge(f, 15);
            int data = chargeData(15);
            releaseArms(f, data, data);
            assertEquals(0, violations(f));
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aReleaseClaimingMoreThanItsChargeArmsTheCharge(ClientVersion client) throws Exception {
        try (JavaTickFixture f = mount(client)) {
            idle(f, 20);
            charge(f, 2);
            var sent = release(f, 100);
            assertEquals(20, sent.getPacket().data(), "the server received the claimed charge");
            assertEquals(scale(20), horse(f).nextHorseJump);
            assertEquals(1, violations(f));
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aSecondReleaseNeedsANewPress(ClientVersion client) throws Exception {
        try (JavaTickFixture f = mount(client)) {
            idle(f, 20);
            charge(f, 10);
            assertVehicleExact(tick(f, GROUND, true, 100, RELEASED), 11);
            assertVehicleExact(tick(f, GROUND.add(JUMP), false, null, null), 12);
            release(f, 100);
            assertEquals(1, violations(f));
        }
    }

    /** A release is sent with the key up, so a key still reported held after it is a new press with no charge. */
    @ParameterizedTest
    @MethodSource("clients")
    void aReleaseWithTheKeyStillHeldStartsNoCharge(ClientVersion client) throws Exception {
        try (JavaTickFixture f = mount(client)) {
            idle(f, 20);
            charge(f, 10);
            assertVehicleExact(tick(f, GROUND, true, 100, null), 11);
            assertVehicleExact(tick(f, GROUND.add(JUMP), false, null, null), 12);
            releaseArms(f, 100, 0);
            assertEquals(1, violations(f));
        }
    }

    /** A press while the mount may still be in flight leaves the charge unknown until the next press. */
    @ParameterizedTest
    @MethodSource("clients")
    void aChargeBegunAsTheRiderMountsIsNotJudged(ClientVersion client) throws Exception {
        try (JavaTickFixture f = mount(client)) {
            assertVehicleExact(tick(f, GROUND, true, null, JUMP_HELD), 0);
            idle(f, 2);
            releaseArms(f, 100, 100);
            assertEquals(0, violations(f));
        }
    }

    /** With BadPacketsQ disabled or observing, the server receives the client's data, and the engine arms that. */
    @Test
    void anUnmodifiedReleaseArmsTheDataTheServerReceives() throws Exception {
        for (boolean observeOnly : new boolean[] {false, true}) {
            try (JavaTickFixture f = mount();
                    AutoCloseable observe = observeOnly ? JavaTickFixture.observeOnly() : () -> {}) {
                if (!observeOnly) f.player.checkManager.getCheck(BadPacketsQ.class).setEnabled(false);
                idle(f, 20);
                charge(f, 2);
                var claimed = release(f, 100);
                assertFalse(claimed.isCancelled(), "observe " + observeOnly);
                assertEquals(100, claimed.getPacket().data(), "observe " + observeOnly);
                assertEquals(scale(100), horse(f).nextHorseJump, "observe " + observeOnly);
                assertVehicleExact(tick(f, GROUND, true, null, RELEASED), 0);
                jump(f, 100);

                idle(f, 30);
                var unpressed = release(f, 100);
                assertFalse(unpressed.isCancelled(), "observe " + observeOnly);
                assertEquals(scale(100), horse(f).nextHorseJump, "observe " + observeOnly);
                assertVehicleExact(tick(f, GROUND, true, null, null), 0);
                jump(f, 100);
            }
        }
    }

    /** The charge does not need the ground: a key pressed in the air and held through the landing is released whole. */
    @ParameterizedTest
    @MethodSource("clients")
    void aChargeHeldInTheAirIsReleasedWholeAfterLanding(ClientVersion client) throws Exception {
        try (JavaTickFixture f = mount(client)) {
            idle(f, 20);
            charge(f, 10);
            assertVehicleExact(tick(f, GROUND, true, 100, RELEASED), 0);
            List<Vec3> path = new ArrayList<>(jumpPath(f, JUMP_STRENGTH));
            assertTrue(path.size() > 10, "air ticks " + path.size());
            path.add(GROUND);
            path.add(GROUND);
            // The key goes down on the third tick of the jump and stays down through the landing.
            int held = -1;
            for (int i = 0; i < path.size(); i++) {
                Vec3 at = path.get(i);
                assertVehicleExact(tick(f, at, at.y == GROUND.y, null, i == 2 ? JUMP_HELD : null), i + 1);
                if (i >= 2) held++;
            }
            releaseAndJump(f, chargeData(held));
            assertEquals(0, violations(f));
        }
    }

    /**
     * LocalPlayer keeps jumpRidingTicks across rides, so a key held from one horse onto the next continues its charge;
     * the engine cannot see the charge begin and does not judge its value.
     */
    @ParameterizedTest
    @MethodSource("clients")
    void aJumpKeyHeldOntoTheNextHorseIsNotJudged(ClientVersion client) throws Exception {
        try (JavaTickFixture f = mount(client)) {
            spawnHorse(f, OTHER_HORSE);
            idle(f, 20);
            charge(f, 3);
            f.setPassengers(HORSE, List.of());
            ride(f, OTHER_HORSE);
            int held = 3 + 1;
            for (int i = 0; i < VEHICLE_SWITCH_TICKS + 2; i++, held++) {
                assertVehicleExact(tick(f, GROUND, true, null, null), i);
            }
            releaseAndJump(f, chargeData(held));
            assertEquals(0, violations(f));

            idle(f, 10);
            charge(f, 2);
            release(f, 100);
            assertEquals(1, violations(f), "a new press is judged again");
        }
    }

    /**
     * Opening the horse's inventory sends OPEN_INVENTORY; the screen the server opens releases every key, and the
     * client's next tick releases the jump with its charge.
     */
    @ParameterizedTest
    @MethodSource("clients")
    void aScreenOpenedWhileChargingReleasesTheCharge(ClientVersion client) throws Exception {
        try (JavaTickFixture f = mount(client)) {
            idle(f, 20);
            charge(f, 4);
            f.command(PlayerCommandAction.OPEN_INVENTORY);
            assertVehicleExact(tick(f, GROUND, true, null, null), 5);
            releaseAndJump(f, chargeData(5));
            assertEquals(0, violations(f));
        }
    }

    /**
     * A saddle change reaches the client bundled with its ping, so every tick before that ping's answer still rides
     * the old saddle; after it, the control switch leaves the charge unknown.
     */
    @ParameterizedTest
    @MethodSource("clients")
    void aSaddleRemovedWhileChargingKeepsTheChargeUntilItsPing(ClientVersion client) throws Exception {
        try (JavaTickFixture f = mount(client)) {
            idle(f, 20);
            charge(f, 3);
            sendSaddle(f, SimItemStack.EMPTY);
            int pending = f.player.lastTransactionSent.get();
            assertTrue(f.player.lastTransactionReceived.get() < pending);
            assertVehicleExact(tick(f, GROUND, true, null, null), 4);
            assertVehicleExact(tick(f, GROUND, true, null, null), 5);
            releaseArms(f, chargeData(5), chargeData(5));
            assertEquals(0, violations(f));
            f.pongThrough(pending);
            assertFalse(horse(f).hasSaddle);
        }
    }

    /** A saddle put on while the key is held hands over control; the charge it continues is not judged. */
    @ParameterizedTest
    @MethodSource("clients")
    void aSaddlePutOnWhileTheKeyIsHeldIsNotJudged(ClientVersion client) throws Exception {
        try (JavaTickFixture f = fixture(client)) {
            spawnHorse(f, HORSE);
            horse(f).hasSaddle = false;
            ride(f, HORSE);
            f.input(JUMP_HELD);
            for (int i = 0; i < 8; i++) f.rideTick();
            sendSaddle(f, saddle());
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(horse(f).hasSaddle);
            for (int i = 0; i < 3; i++) assertVehicleExact(tick(f, GROUND, true, null, null), i);
            var sent = release(f, chargeData(3));
            assertFalse(sent.isCancelled());
            assertEquals(0, violations(f));
        }
    }

    /** Pings answered 12 client ticks late do not change any charge: each is a function of the client's own keys. */
    @ParameterizedTest
    @MethodSource("clients")
    void chargesUnderLateOrBurstPingsAreJudgedExactly(ClientVersion client) throws Exception {
        try (JavaTickFixture f = mount(client)) {
            idle(f, 20);
            Deque<Integer> pings = new ArrayDeque<>();
            for (int held : new int[] {0, 2, 7, 10, 13}) {
                resetTimers(f);
                assertVehicleExact(tick(f, GROUND, true, null, JUMP_HELD), 0);
                pings.add(f.player.sendTrailingTransaction());
                for (int i = 0; i < held; i++) {
                    assertVehicleExact(tick(f, GROUND, true, null, null), i + 1);
                    pings.add(f.player.sendTrailingTransaction());
                    if (pings.size() > 12) f.pongThrough(pings.poll());
                }
                releaseAndJump(f, chargeData(held));
                pings.add(f.player.sendTrailingTransaction());
                if (pings.size() > 12) f.pongThrough(pings.poll());
                idle(f, 12);
            }
            // A stall: every late ping answered at once.
            while (!pings.isEmpty()) f.pongThrough(pings.poll());
            charge(f, 6);
            releaseArms(f, chargeData(6), chargeData(6));
            assertEquals(0, violations(f));
        }
    }

    /**
     * A respawn replaces the LocalPlayer, whose jumpRidingTicks and keys start over: the old charge is gone, and the
     * charge is known again only from a press on a horse.
     */
    @Test
    void aRespawnWhileChargingForgetsTheCharge() throws Exception {
        try (JavaTickFixture f = mount()) {
            idle(f, 20);
            charge(f, 4);
            DeadPlayerSetbackTest.respawn(f, 0, f.player.compensatedWorld.getLastClientboundDimension().dimension());
            f.pongThrough(f.player.lastTransactionSent.get());
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.input(JUMP_HELD);
            for (int i = 0; i < 3; i++) f.tickEnd();
            assertEquals(null, f.player.ridingJumpCharge.releaseData(RidingJumpCharge.Jumpable.YES));
            assertEquals(0, violations(f));
        }
    }

    /** Camel#onPlayerJump starts a dash and AbstractNautilus#onPlayerJump a boost; neither is judged by a charge. */
    @Test
    void aCamelOrNautilusReleaseIsNotJudged() throws Exception {
        try (JavaTickFixture f = fixture(ClientVersion.V_26_3)) {
            f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.CAMEL, GROUND, 0, 0, 0);
            PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(HORSE);
            camel.hasSaddle = true;
            camel.isTame = true;
            camel.onGround = true;
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            for (int i = 0; i < 20; i++) f.tickEnd();
            var sent = release(f, 100);
            assertFalse(sent.isCancelled());
            assertEquals(100, sent.getPacket().data());
            assertEquals(scale(100), camel.nextHorseJump);
            assertEquals(0, violations(f));
        }
        try (JavaTickFixture f = fixture(ClientVersion.V_26_3)) {
            f.player.compensatedEntities.addEntity(
                    HORSE, EntityTypes.defaults().byKey("minecraft:nautilus").id(), GROUND, 0, 0, 0);
            PacketEntityNautilus nautilus = (PacketEntityNautilus) f.player.compensatedEntities.getEntity(HORSE);
            nautilus.hasSaddle = true;
            nautilus.isTame = true;
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            for (int i = 0; i < 20; i++) f.tickEnd();
            var sent = release(f, 100);
            assertFalse(sent.isCancelled());
            assertEquals(1.0, nautilus.nextPendingJumpScale, 0.0);
            assertEquals(0, violations(f));
        }
    }

    /** A set_equipment putting {@code saddle} in the horse's SADDLE slot, with the ping bundled after it. */
    @SuppressWarnings("unchecked")
    private static void sendSaddle(JavaTickFixture f, SimItemStack saddle) {
        var packet = new InventoryPackets.Equipment(
                HORSE, List.of(new InventoryPackets.EquipmentEntry(EquipmentSlot.SADDLE, saddle)));
        PacketType<InventoryPackets.Equipment> type = (PacketType<InventoryPackets.Equipment>)
                TestProtocolRuntime.catalog().stream()
                        .filter(entry -> entry.recordClass() == InventoryPackets.Equipment.class)
                        .findFirst()
                        .orElseThrow();
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
        f.player.packetEntityReplication.onSetEquipment(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static SimItemStack saddle() {
        return ItemUtil.modelItems().stack("minecraft:saddle", 1);
    }

    /** The fixture sends its ticks faster than the wall clock. */
    private static void resetTimers(JavaTickFixture f) throws Exception {
        f.resetTimerBalances();
        JavaTickFixture.setField(
                AbstractTimerCheck.class,
                f.player.checkManager.getCheck(VehicleTimer.class),
                "timerBalanceRealTime",
                System.nanoTime() - 10_000_000_000L);
    }
}
