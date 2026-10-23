package ac.cult.cultac.network;

import static ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item;
import static ac.cult.cultac.network.ItemUseTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.events.packets.listeners.PacketPlayerCooldown;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCooldown;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.data.ItemUseInventory;
import org.junit.jupiter.api.Test;

/**
 * Item-use start legality inputs (abilities, food, cooldowns) are client state only once the client has
 * handled their packet. A pong for a ping written before the packet proves nothing about it.
 */
class UseStartInputProofTest {
    @Test
    void invulnerabilityGrantIsNotAdoptedAtThePrecedingPing() {
        try (var fixture = fixture()) {
            var player = survivalWithFullHungerOffhandFood(fixture);
            var event = abilities(player, true, false);
            ackPrecedingPing(player, event);
            assertFalse(player.isInvulnerable);
            assertOffhandRefused(player, 1);

            ackTrailingProof(player, event);
            assertTrue(player.isInvulnerable);
            player.packetStateData.acceptedClientTick++;
            useOffhand(player, 2);
            assertTrue(player.isClientUsingItem());
            assertEquals(Hand.OFF_HAND, player.getClientItemUseHand());
        }
    }

    @Test
    void instabuildGrantIsNotAdoptedBeforeANoAmmoBowAttempt() {
        try (var fixture = fixture()) {
            var player = fixture.player;
            player.gamemode = GameMode.SURVIVAL;
            setOffhand(player, item("minecraft:bow"));
            var event = abilities(player, false, true);
            ackPrecedingPing(player, event);
            assertFalse(player.canInstabuild);
            assertOffhandRefused(player, 1);

            ackTrailingProof(player, event);
            assertTrue(player.canInstabuild);
            player.packetStateData.acceptedClientTick++;
            useOffhand(player, 2);
            assertTrue(player.isClientUsingItem());
        }
    }

    @Test
    void revocationAppliesAtTheBundledProof() {
        try (var fixture = fixture()) {
            var player = survivalWithFullHungerOffhandFood(fixture);
            player.isInvulnerable = true;
            var event = abilities(player, false, false);
            ackPrecedingPing(player, event);
            assertTrue(player.isInvulnerable);

            ackTrailingProof(player, event);
            assertFalse(player.isInvulnerable);
            assertOffhandRefused(player, 1);
        }
    }

    @Test
    void bedrockRevocationRefusesStartsFromThePrecedingPing() {
        try (var fixture = fixture()) {
            var player = survivalWithFullHungerOffhandFood(fixture);
            player.movementPlatform = MovementPlatform.BEDROCK;
            player.isInvulnerable = true;
            player.canInstabuild = true;
            var event = abilities(player, false, false);
            ackPrecedingPing(player, event);
            assertFalse(player.isInvulnerable);
            assertFalse(player.canInstabuild);

            ackTrailingProof(player, event);
            assertFalse(player.isInvulnerable);
        }
    }

    @Test
    void queuedAbilityProofsApplyInTheirOwnOrder() {
        try (var fixture = fixture()) {
            var player = survivalWithFullHungerOffhandFood(fixture);
            var grant = abilities(player, true, true);
            var revoke = abilities(player, false, false);
            ackPrecedingPing(player, grant);
            assertFalse(player.isInvulnerable);
            assertFalse(player.canInstabuild);

            ackTrailingProof(player, grant);
            assertTrue(player.isInvulnerable);
            assertTrue(player.canInstabuild);
            ackPrecedingPing(player, revoke);
            assertTrue(player.isInvulnerable);
            assertTrue(player.canInstabuild);

            ackTrailingProof(player, revoke);
            assertFalse(player.isInvulnerable);
            assertFalse(player.canInstabuild);
            assertOffhandRefused(player, 1);
        }
    }

    @Test
    void invulnerabilityIsIndependentOfGameMode() {
        try (var fixture = fixture()) {
            var player = survivalWithFullHungerOffhandFood(fixture);
            var event = abilities(player, true, false);
            ackTrailingProof(player, event);
            assertEquals(GameMode.SURVIVAL, player.gamemode);
            assertTrue(player.isInvulnerable);
            assertFalse(player.canInstabuild);
            useOffhand(player, 1);
            assertTrue(player.isClientUsingItem());
            assertEquals(2, player.getInventory().getHandItem(Hand.OFF_HAND).getCount());
        }
    }

    @Test
    void hungerAppliesAtItsBundledProof() {
        try (var fixture = fixture()) {
            var player = survivalWithFullHungerOffhandFood(fixture);
            player.food = 18;
            var full = health(player, 20);
            ackPrecedingPing(player, full);
            assertEquals(18, player.food);

            ackTrailingProof(player, full);
            assertEquals(20, player.food);
            assertOffhandRefused(player, 1);

            var hungry = health(player, 18);
            ackPrecedingPing(player, hungry);
            assertEquals(20, player.food);
            player.packetStateData.acceptedClientTick++;
            assertOffhandRefused(player, 2);
            ackTrailingProof(player, hungry);
            assertEquals(18, player.food);
            player.packetStateData.acceptedClientTick++;
            useOffhand(player, 3);
            assertTrue(player.isClientUsingItem());
        }
    }

    @Test
    void healthAndDeathKeepTheirTransactionBoundaries() {
        try (var fixture = fixture()) {
            var player = survivalWithFullHungerOffhandFood(fixture);
            var self = player.compensatedEntities.getSelf();
            var death = health(player, 0F, 20);
            ackPrecedingPing(player, death);
            assertTrue(self.isDead);
            ackTrailingProof(player, death);
            assertTrue(self.isDead);

            var alive = health(player, 10F, 20);
            ackPrecedingPing(player, alive);
            assertTrue(self.isDead);
            ackTrailingProof(player, alive);
            assertFalse(self.isDead);
            assertEquals(10F, self.getHealth());
        }
    }

    @Test
    void abilitiesHealthAndCooldownAreBundledWithTheirTrailingProofs() {
        try (var fixture = fixture()) {
            var player = fixture.player;
            assertBundledWithProof(abilities(player, true, false).event());
            assertBundledWithProof(health(player, 18).event());
            var packet = new ClientboundCooldown("minecraft:apple", 40);
            var event = sendEvent(player, ClientboundPackets.COOLDOWN, packet);
            new PacketPlayerCooldown().onCooldown(event, player, packet);
            assertBundledWithProof(event);
        }
    }

    private static CultPlayer survivalWithFullHungerOffhandFood(RecordReceiveFixture fixture) {
        var player = fixture.player;
        player.gamemode = GameMode.SURVIVAL;
        player.food = 20;
        setOffhand(player, item("minecraft:apple", 2));
        return player;
    }

    private static void assertBundledWithProof(PacketSendEvent<?> event) {
        assertTrue(event.isBundleRequested());
        assertInstanceOf(ClientboundPing.class, event.writesAfter().getLast().packet());
    }

    /** The use does not start, and unslowed walking after it is legal. */
    private static void assertOffhandRefused(CultPlayer player, int sequence) {
        useOffhand(player, sequence);
        assertNoPredictedSustainedUse(player);
        assertFalse(noSlowFlags(player));
    }

    private static void assertNoPredictedSustainedUse(CultPlayer player) {
        assertFalse(player.isClientUsingItem());
        player.packetStateData.acceptedClientTick++;
        var snapshot = ItemUseInventory.prepare(player);
        assertEquals(1F, snapshot.legalMultiplier());
        assertFalse(snapshot.definitelyUsing());
    }
}
