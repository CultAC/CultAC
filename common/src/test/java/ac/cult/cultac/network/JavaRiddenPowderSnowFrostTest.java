package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A saddled horse stuck in powder snow on stone, as in a flagged 1.21.5 client on a 26.3 server. The server freezes
 * the horse and syncs its DATA_TICKS_FROZEN and its movement_speed, minecraft:powder_snow modifier included.
 * Entity#move scales each move by the stuck multiplier's 0.9F and clears the velocity, so every move is the ridden
 * input alone: 0.98F of AbstractHorse#getRiddenSpeed on 0.6-friction ground.
 */
class JavaRiddenPowderSnowFrostTest {
    private static final int HORSE = 100;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    /** The flagged horse's movement_speed base. */
    private static final double BASE = 0.1811855F;

    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    /** Every client keeps the server's frost until the next packet replaces it, and a move without it flags. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void theServersFrostSlowsTheRiddenHorse(String release, ClientVersion version) throws Exception {
        try (var f = stuckHorse(version)) {
            f.input(FORWARD);
            double z = START.z;
            for (int ticksFrozen = 12; ticksFrozen <= 15; ticksFrozen++) {
                freeze(f, ticksFrozen, ticksFrozen);
                z += move(frost(ticksFrozen));
                assertVehicleExact(f.rideTick(new Vec3(START.x, FLOOR_Y, z), true), ticksFrozen);
            }
            freeze(f, 16, 16);
            var unslowed = f.rideTick(new Vec3(START.x, FLOOR_Y, z + move(0.0D)), true);
            assertFalse(exact(unslowed), release + " " + describe(unslowed));
        }
    }

    /**
     * From 1.17 through 1.21.4 the horse's LivingEntity#aiStep replaces the frost after travel from the synced count;
     * from 1.21.5 the client keeps the server's modifier. The first travel uses the packet's frost either way.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void olderClientsReplaceTheServersFrostAfterTravel(String release, ClientVersion version) throws Exception {
        try (var f = stuckHorse(version)) {
            freeze(f, 70, 12);
            f.input(FORWARD);
            double z = START.z;
            for (int tick = 0; tick < 3; tick++) {
                boolean replaced = tick > 0 && version.isOlderThan(ClientVersion.V_1_21_5);
                z += move(frost(replaced ? 70 : 12));
                assertVehicleExact(f.rideTick(new Vec3(START.x, FLOOR_Y, z), true), tick);
            }
        }
    }

    /** The horse at {@link #START} in a powder snow field, mounted and stuck there after idle ticks. */
    private static JavaTickFixture stuckHorse(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        boolean ready = false;
        try {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 4; z++) {
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y, z), BlockIds.POWDER_SNOW.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.place(START, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, START, 0, 0, 0);
            PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
            horse.hasSaddle = true;
            horse.isTame = true;
            horse.onGround = true;
            f.player.compensatedEntities.updateAttributes(
                    HORSE, List.of(new AttributeSnapshot("minecraft:movement_speed", BASE, List.of())));
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            for (int tick = 0; tick < 6; tick++) assertVehicleExact(f.rideTick(START, true), tick);
            ready = true;
            return f;
        } finally {
            if (!ready) f.close();
        }
    }

    /** The server's tick: SetEntityData with DATA_TICKS_FROZEN, then UpdateAttributes with its frost. */
    private static void freeze(JavaTickFixture f, int syncedTicksFrozen, int modifierTicksFrozen) {
        f.player.compensatedEntities.updateEntityMetadata(
                HORSE,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.ENTITY_TICKS_FROZEN, syncedTicksFrozen, ByteBuffer.allocate(0))));
        f.player.compensatedEntities.updateAttributes(
                HORSE,
                List.of(new AttributeSnapshot(
                        "minecraft:movement_speed",
                        BASE,
                        List.of(new AttributeModifier(
                                "minecraft:powder_snow",
                                frost(modifierTicksFrozen),
                                AttributeModifier.Operation.ADD_VALUE)))));
    }

    /** LivingEntity#tryAddFrost's modifier amount for {@code ticksFrozen}. */
    private static double frost(int ticksFrozen) {
        return -0.05F * (Math.min(ticksFrozen, 140) / 140F);
    }

    /** One stuck move along +z from rest, with the frost {@code frost} on the horse's movement_speed. */
    private static double move(double frost) {
        float speed = (float) (BASE + frost);
        double input = (double) 0.98F * (speed * (0.21600002F / (0.6F * 0.6F * 0.6F)));
        return input * 0.9F;
    }
}
