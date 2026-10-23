package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class JavaPistonSlimeLaunchTest {
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final double FALL = 0.0784000015258789;
    private static final double PLAYER_HALF_WIDTH = 0.6F / 2.0F;
    private static final float ICE = 0.98F;
    private static final double ICE_FRICTION = ICE * 0.91F;
    private static final float SPRINT_SPEED = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    // LivingEntity#getFrictionInfluencedSpeed on the ground and Player#getFlyingSpeed in the air, scaled by the
    // 0.98F forward impulse LivingEntity#aiStep leaves in zza.
    private static final double ICE_SPRINT = (double) 0.98F * (SPRINT_SPEED * (0.21600002F / (ICE * ICE * ICE)));
    private static final double AIR_SPRINT = (double) 0.98F * 0.025999999F;

    /**
     * A sprinting 26.1 client on ice into a slime block that a sticky piston pushes north. Minecraft#tick runs
     * the piston's first PistonMovingBlockEntity#tick after LocalPlayer#sendPosition. Its moveCollidedEntities sets
     * the Z delta movement to the north step for the slime block and shoves the player with Entity#move(PISTON),
     * whose horizontal-only delta leaves Entity#onGround false. The next travel therefore uses the flying speed and
     * the 0.91 air friction, and the tick after it slides on ice again.
     */
    @Test
    void aSlimePistonLaunchOnIceCarriesTheAirborneTravel() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_1);
            int ice = REGISTRY.block("minecraft:ice").defaultState();
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), ice);
                }
            }
            BlockPos piston = new BlockPos(0, FLOOR_Y + 1, 2);
            int stickyPiston =
                    REGISTRY.with(REGISTRY.block("minecraft:sticky_piston").defaultState(), "facing", "north");
            f.player.compensatedWorld.updateBlock(piston, stickyPiston);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y + 1, 1),
                    REGISTRY.block("minecraft:slime_block").defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.2);
            double velocity = 0.1;
            f.place(position, new Vec3(0, -FALL, velocity));
            var command = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);

            f.input(SPRINT);
            velocity += ICE_SPRINT;
            position = position.add(new Vec3(0, 0, velocity));
            velocity *= ICE_FRICTION;
            assertExact(f.tick(position, true));

            // Level#blockEvent reaches PistonBaseBlock#triggerEvent before this tick's LocalPlayer#tick.
            blockEvent(f, piston, 0, 2, stickyPiston);
            f.input(SPRINT);
            velocity += ICE_SPRINT;
            position = position.add(new Vec3(0, 0, velocity));
            velocity *= ICE_FRICTION;
            assertExact(f.tick(position, true));

            // PistonMovingBlockEntity#moveCollidedEntities: the slime's movement area at progress 0 ends at the
            // slime's north face z = 1 and reaches deltaProgress 0.5 north of it; the shove is the entity's depth
            // into it plus 0.01.
            double shove = Math.min(position.z + PLAYER_HALF_WIDTH - 0.5, 0.5) + 0.01;
            double launched = -1.0 + AIR_SPRINT;
            position = position.add(new Vec3(0, 0, -shove + launched));
            velocity = launched * 0.91F;
            assertExact(f.tick(position, true));

            for (int tick = 0; tick < 3; tick++) {
                f.input(SPRINT);
                velocity += ICE_SPRINT;
                position = position.add(new Vec3(0, 0, velocity));
                velocity *= ICE_FRICTION;
                assertExact(f.tick(position, true));
            }
        }
    }

    private static void blockEvent(JavaTickFixture f, BlockPos pos, int action, int parameter, int state) {
        var packet = new ClientboundBlockEvent(pos, action, parameter, REGISTRY.blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
