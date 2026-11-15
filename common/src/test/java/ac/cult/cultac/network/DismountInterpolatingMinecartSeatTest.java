package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A remote minecart in the air carries the local player, moves a block east and is removed two client ticks later.
 * ClientPacketListener#handleMoveEntity calls lerpTo with 3 steps; through 1.21.4 the minecart's lerpTo
 * (OldMinecartBehavior from 1.21.2, AbstractMinecart before) runs them plus 2, while from 1.21.5 OldMinecartBehavior's
 * InterpolationHandler takes its default 3. Entity#positionRider seats the player on the minecart's stepped position,
 * and Entity#setRemoved's stopRiding leaves it there, so the first free move starts from that seat.
 */
class DismountInterpolatingMinecartSeatTest {
    private static final int MINECART = 100;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    /** LivingEntity#travelInAir: one tick of gravity from the zero delta Entity#rideTick left, then 0.98 drag. */
    private static final double FALL = -0.08 * 0.98F;

    @ParameterizedTest(name = "{0}: the seat follows {1} minecart steps")
    @CsvSource({"V_1_21_2, 5", "V_1_21_4, 5", "V_1_21_5, 3", "V_1_21_9, 3", "V_26_3, 3"})
    void theFirstFreeMoveStartsOnTheInterpolatedSeat(ClientVersion version, int steps) throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.input(NONE);
            Vec3 standing = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(standing, new Vec3(0, FALL, 0));
            PredictionResult stood = f.tick(standing, true);
            assertTrue(exact(stood), describe(stood));
            Vec3 cart = new Vec3(0.5, FLOOR_Y + 2, 0.5);
            // ClientPacketListener#handleAddEntity: the position the later relative moves start from.
            f.player.packetEntityReplication.addEntity(
                    MINECART, EntityTypes.defaults().byKey("minecraft:minecart").id(), cart, 0, 0, 0, -1);
            f.setPassengers(MINECART, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.rideTick();
            f.rideTick();
            double seatY = rideTickSeat(f).y;

            move(f, 4096);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.rideTick();
            f.rideTick();
            // Entity#lerpPositionAndRotationStep / InterpolationHandler#interpolate: 1/remaining steps per tick.
            double x = cart.x;
            for (int remaining = steps; remaining > steps - 2; remaining--) x += (cart.x + 1 - x) / remaining;

            remove(f);
            f.pongThrough(f.player.lastTransactionSent.get());
            PredictionResult free = f.tick(new Vec3(x, seatY + FALL, 0.5), false);
            assertTrue(exact(free), describe(free));
        }
    }

    private static void move(JavaTickFixture f, int dx) {
        var move = new ClientboundMoveEntity(
                MINECART, new EntityDelta.Linear((short) dx, (short) 0, (short) 0), 0, 0, false, true, false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, move, false);
        f.player.packetEntityReplication.onMoveEntity(event, f.player, move);
        event.getTasksAfterSend().forEach(Runnable::run);
        ((EmbeddedChannel) f.player.user.getChannel()).runPendingTasks();
    }

    private static void remove(JavaTickFixture f) {
        var packet = new ClientboundRemoveEntities(List.of(MINECART));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, packet, false);
        f.player.packetEntityReplication.onRemoveEntities(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static Vec3 rideTickSeat(JavaTickFixture f) throws Exception {
        Field field = CompensatedEntities.class.getDeclaredField("rideTickSeat");
        field.setAccessible(true);
        Vec3 seat = (Vec3) field.get(f.player.compensatedEntities);
        assertTrue(seat != null, "the last client tick ended off the vehicle");
        return seat;
    }

    /** No dismount velocity resync: the ride tick's own delta carries into the free move. */
    private static AutoCloseable observeOnly() throws ReflectiveOperationException {
        BaseConfigManager manager = CultAPI.INSTANCE.getConfigManager();
        Field field = BaseConfigManager.class.getDeclaredField("observeOnly");
        field.setAccessible(true);
        boolean original = field.getBoolean(manager);
        field.setBoolean(manager, true);
        return () -> field.setBoolean(manager, original);
    }
}
