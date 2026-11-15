package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPlayerTeam;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * EntitySelector#pushableBy reads the pusher's Entity#getTeam, which TamableAnimal#getTeam takes from a tame animal's
 * owner: before its own team through 1.21.4 (OwnableEntity#getOwner finds a player), after it from 1.21.5
 * (OwnableEntity#getRootOwner).
 */
class JavaTamedPushTeamTest {
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y + 10, 0.5);
    private static final double FIRST_FALL = (0 - 0.08) * 0.98F;
    /** Entity#push from a pusher 0.4 along Z. */
    private static final double PUSH = -0.05 * 0.4 / Math.sqrt(0.4);

    private static final int WOLF = 100;
    private static final UUID WOLF_UUID = new UUID(1, 2);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTameWolfTakesItsOwnersPushOwnTeam(boolean legacy) throws Exception {
        try (var f = fixture(legacy)) {
            wolf(f, true, f.player.getUniqueId());
            team(f, "pack", "pushOwnTeam", f.player.getName());

            assertFalls(f, 0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTameWolfTakesARemoteOwnersTeam(boolean legacy) throws Exception {
        try (var f = fixture(legacy)) {
            f.addRemotePlayer(101, new Vec3(4.5, FLOOR_Y, 4.5), GameMode.SURVIVAL);
            var owner = (PacketEntityPlayer) f.player.compensatedEntities.getEntity(101);
            owner.scoreboardName = "Owner";
            wolf(f, true, owner.profile);
            team(f, "pack", "pushOwnTeam", f.player.getName(), "Owner");

            assertFalls(f, 0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUntamedWolfKeepsItsOwnTeam(boolean legacy) throws Exception {
        try (var f = fixture(legacy)) {
            wolf(f, true, f.player.getUniqueId());
            team(f, "pack", "pushOwnTeam", f.player.getName());
            wolf(f, false, f.player.getUniqueId());

            assertFalls(f, PUSH);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTameWolfWhoseOwnerIsAbsentKeepsItsOwnTeam(boolean legacy) throws Exception {
        try (var f = fixture(legacy)) {
            wolf(f, true, new UUID(3, 4));
            team(f, "pack", "pushOwnTeam", f.player.getName());

            assertFalls(f, PUSH);
        }
    }

    /** 1.21.4 reads the owner's team first; 1.21.5 reads the animal's own team first. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTameWolfOnATeamOfItsOwn(boolean legacy) throws Exception {
        try (var f = fixture(legacy)) {
            wolf(f, true, f.player.getUniqueId());
            team(f, "pack", "pushOwnTeam", f.player.getName());
            team(f, "wolves", "always", WOLF_UUID.toString());

            assertFalls(f, legacy ? 0 : PUSH);
        }
    }

    private static JavaTickFixture fixture(boolean legacy) throws Exception {
        var f = legacy
                ? new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4)
                : new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_26_3);
        f.place(START, Vec3.ZERO);
        f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
        assertTrue(exact(f.tick(START, false)));
        return f;
    }

    /** A wolf 0.4 along Z from the falling player and within its box, ticking before it; then its tame data. */
    private static void wolf(JavaTickFixture f, boolean tame, UUID owner) {
        var entities = f.player.compensatedEntities;
        if (entities.getEntity(WOLF) == null) {
            entities.addEntity(WOLF, EntityTypes.defaults().byKey("wolf").id(), START.add(0, -0.5, 0.4), 0, 0, 0);
            entities.getEntity(WOLF).scoreboardName = WOLF_UUID.toString();
            entities.getEntity(WOLF).setClientTickOrder(-1L);
        }
        int flags = WatchableIndexUtil.tameableFlags(
                f.player.user.getCultConnection().getObservedProtocol());
        entities.updateEntityMetadata(
                WOLF,
                List.of(
                        new EntityMetadata.Entry(flags, (byte) (tame ? 4 : 0), ByteBuffer.allocate(0)),
                        new EntityMetadata.Entry(flags + 1, Optional.of(owner), ByteBuffer.allocate(0))));
    }

    private static void team(JavaTickFixture f, String name, String rule, String... members) {
        f.player.compensatedEntities.teamCollision.apply(new ClientboundSetPlayerTeam(name, 0, rule, List.of(members)));
    }

    private static void assertFalls(JavaTickFixture f, double pushZ) {
        PredictionResult result = f.tick(START.add(0, FIRST_FALL, pushZ), false);
        assertTrue(exact(result), describe(result));
    }
}
