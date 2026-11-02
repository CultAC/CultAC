package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.enums.BoatEntityStatus;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaBoatWaterEntryMoveTest {
    /** AbstractBoat#floatBoat calls setPos and zeroes Y before its separate Entity#move. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void theWaterEntrySnapIsNotAnAttemptedVerticalMove(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.5, FLOOR_Y + 0.2, 0.5);
            Vec3 airborne = start.add(0, 0.04, 0);
            f.place(airborne, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.OAK_BOAT, airborne, 0, 0, 0);
            f.setPassengers(100, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var falling = f.rideTick(start, false);
            assertTrue(JavaTickFixture.exact(falling), JavaTickFixture.describe(falling));
            f.player.boatData.status = BoatEntityStatus.IN_AIR;
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++)
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.WATER.defaultState());
            double surface = (float) (FLOOR_Y + 8.0F / 9.0F);
            var result = f.rideTick(new Vec3(start.x, surface - 0.5625 + 0.101, start.z), false);
            assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
            var collision = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertEquals(0, collision.attempted().y);
            assertFalse(collision.verticalCollision());
            assertTrue(result.getPositionOnlyDelta().y > 0.2);
        }
    }
}
