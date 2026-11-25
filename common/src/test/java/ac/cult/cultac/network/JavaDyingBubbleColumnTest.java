package ac.cult.cultac.network;

import ac.cult.cultac.manager.player.SetbackFixtures;
import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHealth;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

/**
 * A player dies inside a tall bubble column. Entity#checkInsideBlocks stops at !isAlive(), so
 * BubbleColumnBlock#entityInside pushes the player only while LivingEntity#isAlive: the DATA_HEALTH_ID the client has
 * handled, which LivingEntity#isImmobile also reads. SET_HEALTH's leading ping does not prove that: the client can
 * answer it on one tick and handle the health, with its trailing ping, after that tick's travel.
 */
class JavaDyingBubbleColumnTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int TOP = FLOOR_Y + 10;
    private static final int ALIVE_TICKS = 4;
    private static final int DEAD_TICKS = 8;

    @ParameterizedTest(name = "{0} drag {1} health handled after the tick {2}")
    @CsvSource({
        "V_1_21_4, false, false",
        "V_1_21_4, false, true",
        "V_1_21_4, true, true",
        "V_1_21_11, false, false",
        "V_1_21_11, false, true",
        "V_1_21_11, true, false",
        "V_1_21_11, true, true",
        "V_26_3, false, false",
        "V_26_3, false, true",
        "V_26_3, true, false",
        "V_26_3, true, true"
    })
    void theColumnPushesADyingPlayerUntilItHandlesTheHealth(String version, boolean drag, boolean late)
            throws Exception {
        try (var f = column(ClientVersion.valueOf(version), drag)) {
            PacketEntitySelf self = f.player.compensatedEntities.getSelf();
            double y = FLOOR_Y + 3;
            double v = 0;
            for (int tick = 0; tick < ALIVE_TICKS + DEAD_TICKS; tick++) {
                boolean deathTick = tick == ALIVE_TICKS;
                int trailing = -1;
                if (deathTick) {
                    int leading = sendZeroHealth(f);
                    trailing = f.player.lastTransactionSent.get();
                    f.pongThrough(late ? leading : trailing);
                    assertTrue(self.isDead, "SET_HEALTH's leading ping was answered");
                    assertEquals(late, self.actionAlive, "the client handled the health before this tick");
                }
                boolean alive = self.actionAlive;
                // LivingEntity#aiStep zeroes a vertical motion below 0.003.
                if (Math.abs(v) < 0.003) v = 0;
                y += v;
                var move = f.move(new Vec3(0.5, y, 0.5), false);
                f.tickEnd();
                var result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
                assertTrue(JavaTickFixture.exact(result), "tick " + tick + " " + JavaTickFixture.describe(result));
                // The server sent the death, so it never receives this move, even one the client travelled alive.
                assertEquals(self.isDead, move.isCancelled(), "tick " + tick);
                if (deathTick && late) f.pongThrough(trailing);
                // LivingEntity#travelInWater, then getFluidFallingAdjustedMovement's gravity / 16.
                v = v * 0.8F - 0.08 / 16;
                if (alive) v = push(v, y, drag);
            }
            assertFalse(self.actionAlive);
        }
    }

    /** BubbleColumnBlock#entityInside for each column block the end box, deflated by 1.0E-5, intersects. */
    private static double push(double v, double y, boolean drag) {
        for (int block = (int) Math.floor(y + 1.0E-5); block <= (int) Math.floor(y + 1.8 - 1.0E-5); block++) {
            // Entity#onInsideBubbleColumn: water above every column block.
            v = drag ? Math.max(-0.3, v - 0.03) : Math.min(0.7, v + 0.06);
        }
        return v;
    }

    /** A loaded player at rest inside an 11-block column with water above it. */
    private static JavaTickFixture column(ClientVersion version, boolean drag) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.nettyScheduler.removeScheduler();
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        int column = BLOCKS.with(BlockIds.BUBBLE_COLUMN.defaultState(), "drag", Boolean.toString(drag));
        for (int y = FLOOR_Y; y <= TOP; y++) f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), column);
        for (int y = TOP + 1; y <= TOP + 3; y++)
            f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), BlockIds.WATER.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        Vec3 at = new Vec3(0.5, FLOOR_Y + 3, 0.5);
        f.place(at, Vec3.ZERO);
        f.player.onGround = f.player.lastOnGround = false;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
        SetbackFixtures.seedSafePosition(f.player, new SetbackPosWithVector(at, Vec3.ZERO));
        f.player.packetStateData.playerLoadedIntoLevel = true;
        f.input(NONE);
        return f;
    }

    /** The server's SET_HEALTH of 0, not yet answered; the transaction of the ping written before it. */
    private static int sendZeroHealth(JavaTickFixture f) {
        int leading = f.player.lastTransactionSent.get() + 1;
        var packet = new ClientboundSetHealth(0.0F, 20, 5.0F);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_HEALTH, packet, false);
        new PacketPlayerRespawn().onSetHealth(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        assertTrue(f.player.lastTransactionSent.get() > leading, "SET_HEALTH has a ping after it");
        return leading;
    }
}
