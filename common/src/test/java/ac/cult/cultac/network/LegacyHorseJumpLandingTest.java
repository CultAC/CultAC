package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A 1.21.4 client jumps a horse with forward held and lands (CultAC issue #129), shifted by (-270, 2, -114) onto the
 * fixture's floor. The client runs AbstractHorse#tick for the ridden horse each tick. AbstractHorse#onPlayerJump calls
 * standIfPossible, which needs Entity#isEffectiveAi; through 1.21.4 that is !level().isClientSide, so the client horse
 * does not stand and its next tick clears allowStandSliding. The server's standing flag arrives during the jump, and on
 * the first tick the horse starts on the ground AbstractHorse#getRiddenInput returns zero.
 */
class LegacyHorseJumpLandingTest {
    private static final int HORSE = 100;
    private static final Vec3 SHIFT = new Vec3(-270, 2, -114);
    private static final float YAW = -808.6962F;
    private static final float PITCH = 11.516963F;
    /** The reported horse's movement_speed, from its grounded moves before the jump. */
    private static final float SPEED = 0.2249995F;
    /** The reported horse's jump_strength: its 0.53511113 jump is this at the 82 charge's scale. */
    private static final double JUMP_STRENGTH = 0.7;
    /** START_JUMPING_WITH_HORSE's charge: 0.4F + 0.4F * 82 / 90.0F of the jump strength. */
    private static final int JUMP_CHARGE = 82;
    /** LivingEntity#travelInAir: a grounded horse's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    /** AbstractHorse FLAG_TAME | FLAG_SADDLE | FLAG_STANDING. */
    private static final byte TAME_SADDLED_STANDING = 0x02 | 0x04 | 0x20;

    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    /** The reported horse positions from the jump tick to the first tick after landing. */
    private static final Vec3[] MOVES = {
        new Vec3(269.4862196077559, 62.535111129283905, 114.35009621343804),
        new Vec3(269.9255460186961, 62.97219986635511, 114.36008036066484),
        new Vec3(270.33938158327265, 63.31514789611301, 114.36948520239974),
        new Vec3(270.73048441040703, 63.56768318880752, 114.37837341948209),
        new Vec3(271.1013141817062, 63.73338608713637, 114.386800910686),
        new Vec3(271.4540644412904, 63.815695152293344, 114.3948175265397),
        new Vec3(271.79069139186265, 63.815695152293344, 114.40246772374549),
        new Vec3(272.1129395750435, 63.73886315079798, 114.40979114879167),
        new Vec3(272.4223647730953, 63.58824169363033, 114.41682315841751),
        new Vec3(272.7203544326793, 63.366752841855735, 114.42359528376402),
        new Vec3(273.00814587876386, 63.07720294297598, 114.43013564430352),
        new Vec3(273.2868425577875, 62.72228721318423, 114.43646931698218),
        new Vec3(273.5574285233125, 62.304594138162784, 114.44261866542124),
        new Vec3(273.82078135433096, 62.0, 114.44860363349866),
        new Vec3(274.0556394158018, 62.0, 114.45394102818393),
    };

    @Test
    void aLegacyHorseStandingFromTheServerIgnoresInputAfterLanding() throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            Vec3 start = new Vec3(268.7063696741703, 62.0, 114.33237331752738).add(SHIFT);
            Vec3 lastMove = new Vec3(0.47415174220674317, 0.0, 0.010775570707039606);
            mount(f, start, new Vec3(lastMove.x * GROUND_FRICTION, GROUNDED_Y, lastMove.z * GROUND_FRICTION));

            for (int tick = 0; tick < MOVES.length; tick++) {
                assertVehicleExact(ride(f, MOVES[tick].add(SHIFT)), tick);
                if (tick == 0) standFromServer(f);
            }
        }
    }

    /**
     * The saddled horse at {@code start} carrying {@code carried}, ridden with forward held. The rider releases the
     * jump: LocalPlayer#aiStep sends START_JUMPING_WITH_HORSE after the horse's tick.
     */
    private static void mount(JavaTickFixture f, Vec3 start, Vec3 carried) {
        f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, start, YAW, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.movementSpeedAttribute = SPEED;
        horse.jumpStrength = JUMP_STRENGTH;
        horse.onGround = true;
        // The spawned horse's interpolation target settles before it is ridden.
        for (int tick = 0; tick < 3; tick++) f.tickEnd();
        f.setPassengers(HORSE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        var jump = new ServerboundPlayerCommand(
                f.player.entityID, PlayerCommandAction.START_JUMPING_WITH_HORSE, JUMP_CHARGE);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, jump), f.player, jump);
        f.input(FORWARD);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(carried);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    /** AbstractHorse#handleStartJump on the server stands the horse; its flags reach the client. */
    private static void standFromServer(JavaTickFixture f) {
        int flags = WatchableIndexUtil.forModel(f.player.user.getCultConnection()).horseFlags();
        f.player.compensatedEntities.updateEntityMetadata(
                HORSE, List.of(new EntityMetadata.Entry(flags, TAME_SADDLED_STANDING, ByteBuffer.allocate(0))));
    }

    /**
     * One client tick: the rider's Rot, the horse's move and the tick end. AbstractHorse#tickRidden turns the horse to
     * the rider's yaw, wrapped by Entity#setYRot, and half the rider's pitch.
     */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, YAW, PITCH, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(
                new Vec3d(to.x, to.y, to.z), YAW % 360.0F, PITCH * 0.5F, to.y == FLOOR_Y, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }
}
