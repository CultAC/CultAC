package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/**
 * A collidable actor only the Bedrock client holds is a solid box for its movement.
 *
 * <p>A player walks backwards into the translator's world border: the border's actor stands at local z -7.0 with
 * width 10, so its face lies at z -2.0, and the 0.6 wide player stops at z -1.6999993 with no velocity into it,
 * then slides along it.
 */
public class BedrockClientActorWallTest {
    private static final BedrockCoordinateFrame ORIGIN = new BedrockCoordinateFrame(20064, 20080, 9);
    private static final long WALL = 5681;
    private static final float YAW = -0.049453735F;
    private static final long DOWN = 1L << PlayerAuthInputData.DOWN.ordinal();
    private static final long LEFT = 1L << PlayerAuthInputData.LEFT.ordinal();
    private static final long HORIZONTAL = 1L << PlayerAuthInputData.HORIZONTAL_COLLISION.ordinal();
    private static final long VERTICAL = 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal();
    private static final int FIRST_TICK = 24673;
    /**
     * Local x, local z, velocity x, velocity z, strafe, forward and flags of each tick of the walk from rest. The
     * first tick only seeds the standing player.
     */
    private static final Object[][] TICKS = {
        {-29.5F, 5.6941605F, 0.0F, 0.0F, 0F, 0F, 0L},
        {-29.500084F, 5.5961604F, -4.618439E-5F, -0.053507987F, 0F, -1F, DOWN},
        {-29.500216F, 5.4446526F, -7.140107E-5F, -0.08272336F, 0F, -1F, DOWN},
        {-29.500372F, 5.2639294F, -8.5169384E-5F, -0.098674946F, 0F, -1F, DOWN},
        {-29.500542F, 5.0672545F, -9.268688E-5F, -0.10738452F, 0F, -1F, DOWN},
        {-29.50072F, 4.86187F, -9.679144E-5F, -0.11213995F, 0F, -1F, DOWN},
        {-29.5009F, 4.65173F, -9.903251E-5F, -0.11473641F, 0F, -1F, DOWN},
        {-29.501083F, 4.4389935F, -1.0025615E-4F, -0.11615407F, 0F, -1F, DOWN},
        {-29.501268F, 4.224839F, -1.00924255E-4F, -0.116928115F, 0F, -1F, DOWN},
        {-29.501453F, 4.009911F, -1.0128904E-4F, -0.11735075F, 0F, -1F, DOWN},
        {-29.501638F, 3.7945604F, -1.01488215E-4F, -0.1175815F, 0F, -1F, DOWN},
        {-29.501825F, 3.578979F, -1.01596954E-4F, -0.1177075F, 0F, -1F, DOWN},
        {-29.502012F, 3.3632717F, -1.0165633E-4F, -0.11777629F, 0F, -1F, DOWN},
        {-29.5022F, 3.1474953F, -1.01688755E-4F, -0.117813855F, 0F, -1F, DOWN},
        {-29.502386F, 2.9316816F, -1.0170646E-4F, -0.11783436F, 0F, -1F, DOWN},
        {-29.502573F, 2.715847F, -1.0171612E-4F, -0.11784555F, 0F, -1F, DOWN},
        {-29.50276F, 2.5000014F, -1.01721394E-4F, -0.11785167F, 0F, -1F, DOWN},
        {-29.502947F, 2.28415F, -1.01724276E-4F, -0.117855005F, 0F, -1F, DOWN},
        {-29.503134F, 2.068295F, -1.0172585E-4F, -0.11785683F, 0F, -1F, DOWN},
        {-29.50332F, 1.8524382F, -1.01726706E-4F, -0.11785782F, 0F, -1F, DOWN},
        {-29.503508F, 1.6365805F, -1.0172718E-4F, -0.117858365F, 0F, -1F, DOWN},
        {-29.503695F, 1.4207221F, -1.0172743E-4F, -0.11785867F, 0F, -1F, DOWN},
        {-29.503881F, 1.2048635F, -1.0172758E-4F, -0.11785883F, 0F, -1F, DOWN},
        {-29.504068F, 0.98900473F, -1.0172766E-4F, -0.11785891F, 0F, -1F, DOWN},
        {-29.504255F, 0.7731459F, -1.01727695E-4F, -0.11785896F, 0F, -1F, DOWN},
        {-29.504442F, 0.557287F, -1.01727725E-4F, -0.11785899F, 0F, -1F, DOWN},
        {-29.50463F, 0.34142798F, -1.0172774E-4F, -0.117859006F, 0F, -1F, DOWN},
        {-29.504816F, 0.12556903F, -1.0172774E-4F, -0.117859006F, 0F, -1F, DOWN},
        {-29.505003F, -0.090289935F, -1.0172774E-4F, -0.117859006F, 0F, -1F, DOWN},
        {-29.50519F, -0.3061489F, -1.0172774E-4F, -0.117859006F, 0F, -1F, DOWN},
        {-29.505377F, -0.5220079F, -1.0172774E-4F, -0.117859006F, 0F, -1F, DOWN},
        {-29.505564F, -0.7378668F, -1.0172774E-4F, -0.117859006F, 0F, -1F, DOWN},
        {-29.50575F, -0.9537258F, -1.0172774E-4F, -0.117859006F, 0F, -1F, DOWN},
        {-29.505938F, -1.1695848F, -1.0172774E-4F, -0.117859006F, 0F, -1F, DOWN},
        {-29.506124F, -1.3854437F, -1.0172774E-4F, -0.117859006F, 0F, -1F, DOWN},
        {-29.506311F, -1.6013026F, -1.0172774E-4F, -0.117859006F, 0F, -1F, DOWN},
        {-29.506498F, -1.6999993F, -1.0172774E-4F, 0.0F, 0F, -1F, DOWN | HORIZONTAL},
        {-29.4086F, -1.6999993F, 0.053452443F, 0.0F, 1F, 0F, LEFT | HORIZONTAL},
        {-29.257147F, -1.6999993F, 0.082693025F, 0.0F, 1F, 0F, LEFT | HORIZONTAL},
        {-29.076454F, -1.6999993F, 0.09865839F, 0.0F, 1F, 0F, LEFT | HORIZONTAL}
    };

    @Test
    public void theBorderActorStopsThePlayerAtItsFaceAndLetsItSlide() {
        assertEquals(0, run(true), 0);
    }

    @Test
    public void walkingToTheSameStopWithoutTheActorIsRejected() {
        assertTrue(run(false) > 0);
    }

    private static double run(boolean wall) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int stone = DataTables.defaults().registry().block("minecraft:stone").defaultState();
            for (int x = 20031; x <= 20037; x++)
                for (int z = 20075; z <= 20087; z++) {
                    player.compensatedWorld.ensureValidationChunkLoaded(x >> 4, z >> 4);
                    player.compensatedWorld.updateBlock(x, 119, z, stone);
                }
            if (wall) {
                // The border's actor follows the player along the border, 10 below its feet.
                player.bedrockState.clientActors.place(WALL, -29.506311F, 110.0F, -7.0F, ORIGIN);
                player.bedrockState.clientActors.shape(WALL, 10.0F, 25.0F, true);
            }
            Vec3 start = ORIGIN.toWorld(new Vec3((float) TICKS[0][0], 120, (float) TICKS[0][1]));
            player.x = player.lastX = start.x;
            player.y = player.lastY = start.y;
            player.z = player.lastZ = start.z;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, start.x, start.y, start.z);
            for (int tick = 0; tick < TICKS.length; tick++) {
                Object[] row = TICKS[tick];
                var local = new Vec3((float) row[0], 120, (float) row[1]);
                var velocity = new Vec3((float) row[2], -0.0784F, (float) row[3]);
                var frame = BedrockAuthInputFrame.builder(player.playerUUID)
                        .protocolVersion(2168)
                        .clientTick(FIRST_TICK + tick)
                        .inputMode(1)
                        .playMode(2)
                        .deviceId(3)
                        .coordinateFrame(ORIGIN)
                        .position(ORIGIN.toWorld(local))
                        .packetPosition(new Vec3(local.x, 121.62001F, local.z))
                        .rotation(YAW, 0, YAW)
                        .moveVector((float) row[4], (float) row[5])
                        .delta(velocity)
                        .reportedEndOfTickVelocity(velocity)
                        .rawInputFlags((long) row[6] | VERTICAL)
                        .build();
                player.bedrockState.offerAuthInputFrame(frame);
                var result = player.checkManager
                        .getSimulationProcessor()
                        .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
                assertNotNull("tick " + (FIRST_TICK + tick), result);
                var state =
                        result.getProfileResult(BedrockPredictionResult.class).nextTickBaseState();
                assertNotNull(state);
                if (wall && tick > 0) {
                    assertEquals("tick " + (FIRST_TICK + tick), local.z + 20080, state.physicalFeetPosition().z(), 1.0E-4);
                    assertEquals("tick " + (FIRST_TICK + tick), velocity.z, state.velocity().z(), 1.0E-4);
                    assertEquals("tick " + (FIRST_TICK + tick), local.x + 20064, state.physicalFeetPosition().x(), 1.0E-4);
                }
            }
            return player.checkManager.getListener(BedrockMovement.class).violations;
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }
}
