package ac.cult.cultac.checks.impl.prediction.checks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.ItemUseInventory;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class NoSlowClearTest {
    private static final double SPEED = 0.13;
    private static final double WALK = 0.98 * 0.1;
    private static final double SLOWED = 0.98 * 0.2 * 0.1;
    private static final double ABOVE_SETBACK = 10;

    @Test
    void inFlightFlagThatSetsBackClearsTheActiveItemOnce() {
        run(rig -> {
            rig.useShield();
            rig.noSlow.violations = ABOVE_SETBACK;
            doReturn(true).when(rig.setbacks).executeViolationSetback();
            rig.move(WALK);
            assertEquals(ABOVE_SETBACK + 1, rig.noSlow.violations);
            verify(rig.setbacks).executeViolationSetback();
            verify(rig.platform, times(1)).clearActiveItem(Hand.OFF_HAND, "minecraft:shield");
            verify(rig.platform, times(1)).clearActiveItem(any(), any());
        });
    }

    @Test
    void inFlightFlagBelowTheSetbackThresholdNeverClears() {
        run(rig -> {
            rig.useShield();
            rig.move(WALK);
            assertEquals(1, rig.noSlow.violations);
            verify(rig.setbacks, never()).executeViolationSetback();
            verify(rig.platform, never()).clearActiveItem(any(), any());
        });
    }

    @Test
    void observeOnlySetbackRefusalNeverClears() {
        run(rig -> {
            rig.useShield();
            rig.noSlow.violations = ABOVE_SETBACK;
            doReturn(false).when(rig.setbacks).executeViolationSetback();
            rig.move(WALK);
            assertEquals(ABOVE_SETBACK + 1, rig.noSlow.violations);
            verify(rig.setbacks).executeViolationSetback();
            verify(rig.platform, never()).clearActiveItem(any(), any());
        });
    }

    @Test
    void slowedMovementNeverClears() {
        run(rig -> {
            rig.useShield();
            rig.noSlow.violations = ABOVE_SETBACK;
            doReturn(true).when(rig.setbacks).executeViolationSetback();
            for (int i = 0; i < 20; i++) rig.move(SLOWED);
            assertTrue(rig.noSlow.violations <= ABOVE_SETBACK);
            verify(rig.setbacks, never()).executeViolationSetback();
            verify(rig.platform, never()).clearActiveItem(any(), any());
        });
    }

    @Test
    void unflaggedMovementWithoutUseNeverClears() {
        run(rig -> {
            rig.noSlow.violations = ABOVE_SETBACK;
            doReturn(true).when(rig.setbacks).executeViolationSetback();
            for (int i = 0; i < 20; i++) rig.move(WALK);
            assertEquals(ABOVE_SETBACK, rig.noSlow.violations);
            verify(rig.setbacks, never()).executeViolationSetback();
            verify(rig.platform, never()).clearActiveItem(any(), any());
        });
    }

    @Test
    void unslowedMainHandUseIsCorrectedByAdvancingTheRejectedTick() {
        run(rig -> {
            rig.useMainHandShield();
            for (int i = 0; i < 20; i++) rig.move(WALK);
            // A hotbar key at handleKeybinds can stop a main-hand use before travel without a packet.
            assertEquals(0, rig.noSlow.violations);
            assertEquals(20, rig.desync.history().desynced());
            // Each rejected movement is a client tick; a correction that does not advance it pins the player.
            verify(rig.setbacks, times(20)).executeViolationSetback();
            verify(rig.setbacks, never()).executeNonSimulatingSetback();
        });
    }

    private static void run(Consumer<Rig> scenario) {
        OfflineCultTestBootstrap.installConfig();
        var channel = new EmbeddedChannel();
        var user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".NoSlow_Clear_Test"), channel);
        var original = new CultPlayer(user);
        try {
            scenario.accept(new Rig(original));
        } finally {
            original.onRemove();
            channel.finishAndReleaseAll();
        }
    }

    private static final class Rig {
        final CultPlayer player;
        final PlatformPlayer platform = mock(PlatformPlayer.class);
        final SetbackTeleportUtil setbacks = mock(SetbackTeleportUtil.class);
        final NoSlow noSlow;
        final ItemUseDesync desync;
        final ItemUseAnalyzer analyzer = new ItemUseAnalyzer();

        Rig(CultPlayer original) {
            player = spy(original);
            player.gamemode = GameMode.SURVIVAL;
            player.platformPlayer = platform;
            player.noSetbackPermission = false;
            doReturn(setbacks).when(player).getSetbackTeleportUtil();
            noSlow = new NoSlow(player);
            noSlow.reload();
            noSlow.violations = 0;
            desync = new ItemUseDesync(player);
            desync.reload();
        }

        void useMainHandShield() {
            SimItemStack shield = ItemUtil.modelItems().stack("minecraft:shield", 1);
            player.getInventory().inventory.setHeldItem(shield);
            player.clientItemUse.start(Hand.MAIN_HAND, shield, ClientItemUseState.duration(shield), true);
        }

        void useShield() {
            SimItemStack shield = ItemUtil.modelItems().stack("minecraft:shield", 1);
            player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, shield);
            player.clientItemUse.start(Hand.OFF_HAND, shield, ClientItemUseState.duration(shield), true);
        }

        /** One accepted client tick: forward input of the given length, through the real input analysis. */
        void move(double forward) {
            var snapshot = ItemUseInventory.prepare(player);
            var context = mock(SimulationContext.class);
            when(context.getItemUse()).thenReturn(snapshot);
            when(context.getVersion()).thenReturn(ClientVersion.V_26_3);
            when(context.getMaxSprintInputSpeed(any(), any())).thenReturn(SPEED);
            var realities = new ArrayList<PredictionResult>();
            var result = new PredictionResult(
                    player, new PredVector(Vec3.ZERO), context, new Vec3(0, 0, forward), null, realities, realities);
            result.setProfilePredictionVector(Vec3.ZERO);
            realities.add(result);
            analyzer.handleResult(player, result, null);
            var complete = new PredictionComplete(result);
            noSlow.onPredictionComplete(complete);
            desync.onPredictionComplete(complete);
            desync.onPlayerTickEnd(null);
            player.packetStateData.acceptedClientTick++;
        }
    }
}
