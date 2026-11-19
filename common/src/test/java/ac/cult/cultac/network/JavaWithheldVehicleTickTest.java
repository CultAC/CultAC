package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.packet.PacketCodecUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * A pig ridden through a nether portal. Its copy in the new level is paired with its saddle and the player's mount in
 * one bundle, and a Cult setback can follow before its first checked tick. The client keeps ticking the pig until
 * ClientPacketListener#handleMoveVehicle snaps it, while Cult withholds those ticks from the server.
 */
class JavaWithheldVehicleTickTest {
    private static final int PIG = 100;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    // Pig#getRiddenSpeed through LivingEntity#getFrictionInfluencedSpeed: on stone, and in the air.
    private static final float RIDDEN_SPEED = (float) (0.25D * 0.225D);
    private static final float GROUND_MOVE = RIDDEN_SPEED * (0.21600002F / (0.6F * 0.6F * 0.6F));
    private static final float AIR_MOVE = RIDDEN_SPEED * 0.1F;

    /** Clients whose ServerboundMoveVehiclePacket carries the root's onGround. */
    static Stream<ClientVersion> groundReportingVersions() {
        return Stream.of(ClientVersion.V_1_21_4, ClientVersion.V_26_1, ClientVersion.V_26_3);
    }

    /**
     * The first ridden tick starts without velocity, so Entity#move has no vertical collision and the pig reports air.
     * The withheld tick lands it, and handleMoveVehicle's absSnapTo keeps that ground: the first travel after the
     * setback moves at ground speed, not the flying speed of the last checked tick.
     */
    @ParameterizedTest
    @MethodSource("groundReportingVersions")
    void aLandingTheSetbackWithheldStartsTheNextTravelOnTheGround(ClientVersion version) throws Exception {
        try (Ride ride = Ride.mount(version)) {
            JavaTickFixture f = ride.f;
            Vec3 first = START.add(0, 0, AIR_MOVE);
            assertVehicleExact(f.rideTick(first, false), 0);

            f.player.getSetbackTeleportUtil().executeForceResync("test");
            SetBackData setback = f.player.getSetbackTeleportUtil().getRequiredSetBack();
            ride.withheldTick(first.add(0, 0, AIR_MOVE * 0.91D + AIR_MOVE), true);
            Vec3 velocity = ride.acceptSetback(setback, true);

            // LivingEntity#aiStep's legacy !isEffectiveAi damping precedes Pig#travelRidden through 1.21.4.
            double inherited = velocity.z * (version.isOlderThan(ClientVersion.V_1_21_5) ? 0.98D : 1.0D);
            Vec3 at = setback.getTeleportData().getLocation();
            assertVehicleExact(f.rideTick(at.add(0, 0, inherited + GROUND_MOVE), true), 1);
        }
    }

    /**
     * ServerPlayer#startRiding teleports the player to its seat before the mount. The saddle in the mount's own bundle
     * is not yet the client's when it is sent, so no mount resync places the pig, and a setback before its first
     * checked tick must still move it to its own position, not to the seat.
     */
    @Test
    void aSetbackBeforeTheFirstRiddenTickMovesThePigToItsOwnPosition() throws Exception {
        try (Ride ride = new Ride(new JavaTickFixture())) {
            JavaTickFixture f = ride.f;
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.place(START.add(2, 0, 2), Vec3.ZERO);
            ride.holdCarrotOnAStick();
            ride.addPig();
            // Pig passenger attachment 0.86875 less the player's vehicle attachment 0.6.
            f.playerPosition(START.add(0, 0.26875D, 0), Vec3.ZERO, Set.of());
            f.setPassengers(PIG, List.of(f.player.entityID));
            ride.saddle();
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(ride.moves.isEmpty(), "a mount resync was sent");

            f.player.getSetbackTeleportUtil().executeForceResync("test");
            assertEquals(START, f.player.getSetbackTeleportUtil().getRequiredSetBack().getTeleportData().getLocation());
            Vec3d sent = ride.moves.getLast().position();
            assertEquals(START, new Vec3(sent.x(), sent.y(), sent.z()));
        }
    }

    /** A pig the player rides with a carrot on a stick, and the corrections Cult sends it. */
    private record Ride(JavaTickFixture f, List<ClientboundMoveVehicle> moves, List<Vec3> motions)
            implements AutoCloseable {
        Ride(JavaTickFixture f) {
            this(f, new ArrayList<>(), new ArrayList<>());
            f.fixture.registrar.register(routes -> {
                routes.send(ClientboundPackets.MOVE_VEHICLE, PacketListenerPriority.LOW, (event, player, packet) -> {
                    moves.add(packet);
                    new PacketServerTeleport().onMoveVehicle(event, player, packet);
                });
                routes.send(ClientboundPackets.ENTITY_MOTION, PacketListenerPriority.LOW, (event, player, packet) -> {
                    if (packet.entityId() == PIG) {
                        motions.add(new Vec3(packet.velocity().x(), packet.velocity().y(), packet.velocity().z()));
                    }
                    player.packetEntityReplication.onSetEntityMotion(event, player, packet);
                });
            });
        }

        static Ride mount(ClientVersion version) throws Exception {
            Ride ride = new Ride(new JavaTickFixture());
            JavaTickFixture f = ride.f;
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(START, Vec3.ZERO);
            ride.holdCarrotOnAStick();
            ride.addPig();
            f.pongThrough(f.player.lastTransactionSent.get());
            ride.saddle();
            f.setPassengers(PIG, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            // ClientPacketListener#handleMoveVehicle answers any mount resync before the first ridden tick.
            for (ClientboundMoveVehicle move : List.copyOf(ride.moves)) {
                ride.echo(new Vec3(move.position().x(), move.position().y(), move.position().z()), move, false);
            }
            f.player.xRot = 0;
            f.player.yRot = 0;
            return ride;
        }

        void holdCarrotOnAStick() {
            f.player.getInventory().inventory.setHeldItem(
                    ItemUtil.modelItems().stack("minecraft:carrot_on_a_stick", 1));
        }

        void addPig() {
            var spawn = new ClientboundAddEntity(PIG, "minecraft:pig", new Vec3d(START.x, START.y, START.z), 0, 0, 0);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, spawn, false);
            f.player.packetEntityReplication.onAddEntity(event, f.player, spawn);
            event.tasksAfter().forEach(Runnable::run);
        }

        /** ClientPacketListener#handleSetEquipment's saddle, which the client holds once it answers the next ping. */
        void saddle() {
            f.player.compensatedEntities.updateEntityEquipment(
                    PIG,
                    List.of(new InventoryPackets.EquipmentEntry(
                            EquipmentSlot.SADDLE, ItemUtil.modelItems().stack("minecraft:saddle", 1))));
            assertTrue(((PacketEntityRideable) f.player.compensatedEntities.getEntity(PIG)).hasSaddle);
        }

        /** A ridden client tick Cult withholds while its setback is pending. */
        void withheldTick(Vec3 to, boolean onGround) {
            var rot = new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, onGround, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            var before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            f.moveVehicle(to, f.player.xRot, f.player.yRot, onGround);
            assertTrue(
                    before == f.player.checkManager.getSimulationProcessor().getLastPrediction(),
                    "the withheld tick was predicted");
            f.tickEnd();
        }

        /**
         * The client answers the setback's ping and handleMoveVehicle echoes the snapped pig with the ground its last
         * tick left; returns the velocity handleSetEntityMotion gives the pig.
         */
        Vec3 acceptSetback(SetBackData setback, boolean onGround) {
            f.pongThrough(f.player.lastTransactionSent.get());
            echo(setback.getTeleportData().getLocation(), moves.getLast(), onGround);
            assertTrue(setback.isComplete(), "the setback echo was not accepted");
            return PacketCodecUtil.clientVelocityFromObserved(
                    ProtocolVersion.V26_3, f.player.getClientVersion(), motions.getLast());
        }

        void echo(Vec3 at, ClientboundMoveVehicle snap, boolean onGround) {
            var packet = new ServerboundMoveVehicle(
                    new Vec3d(at.x, at.y, at.z), snap.yaw(), snap.pitch(), onGround, true);
            f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, packet), f.player, packet);
        }

        @Override
        public void close() throws Exception {
            f.close();
        }
    }
}
