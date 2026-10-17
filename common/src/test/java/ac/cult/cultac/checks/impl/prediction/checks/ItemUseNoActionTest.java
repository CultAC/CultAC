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

class ItemUseNoActionTest {
    private static final double SPEED = 0.13;
    private static final double WALK = 0.98 * 0.1;
    private static final double SLOWED = 0.98 * 0.2 * 0.1;

    @Test
    void cancelledClickStopNeverFlagsNoSlow() {
        run(rig -> {
            var shield = stack("minecraft:shield");
            rig.setOffhand(shield);
            rig.player.clientItemUse.start(Hand.OFF_HAND, shield, ClientItemUseState.duration(shield), true);
            rig.move(SLOWED);
            // The click is predicted locally, then the cancelled server resyncs the same shield.
            rig.setOffhand(SimItemStack.EMPTY);
            rig.move(WALK);
            rig.setOffhand(stack("minecraft:shield"));
            for (int i = 0; i < 200; i++) rig.move(WALK);
            assertTrue(rig.player.clientItemUse.hasObligation());
            assertFalse(rig.player.clientItemUse.definitelyUsing());
        });
    }

    @Test
    void silentHotbarStopWithoutReleaseNeverFlagsNoSlow() {
        run(rig -> {
            var shield = stack("minecraft:shield");
            rig.setHotbar(0, shield);
            rig.setHotbar(1, stack("minecraft:iron_sword"));
            rig.player.clientItemUse.start(Hand.MAIN_HAND, shield, ClientItemUseState.duration(shield), true);
            for (int i = 0; i < 200; i++) rig.move(WALK);
            assertTrue(rig.player.clientItemUse.hasObligation());
        });
    }

    @Test
    void brushRayMissStopNeverFlagsNoSlow() {
        run(rig -> {
            var brush = stack("minecraft:brush");
            rig.setHotbar(0, brush);
            rig.player.clientItemUse.start(Hand.MAIN_HAND, brush, ClientItemUseState.duration(brush), true);
            for (int i = 0; i < 200; i++) rig.move(WALK);
            assertTrue(rig.player.clientItemUse.hasObligation());
        });
    }

    @Test
    void deterministicOffhandNoSlowStillFlags() {
        run(rig -> {
            var shield = stack("minecraft:shield");
            rig.setOffhand(shield);
            rig.player.clientItemUse.start(Hand.OFF_HAND, shield, ClientItemUseState.duration(shield), true);
            rig.move(WALK);
            assertEquals(rig.noSlowBefore + 1, rig.noSlow.violations);
            rig.noSlowBefore = rig.noSlow.violations;
        });
    }

    private static void run(Consumer<Rig> scenario) {
        OfflineCultTestBootstrap.installConfig();
        var channel = new EmbeddedChannel();
        var user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".Use_NoAction_Test"), channel);
        var original = new CultPlayer(user);
        try {
            var rig = new Rig(original);
            scenario.accept(rig);
            assertEquals(rig.noSlowBefore, rig.noSlow.violations);
            verifyNoInteractions(rig.setbacks);
            verify(rig.platform, never()).clearActiveItem();
            verify(rig.platform, never()).getItemUseState();
        } finally {
            original.onRemove();
            channel.finishAndReleaseAll();
        }
    }

    private static SimItemStack stack(String item) {
        return ItemUtil.modelItems().stack(item, 1);
    }

    private static final class Rig {
        final CultPlayer player;
        final PlatformPlayer platform = mock(PlatformPlayer.class);
        final SetbackTeleportUtil setbacks = mock(SetbackTeleportUtil.class);
        final NoSlow noSlow;
        final ItemUseAnalyzer analyzer = new ItemUseAnalyzer();
        double noSlowBefore;

        Rig(CultPlayer original) {
            player = spy(original);
            player.gamemode = GameMode.SURVIVAL;
            player.platformPlayer = platform;
            player.noSetbackPermission = false;
            doReturn(setbacks).when(player).getSetbackTeleportUtil();
            noSlow = player.checkManager.getListener(NoSlow.class);
            noSlowBefore = noSlow.violations;
        }

        void setOffhand(SimItemStack stack) {
            player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, stack);
        }

        void setHotbar(int slot, SimItemStack stack) {
            player.getInventory().inventory.getInventoryStorage().setItem(Inventory.HOTBAR_OFFSET + slot, stack);
        }

        /** One accepted client tick: forward input of the given length, through the real input analysis. */
        void move(double forward) {
            var snapshot = ItemUseInventory.prepare(player);
            var context = mock(SimulationContext.class);
            when(context.getItemUse()).thenReturn(snapshot);
            when(context.getVersion()).thenReturn(ClientVersion.V_26_3);
            when(context.getMaxSprintInputSpeed(any())).thenReturn(SPEED);
            var realities = new ArrayList<PredictionResult>();
            var result = new PredictionResult(
                    player, new PredVector(Vec3.ZERO), context, new Vec3(0, 0, forward), null, realities, realities);
            result.setProfilePredictionVector(Vec3.ZERO);
            realities.add(result);
            analyzer.handleResult(player, result, null);
            noSlow.onPredictionComplete(new PredictionComplete(result));
            player.packetStateData.acceptedClientTick++;
        }
    }
}
