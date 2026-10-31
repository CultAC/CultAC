package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A ridden horse falls from rest through a vine onto powder snow. LivingEntity#handleOnClimbable resets the fall
 * distance only through the entity's own onClimbable, and AbstractHorse overrides it to return false, so the vine
 * keeps the horse's fall distance. Past 2.5 blocks PowderSnowBlock#getCollisionShape gives the horse its 0.9F
 * FALLING_COLLISION_SHAPE, and the twelfth move lands on it.
 */
class JavaRiddenHorseClimbableFallTest {
    private static final int HORSE = 100;
    private static final Vec3 START = new Vec3(0.5, 72.1, 0.5);
    private static final BlockPos VINE = new BlockPos(0, 68, 0);
    private static final BlockPos POWDER_SNOW = new BlockPos(0, 67, 0);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    /**
     * The last moves before the landing start inside the vine with more than 2.5 blocks of fall distance. Through
     * 1.21.4 Entity#move skips checkFallDamage for an entity the client controls, so the 1.21.4 horse never gains a
     * fall distance and falls through the powder snow.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void theVineKeepsTheHorsesFallDistance(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.compensatedWorld.updateBlock(VINE, BlockIds.VINE.defaultState());
            f.player.compensatedWorld.updateBlock(POWDER_SNOW, BlockIds.POWDER_SNOW.defaultState());
            mount(f);
            f.input(NONE);
            // Through 1.21.4 Entity#isEffectiveAi is false on the client, so LivingEntity#aiStep scales the
            // horse's velocity by 0.98 before each travel.
            double aiStepDrag = version.isOlderThan(ClientVersion.V_1_21_5) ? 0.98 : 1.0;
            double y = START.y;
            double velocity = 0.0;
            for (int tick = 0; tick < 11; tick++) {
                velocity *= aiStepDrag;
                y += velocity;
                assertVehicleExact(f.rideTick(new Vec3(START.x, y, START.z), false), tick);
                // LivingEntity#travelInAir: the horse's 0.08 gravity, then 0.98F vertical drag.
                velocity = (velocity - 0.08) * 0.98F;
            }
            boolean accumulates = version != ClientVersion.V_1_21_4;
            double landing = accumulates ? POWDER_SNOW.y() + (double) 0.9F : y + velocity * aiStepDrag;
            assertVehicleExact(f.rideTick(new Vec3(START.x, landing, START.z), accumulates), 11);
        }
    }

    /** A saddled horse at rest at {@link #START} with the player riding it. */
    private static void mount(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(START, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, START, 0, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.onGround = false;
        f.setPassengers(HORSE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(Vec3.ZERO);
    }
}
