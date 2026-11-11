package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.vehicle.VehicleGround;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Entity#teleportCrossDimension seats the rider on the vehicle's copy in the new level. The client's respawn builds a
 * new LocalPlayer, and until ClientPacketListener#hasClientLoaded holds, LocalPlayer#tick does not run: no
 * LocalPlayer#applyInput stores the held keys in its xxa/zza, while Minecraft#tick still ticks ClientLevel's entities,
 * the ridden root among them, reading the new player's empty input.
 */
class JavaRespawnRiddenRootTest {
    private static final int HORSE = 7721;
    private static final int OLD_HORSE = 7718;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int KEEP_ALL =
            ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS | ClientboundRespawn.KEEP_ENTITY_DATA;

    /** The rider held forward into the portal and released it while the new level loaded. */
    @Test
    void aRootTickedWhileLoadingReadsTheNewPlayersEmptyInput() throws Exception {
        rideOutOfThePortal(0);
    }

    /** The horse's copy is placed above the floor: it falls and lands before the rider's first report. */
    @Test
    void aRootFallsAndLandsWhileLoading() throws Exception {
        rideOutOfThePortal(2);
    }

    private static void rideOutOfThePortal(double above) throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.player.checkManager.getCheck(VehicleGround.class).setEnabled(true);
            f.place(START, GROUNDED);
            f.input(FORWARD);

            DeadPlayerSetbackTest.respawn(
                    f,
                    KEEP_ALL,
                    f.player.compensatedWorld.getLastClientboundDimension().dimension());
            f.pongThrough(f.player.lastTransactionSent.get());
            f.playerPosition(START, Vec3.ZERO, Set.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z));
            var add = new PacketSendEvent<>(
                    f.fixture.user,
                    ConnectionPhase.PLAY,
                    ClientboundPackets.ADD_ENTITY,
                    new ClientboundAddEntity(
                            HORSE, "minecraft:horse", JavaTickFixture.vec3d(START.add(0, above, 0)), 0, 0, 0),
                    false);
            f.player.packetEntityReplication.onAddEntity(add, f.player, add.getPacket());
            add.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            var horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
            horse.hasSaddle = true;
            horse.isTame = true;
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.pongThrough(f.player.sendTrailingTransaction());

            // ClientLevel#tickEntities ticks the horse each loading tick, and no MoveVehicle reports it.
            for (int tick = 0; tick < 20; tick++) f.tickEnd();

            f.input(NONE);
            for (int tick = 0; tick < 6; tick++) {
                assertVehicleExact(rideTick(f, START), tick);
            }
            assertEquals(0, f.player.checkManager.getCheck(VehicleGround.class).violations);
        }
    }

    /**
     * The live order: the rider held forward on the horse it rode into the portal, so the old LocalPlayer's
     * applyInput stored it. The server dismounts it, respawns it, and seats it on the horse's copy only after two
     * loading tick ends; the keys are released while loading. Neither the loading ticks on foot nor those riding run
     * LocalPlayer#tick, so the new LocalPlayer holds no xxa/zza, and its ServerboundPlayerLoadedPacket comes before
     * the first loaded tick's player_input.
     */
    @Test
    void aRiderHoldingForwardIntoThePortalLoadsWithoutInput() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.player.checkManager.getCheck(VehicleGround.class).setEnabled(true);
            f.place(START, Vec3.ZERO);
            addHorse(f, OLD_HORSE, START);
            f.setPassengers(OLD_HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            acceptInOrder(f);
            // The new horse's first Entity#move is still: no verticalCollisionBelow until gravity moves it.
            for (int tick = 0; tick < 3; tick++) assertVehicleExact(f.rideTick(START, tick > 0), tick);
            // AbstractHorse#getRiddenInput reads the zza the rider's last applyInput stored, so the press moves the
            // horse from the next tick on; the server takes the horse through the portal first.
            f.input(FORWARD);
            assertVehicleExact(f.rideTick(START, true), 3);

            f.setPassengers(OLD_HORSE, List.of());
            DeadPlayerSetbackTest.respawn(f, KEEP_ALL, "minecraft:the_nether");
            var remove = new PacketSendEvent<>(
                    f.fixture.user,
                    ConnectionPhase.PLAY,
                    ClientboundPackets.REMOVE_ENTITIES,
                    new ClientboundRemoveEntities(List.of(OLD_HORSE)),
                    false);
            f.player.packetEntityReplication.onRemoveEntities(remove, f.player, remove.getPacket());
            remove.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            acceptInOrder(f);
            f.playerPosition(START, Vec3.ZERO, Set.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z));
            // The server seats the rider before it sends the horse's copy: ClientPacketListener ignores that one.
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            for (int tick = 0; tick < 2; tick++) f.tickEnd();

            JavaTickFixture.floor(f.player);
            addHorse(f, HORSE, START);
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.pongThrough(f.player.sendTrailingTransaction());
            acceptInOrder(f);
            for (int tick = 0; tick < 20; tick++) f.tickEnd();

            f.listener.onPlayerLoaded(
                    f.event(ServerboundPackets.PLAYER_LOADED, ServerboundPackets.PLAYER_LOADED.opaqueValue()),
                    f.player,
                    ServerboundPackets.PLAYER_LOADED.opaqueValue());
            f.input(NONE);
            for (int tick = 0; tick < 6; tick++) {
                assertVehicleExact(rideTick(f, START), tick);
            }
            assertEquals(0, f.player.checkManager.getCheck(VehicleGround.class).violations);
        }
    }

    private static void addHorse(JavaTickFixture f, int id, Vec3 at) {
        var add = new PacketSendEvent<>(
                f.fixture.user,
                ConnectionPhase.PLAY,
                ClientboundPackets.ADD_ENTITY,
                new ClientboundAddEntity(id, "minecraft:horse", JavaTickFixture.vec3d(at), 0, 0, 0),
                false);
        f.player.packetEntityReplication.onAddEntity(add, f.player, add.getPacket());
        add.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        ((PacketEntityHorse) f.player.compensatedEntities.getEntity(id)).isTame = true;
        f.equip(id, EquipmentSlot.SADDLE, ItemUtil.modelItems().stack("minecraft:saddle", 1));
        assertTrue(((PacketEntityHorse) f.player.compensatedEntities.getEntity(id)).hasSaddle);
    }

    /** The client handles every sent packet in order, accepting each teleport where it puts it. */
    private static void acceptInOrder(JavaTickFixture f) {
        TeleportData pending;
        while ((pending = f.player.getSetbackTeleportUtil().pendingTeleports.peek()) != null) {
            f.pongThrough(pending.getTransaction());
            Vec3 target = pending.getLocation();
            var packet = new ServerboundAcceptTeleportation(
                    pending.getTeleportId(), new Vec3d(target.x, target.y, target.z), 0, 0);
            new PacketServerTeleport()
                    .onAcceptTeleportation(f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet), f.player, packet);
            assertTrue(f.player.getSetbackTeleportUtil().pendingTeleports.peek() != pending, "not accepted");
        }
    }

    /** LocalPlayer#sendChanges: the passenger's Rot, then the horse's MoveVehicle with its Entity#onGround. */
    private static PredictionResult rideTick(JavaTickFixture f, Vec3 to) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, 0, 0, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.moveVehicle(to, 0, 0, true);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the horse's move was not predicted");
        return result;
    }
}
