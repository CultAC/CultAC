package ac.cult.cultac.network;

import static ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item;
import static ac.cult.cultac.network.ItemUseTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsA;
import ac.cult.cultac.events.packets.PacketChangeGameState;
import ac.cult.cultac.events.packets.listeners.PacketPlayerCooldown;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCooldown;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundGameEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.GameEventType;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.latency.CompensatedCooldown;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Game-mode, respawn and hunger transitions refuse item-use starts until the client has provably
 * handled them; a ping written before the packet proves nothing about it.
 */
class UseLegalityTransitionTest {
    @Test
    void aCreativeGrantDoesNotStartFullHungerFoodBeforeItsProof() {
        try (var fixture = fixture()) {
            var player = survival(fixture, item("minecraft:bread", 2));
            var sent = gameMode(player, 1);
            ackPrecedingPing(player, sent);
            assertEquals(GameMode.CREATIVE, player.gamemode);
            assertFalse(player.isInvulnerable);
            assertOffhandRefused(player, 1);

            ackTrailingProof(player, sent);
            assertTrue(player.isInvulnerable);
            assertTrue(player.canInstabuild);
            useOffhand(player, 2);
            assertTrue(player.isClientUsingItem());
            assertTrue(walkingFlagsNoSlow(player));
        }
    }

    @Test
    void aCreativeGrantDoesNotStartANoAmmoBowBeforeItsProof() {
        try (var fixture = fixture()) {
            var player = survival(fixture, item("minecraft:bow"));
            var sent = gameMode(player, 1);
            ackPrecedingPing(player, sent);
            assertFalse(player.canInstabuild);
            assertOffhandRefused(player, 1);

            ackTrailingProof(player, sent);
            useOffhand(player, 2);
            assertTrue(player.isClientUsingItem());
        }
    }

    @Test
    void aCreativeRevocationAppliesAtItsBundledProof() {
        try (var fixture = fixture()) {
            var player = creative(fixture);
            var sent = gameMode(player, 0);
            ackPrecedingPing(player, sent);
            assertTrue(player.isInvulnerable);
            assertTrue(player.canInstabuild);

            ackTrailingProof(player, sent);
            assertFalse(player.isInvulnerable);
            assertFalse(player.canInstabuild);
            assertOffhandRefused(player, 1);
        }
    }

    @Test
    void withNoTransactionARevocationRefusesStartsAtOnce() {
        try (var fixture = fixture()) {
            var player = creative(fixture);
            int sent = player.lastTransactionSent.get();
            withoutTransactions(fixture, () -> gameMode(player, 0));
            acknowledge(player, sent);
            assertFalse(player.isInvulnerable);
            assertFalse(player.canInstabuild);
            assertOffhandRefused(player, 1);
        }
    }

    @Test
    void aStandaloneRespawnIntoSurvivalRefusesStartsAtOnceAndAppliesItsAbilitiesAndFoodAtItsProof() {
        try (var fixture = fixture()) {
            var player = survival(fixture, item("minecraft:bread", 2));
            acknowledge(player, login(player, GameMode.CREATIVE));
            setOffhand(player, item("minecraft:bread", 2));
            player.food = 10;

            // PacketBundlePolicy keeps a respawn outside bundles. Its trailing ping follows it in FIFO order but
            // is not handled in the same task, so the client may be the new survival LocalPlayer before the pong.
            int proof = respawn(player, "minecraft:overworld", GameMode.SURVIVAL);
            assertFalse(player.isInvulnerable);
            assertFalse(player.canInstabuild);
            assertEquals(20, player.food);

            acknowledge(player, proof);
            assertFalse(player.isInvulnerable);
            assertFalse(player.canInstabuild);
            assertEquals(20, player.food);
            useOffhand(player, 1);
            assertFalse(player.isClientUsingItem());
            assertNoAttackCancellation(fixture, player);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnbundledRespawnIntoSurvivalRefusesStartsAtOnce(boolean bedrock) {
        try (var fixture = fixture()) {
            var player = survival(fixture, item("minecraft:bread", 2));
            acknowledge(player, login(player, GameMode.CREATIVE));
            setOffhand(player, item("minecraft:bread", 2));
            player.food = 10;

            if (bedrock) {
                player.movementPlatform = MovementPlatform.BEDROCK;
                respawn(player, "minecraft:overworld", GameMode.SURVIVAL);
            } else {
                withoutTransactions(fixture, () -> respawn(player, "minecraft:overworld", GameMode.SURVIVAL));
            }
            assertFalse(player.isInvulnerable);
            assertFalse(player.canInstabuild);
            assertEquals(20, player.food);
            assertNoAttackCancellation(fixture, player);
        }
    }

    @Test
    void withNoTransactionAGrantNeverPermitsAStart() {
        try (var fixture = fixture()) {
            var player = survival(fixture, item("minecraft:bread", 2));
            int sent = player.lastTransactionSent.get();
            withoutTransactions(fixture, () -> {
                gameMode(player, 1);
                abilities(player, true, true);
            });
            assertEquals(sent, player.lastTransactionSent.get());
            acknowledge(player, sent);
            assertEquals(GameMode.CREATIVE, player.gamemode);
            assertFalse(player.isInvulnerable);
            assertFalse(player.canInstabuild);
            assertOffhandRefused(player, 1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void withNoTransactionAGrantAppliesAtTheNextPing(boolean viaGameMode) {
        try (var fixture = fixture()) {
            var player = survival(fixture, item("minecraft:bread", 2));
            withoutTransactions(fixture, () -> {
                if (viaGameMode) gameMode(player, 1);
                else abilities(player, true, true);
            });
            player.sendTransaction();
            acknowledge(player, player.lastTransactionSent.get());
            assertTrue(player.isInvulnerable);
            assertTrue(player.canInstabuild);
            useOffhand(player, 1);
            assertTrue(player.isClientUsingItem());
        }
    }

    @Test
    void withNoTransactionARevocationStillRefusesStarts() {
        try (var fixture = fixture()) {
            var player = creative(fixture);
            withoutTransactions(fixture, () -> abilities(player, false, false));
            assertFalse(player.isInvulnerable);
            assertFalse(player.canInstabuild);

            player.canInstabuild = true;
            player.isInvulnerable = true;
            withoutTransactions(fixture, () -> gameMode(player, 0));
            assertFalse(player.isInvulnerable);
            assertFalse(player.canInstabuild);
            assertOffhandRefused(player, 1);
        }
    }

    @Test
    void unprovenHungerDropKeepsTheHigherFood() {
        try (var fixture = fixture()) {
            var player = survival(fixture, item("minecraft:bread", 2));
            player.clientItemUse.markPossiblyFull();
            int sent = player.lastTransactionSent.get();
            withoutTransactions(fixture, () -> health(player, 16));
            assertEquals(sent, player.lastTransactionSent.get());
            acknowledge(player, sent);
            assertEquals(20, player.food);
            assertTrue(player.clientItemUse.possiblyFull());
            assertOffhandRefused(player, 1);
        }
    }

    @Test
    void unprovenCooldownAndClearApplyAtThePrecedingPing() throws Exception {
        try (var fixture = fixture()) {
            var shield = item("minecraft:shield");
            var player = survival(fixture, shield);
            var cooldowns = player.checkManager.getCompensatedCooldown();
            String group = CompensatedCooldown.group(shield);
            int sent = player.lastTransactionSent.get();
            withoutTransactions(fixture, () -> cooldown(player, group, 20));
            assertEquals(sent, player.lastTransactionSent.get());
            acknowledge(player, sent);
            assertTrue(cooldowns.hasItem(shield));

            withoutTransactions(fixture, () -> cooldown(player, group, 0));
            assertFalse(cooldowns.hasItem(shield));
        }
    }

    @Test
    void bundledGameEventAppliesAtItsProof() {
        try (var fixture = fixture()) {
            var player = survival(fixture, item("minecraft:bread", 2));
            var packet = new ClientboundGameEvent(GameEventType.CHANGE_GAME_MODE, 1);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.GAME_EVENT, packet, true);
            int start = player.lastTransactionSent.get();
            new PacketChangeGameState(player).onGameEvent(event, player, packet);
            var sent = Sent.of(player, event, start);
            assertFalse(event.isBundleRequested());
            assertEquals(sent.after(), sent.preceding() + 1);

            ackPrecedingPing(player, sent);
            assertFalse(player.isInvulnerable);
            assertOffhandRefused(player, 1);

            ackTrailingProof(player, sent);
            assertTrue(player.isInvulnerable);
            useOffhand(player, 2);
            assertTrue(player.isClientUsingItem());
        }
    }

    private static CultPlayer survival(RecordReceiveFixture fixture, SimItemStack offhand) {
        var player = fixture.player;
        player.gamemode = GameMode.SURVIVAL;
        player.canInstabuild = false;
        player.isInvulnerable = false;
        player.food = 20;
        player.entityID = 7;
        setOffhand(player, offhand);
        return player;
    }

    private static CultPlayer creative(RecordReceiveFixture fixture) {
        var player = survival(fixture, item("minecraft:bread", 2));
        player.gamemode = GameMode.CREATIVE;
        player.canInstabuild = true;
        player.isInvulnerable = true;
        return player;
    }

    private static Sent gameMode(CultPlayer player, int mode) {
        var packet = new ClientboundGameEvent(GameEventType.CHANGE_GAME_MODE, mode);
        var event = sendEvent(player, ClientboundPackets.GAME_EVENT, packet);
        int start = player.lastTransactionSent.get();
        new PacketChangeGameState(player).onGameEvent(event, player, packet);
        return Sent.of(player, event, start);
    }

    /** CultPlayer writes no ping while the encoder is outside PLAY and CONFIGURATION. */
    private static void withoutTransactions(RecordReceiveFixture fixture, Runnable sends) {
        fixture.connection.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.LOGIN);
        try {
            sends.run();
        } finally {
            fixture.connection.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.PLAY);
        }
    }

    private static Sent cooldown(CultPlayer player, String group, int ticks) {
        var packet = new ClientboundCooldown(group, ticks);
        var event = sendEvent(player, ClientboundPackets.COOLDOWN, packet);
        int preceding = player.lastTransactionSent.get();
        new PacketPlayerCooldown().onCooldown(event, player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return new Sent(event, preceding, player.lastTransactionSent.get());
    }

    private static void assertOffhandRefused(CultPlayer player, int sequence) {
        useOffhand(player, sequence);
        assertFalse(player.isClientUsingItem());
        assertFalse(walkingFlagsNoSlow(player));
    }

    private static void assertNoAttackCancellation(RecordReceiveFixture fixture, CultPlayer player) {
        player.setExperimentalChecks(true);
        var check = player.checkManager.getCheck(MultiActionsA.class);
        check.setEnabled(true);
        double before = check.violations;
        var packet = new ServerboundInteract(42, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
        var event = new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, packet);
        check.onInteractEntity(event, player, packet);
        assertFalse(event.isCancelled());
        assertEquals(before, check.violations);
    }
}
