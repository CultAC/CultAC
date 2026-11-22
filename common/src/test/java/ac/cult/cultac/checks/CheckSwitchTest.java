package ac.cult.cultac.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.checks.impl.combat.FairReach;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.checks.verdict.FlagOutcome;
import ac.cult.cultac.manager.PunishmentManager;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CheckSwitchTest {
    private static final Vec3 SAFE = new Vec3(4, 64, 2);

    @BeforeAll
    static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    void aSwitchedOffCheckNeitherFlagsNorSetsBackNorChangesPacketsUntilSwitchedOn() {
        OfflinePlayers.withSafePlayer(SAFE, player -> {
            Check check = probe(player, 0);
            check.setEnabled(true);
            check.setSwitchedOff(true);

            assertFalse(check.isEnabled());
            assertFalse(check.shouldModifyPackets());
            assertFalse(check.flagWithSetback("offset"));
            assertFalse(check.executeViolationSetback());
            assertEquals(0, check.getViolations());
            assertNull(player.getSetbackTeleportUtil().getRequiredSetBack());

            List<FlagOutcome> deferred = new ArrayList<>();
            player.setFlagArbiter((flagged, verbose, prospective, outcome) -> deferred.add(outcome));
            check.setSwitchedOff(false);
            assertTrue(check.flag("pending"));
            check.setSwitchedOff(true);
            deferred.getFirst().commit();
            assertEquals(0, check.getViolations());
            player.setFlagArbiter(null);

            check.setSwitchedOff(false);
            assertTrue(check.isEnabled());
            assertTrue(check.shouldModifyPackets());
            assertTrue(check.flagWithSetback("offset"));
            assertEquals(1, check.getViolations());
            assertNotNull(player.getSetbackTeleportUtil().getRequiredSetBack());
        });
    }

    @Test
    void aSwitchedOffReachCheckCancelsNoAttack() {
        OfflinePlayers.withSafePlayer(SAFE, player -> {
            int target = 3000;
            player.compensatedEntities.addEntity(target, EntityTypeIds.PLAYER, new Vec3(40, 64, 2), 0, 0, 0);
            FairReach fairReach = player.checkManager.getCheck(FairReach.class);
            fairReach.onAddEntity(
                    null, player, new ClientboundAddEntity(target, "minecraft:player", new Vec3d(40, 64, 2), 0, 0, 0));
            PlayerReach reach = player.checkManager.getCheck(PlayerReach.class);
            ServerboundInteract attack =
                    new ServerboundInteract(target, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            for (int i = 0; i <= 10; i++) cancels(player, reach::onInteract, attack);

            for (boolean off : new boolean[] {true, false}) {
                fairReach.setSwitchedOff(off);
                reach.setSwitchedOff(off);
                assertEquals(!off, cancels(player, fairReach::onInteract, attack), "out of reach, off=" + off);
                assertEquals(!off, cancels(player, reach::onInteract, attack), "queue full, off=" + off);
                player.packetStateData.localPlayerTickedSinceSpawn = false;
                assertEquals(!off, cancels(player, reach::onInteract, attack), "not ticked, off=" + off);
                player.packetStateData.localPlayerTickedSinceSpawn = true;
            }
        });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aSwitchHoldsThroughAPunishmentsReload() {
        CultPlayer player = mock(CultPlayer.class);
        Check check = probe(player, 1);
        doReturn(List.of(check)).when(player).getChecks();
        ConfigManager config = mock(ConfigManager.class);
        when(config.getBooleanElse(anyString(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getIntElse(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getDoubleElse(anyString(), anyDouble())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getStringElse(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getStringListElse(eq("Punishments"), any())).thenReturn((List) List.of(
                new LinkedHashMap<>(Map.of("checks", List.of("switchprobe"), "commands", List.of("1:0 [alert]")))));

        check.setSwitchedOff(true);
        new PunishmentManager(player).reload(config);
        assertFalse(check.isEnabled());

        check.setSwitchedOff(false);
        assertTrue(check.isEnabled());
    }

    private interface InteractHandler {
        void handle(PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet);
    }

    private static boolean cancels(CultPlayer player, InteractHandler handler, ServerboundInteract attack) {
        var event = new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, attack);
        handler.handle(event, player, attack);
        return event.isCancelled();
    }

    private static Check probe(CultPlayer player, double setbackVl) {
        return new Check(
                player,
                CheckInfo.builder()
                        .name("SwitchProbe")
                        .stableKey("cult.test.switch_probe")
                        .setback(setbackVl)
                        .build()) {};
    }
}
