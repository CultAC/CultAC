package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A movement reader placed before an interpolating entity in ClientLevel#tickEntities reads the boxes it held before
 * this tick's Entity#commonTick step. Those boxes are kept per entity from tick to tick; a list a reader took stays
 * as it was when the next tick steps the entity again.
 */
class ClientTickPositionsTest {
    private static final int ZOMBIE = 100;

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_3"})
    void aBeforeTickListAReaderKeepsSurvivesTheNextStep(ClientVersion version) throws Exception {
        var protocol = version == ClientVersion.V_26_3 ? ProtocolVersion.V26_3 : ProtocolVersion.V1_21_11;
        try (var f = new JavaTickFixture(protocol, version)) {
            CompensatedEntities entities = f.player.compensatedEntities;
            entities.addEntity(ZOMBIE, EntityTypeIds.ZOMBIE, new Vec3(2.5, JavaTickFixture.FLOOR_Y, 2.5), 0, 0, 0);
            PacketEntity zombie = entities.getEntity(ZOMBIE);
            for (int tick = 0; tick < 5; tick++) step(f, zombie, 0);

            step(f, zombie, 0.25);
            List<SimpleCollisionBox> kept = beforeTick(entities, zombie);
            List<List<Double>> copy = copies(kept);

            step(f, zombie, 0.25);
            List<SimpleCollisionBox> next = beforeTick(entities, zombie);

            assertEquals(copy, copies(kept), "the next step rewrote a list a reader kept");
            assertNotEquals(copy, copies(next), "the second step started from where the first left the zombie");
        }
    }

    /** A proven bundled move of {@code dx}, then the client tick that steps the zombie's interpolation. */
    private static void step(JavaTickFixture f, PacketEntity zombie, double dx) {
        if (dx != 0) {
            zombie.onBundleFirstTransaction(true, true, dx, 0, 0, f.player);
            zombie.onSecondTransaction();
        }
        f.player.packetStateData.acceptedClientTick++;
        f.player.compensatedEntities.tickClientEntities(entity -> entity.onMovement(f.player));
    }

    /** The boxes a reader at the local player's place, which ticks first, sees: the ones before the step. */
    private static List<SimpleCollisionBox> beforeTick(CompensatedEntities entities, PacketEntity zombie) {
        entities.beginMovementPositionReads();
        try {
            List<SimpleCollisionBox> boxes = zombie.getPossibleMovementCollisionBoxCandidates();
            assertEquals(boxes.size(), zombie.possibleMovementCollisionBoxCandidateCount());
            for (int i = 0; i < boxes.size(); i++) {
                SimpleCollisionBox indexed = zombie.possibleMovementCollisionBoxCandidate(i, new SimpleCollisionBox());
                assertEquals(bounds(boxes.get(i)), bounds(indexed), "candidate " + i);
            }
            return boxes;
        } finally {
            entities.endMovementPositionReads();
        }
    }

    private static List<List<Double>> copies(List<SimpleCollisionBox> boxes) {
        return boxes.stream().map(ClientTickPositionsTest::bounds).toList();
    }

    private static List<Double> bounds(SimpleCollisionBox box) {
        return List.of(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }
}
