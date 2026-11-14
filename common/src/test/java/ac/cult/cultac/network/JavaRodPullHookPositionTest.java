package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHook;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/**
 * FishingHook#pullEntity pulls from where the client's hook is. On the client's first hook tick after
 * DATA_HOOKED_ENTITY the FLYING branch only switches to HOOKED_IN_ENTITY and returns, so a reel handled before the
 * hook's second tick pulls from the hook's last position, not from Entity#getY(0.8) of the player. A hook that ticks
 * before the player holds the player's position from the start of the last move. Entity#rideTick drops the pull of a
 * passenger.
 */
class JavaRodPullHookPositionTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 HIGH = new Vec3(0.5, FLOOR_Y + 10, 0.5);
    private static final double HOOK_HEIGHT = 1.8F * 0.8;
    private static final Vec3 OWNER = new Vec3(4, 0, 0);
    private static final int OWNER_ID = 300;
    private static final int HOOK = 301;
    private static final int CAMEL = 302;

    /** The hook's last position on the player's feet or off its centre; attach and reel in one frame. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void aReelInTheAttachFramePullsFromTheHooksLastPosition(ClientVersion version) throws Exception {
        for (Vec3 hookOffset : new Vec3[] {new Vec3(0, 0.1, 0), new Vec3(0.25, 1.0, -0.25), new Vec3(-0.2, 0.4, 0)}) {
            try (var f = airborne(version)) {
                Vec3 hookAt = HIGH.add(hookOffset);
                hook(f, hookAt);
                pull(f, true);
                Vec3 pull = pullFrom(HIGH.add(OWNER), hookAt);
                double fall = (0 - 0.08) * 0.98F;
                PredictionResult pulled = f.tick(HIGH.add(pull.x, fall + pull.y, pull.z), false);
                assertTrue(exact(pulled), hookOffset + ": " + describe(pulled));
            }
        }
    }

    /** One client tick between the attach and the reel: the hook's first tick after the attach does not move it. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void aReelOneTickAfterTheAttachPullsFromTheHooksLastPosition(ClientVersion version) throws Exception {
        try (var f = airborne(version)) {
            Vec3 hookAt = HIGH.add(0.25, 0.1, 0.25);
            hook(f, hookAt);
            f.pongThrough(f.player.lastTransactionSent.get());
            double velocity = (0 - 0.08) * 0.98F;
            Vec3 at = HIGH.add(0, velocity, 0);
            PredictionResult falling = f.tick(at, false);
            assertTrue(exact(falling), "before the reel: " + describe(falling));
            velocity = (velocity - 0.08) * 0.98F;
            pull(f, true);
            Vec3 pull = pullFrom(HIGH.add(OWNER), hookAt);
            PredictionResult pulled = f.tick(at.add(pull.x, velocity + pull.y, pull.z), false);
            assertTrue(exact(pulled), describe(pulled));
        }
    }

    /**
     * 300 to 800 ms without pings, then the hook's position, the attach and the reel answered in one frame: unpulled
     * falls until then, and a pull from the hook's last position after.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void anAttachAndReelArrivingTogetherPullFromTheHooksLastPosition(ClientVersion version) throws Exception {
        for (int stall : new int[] {6, 10, 16}) {
            try (var f = airborne(version)) {
                double fallen = 0;
                double v = (0 - 0.08) * 0.98F;
                for (int tick = 0; tick < stall; tick++) {
                    fallen += v;
                    v = (v - 0.08) * 0.98F;
                }
                Vec3 hookAt = HIGH.add(0.2, fallen + 0.3, 0.1);
                hook(f, hookAt);
                pull(f, false);
                int burst = f.player.lastTransactionSent.get();
                Vec3 at = HIGH;
                double velocity = (0 - 0.08) * 0.98F;
                for (int tick = 0; tick < stall; tick++) {
                    at = at.add(0, velocity, 0);
                    PredictionResult falling = f.tick(at, false);
                    assertTrue(exact(falling), stall + " stalled " + tick + ": " + describe(falling));
                    velocity = (velocity - 0.08) * 0.98F;
                }
                f.pongThrough(burst);
                Vec3 pull = pullFrom(HIGH.add(OWNER), hookAt);
                PredictionResult pulled = f.tick(at.add(pull.x, velocity + pull.y, pull.z), false);
                assertTrue(exact(pulled), stall + ": " + describe(pulled));
            }
        }
    }

    /**
     * A hook ahead of the player in the client's entity tick list sets its position before the player's move, so a
     * reel after that tick pulls from the player's position at the start of the move before.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void aHookTickingBeforeTheRisingPlayerPullsFromTheMoveBefore(ClientVersion version) throws Exception {
        try (var f = airborne(version)) {
            hook(f, HIGH.add(0, HOOK_HEIGHT, 0));
            int knockback = f.velocity(new Vec3(0, 0.6, 0));
            f.pongThrough(knockback);
            Vec3 at = HIGH.add(0, 0.6, 0);
            PredictionResult rising = f.tick(at, false);
            assertTrue(exact(rising), "rising: " + describe(rising));
            double velocity = (0.6 - 0.08) * 0.98F;
            pull(f, true);
            Vec3 pull = pullFrom(HIGH.add(OWNER), HIGH.add(0, HOOK_HEIGHT, 0));
            PredictionResult pulled = f.tick(at.add(pull.x, velocity + pull.y, pull.z), false);
            assertTrue(exact(pulled), describe(pulled));
        }
    }

    /** Entity#rideTick zeroes the passenger's delta movement, so a pulled rider keeps the vehicle's move. */
    @Test
    void aPulledRiderKeepsTheVehiclesMove() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            Vec3 camelAt = new Vec3(0.5, FLOOR_Y, 3.5);
            f.place(camelAt.add(0, 1, 0), Vec3.ZERO);
            f.input(NONE);
            f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, camelAt, 0, 0, 0);
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(CAMEL)).hasSaddle = true;
            f.setPassengers(CAMEL, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            PredictionResult riding = f.rideTick(camelAt, true);
            assertTrue(exact(riding), "riding: " + describe(riding));

            f.addRemotePlayer(OWNER_ID, camelAt.add(OWNER), GameMode.SURVIVAL);
            f.player.compensatedEntities.addEntity(
                    HOOK, EntityTypeIds.FISHING_BOBBER, camelAt.add(0, 2, 0), 0, 0, OWNER_ID);
            ((PacketEntityHook) f.player.compensatedEntities.getEntity(HOOK)).attached = f.player.entityID;
            pull(f, true);
            for (int tick = 0; tick < 2; tick++) {
                PredictionResult pulled = f.rideTick(camelAt, true);
                assertTrue(exact(pulled), "pulled ride tick " + tick + ": " + describe(pulled));
            }
        }
    }

    private static Vec3 pullFrom(Vec3 owner, Vec3 hook) {
        return owner.subtract(hook).multiply(0.1, 0.1, 0.1);
    }

    /** Airborne at {@link #HIGH} from rest, after one still tick. */
    private static JavaTickFixture airborne(ClientVersion version) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.place(HIGH, Vec3.ZERO);
        f.input(NONE);
        PredictionResult still = f.tick(HIGH, false);
        assertTrue(exact(still), describe(still));
        f.addRemotePlayer(OWNER_ID, HIGH.add(OWNER), GameMode.SURVIVAL);
        return f;
    }

    /** The owner's hook at {@code at}, attached to the local player. */
    private static void hook(JavaTickFixture f, Vec3 at) {
        f.player.compensatedEntities.addEntity(HOOK, EntityTypeIds.FISHING_BOBBER, at, 0, 0, OWNER_ID);
        ((PacketEntityHook) f.player.compensatedEntities.getEntity(HOOK)).attached = f.player.entityID;
    }

    private static void pull(JavaTickFixture f, boolean answered) {
        var packet = new ClientboundEntityEvent(HOOK, (byte) 31);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_EVENT, packet, false);
        f.player.packetEntityReplication.onEntityEvent(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        if (answered) f.pongThrough(f.player.lastTransactionSent.get());
    }
}
