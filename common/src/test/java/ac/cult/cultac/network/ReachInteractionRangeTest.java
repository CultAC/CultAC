package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.combat.EntityHitboxes;
import ac.cult.cultac.checks.impl.combat.EntityReach;
import ac.cult.cultac.checks.impl.combat.PlayerHitboxes;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.checks.impl.combat.ReachCheck;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundExplode;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReachInteractionRangeTest {
    // ServerPlayer#CREATIVE_ENTITY_INTERACTION_RANGE_MODIFIER
    private static final AttributeModifier CREATIVE =
            new AttributeModifier("minecraft:creative_mode_entity_range", 2.0, AttributeModifier.Operation.ADD_VALUE);
    private static final String REACH = "{\"max_reach\":4.5,\"hitbox_margin\":0.125}";
    private static final String SPEAR = "{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}";

    @Test
    void attacksAreBoundedByTheEntityInteractionRangeAttribute() throws Exception {
        try (var setup = new Setup()) {
            assertFalse(setup.attack(2.5));
            assertTrue(setup.attack(3.5));
            setup.self().entityInteractionRange.replace(4.5, List.of());
            assertFalse(setup.attack(3.5));
            assertTrue(setup.attack(5.0));
        }
    }

    @Test
    void creativeReachComesFromTheServerModifierNotTheGameMode() throws Exception {
        try (var setup = new Setup()) {
            setup.player.gamemode = GameMode.CREATIVE;
            setup.self().onGameModeChange(true);
            assertTrue(setup.attack(4.5));
            setup.self().entityInteractionRange.replace(3.0, List.of(CREATIVE));
            assertFalse(setup.attack(4.5));
            assertTrue(setup.attack(5.5));
        }
    }

    @Test
    void gameModeChangesKeepTheAttributeAndRespawnsDropOnlyModifiers() throws Exception {
        try (var setup = new Setup()) {
            setup.self().entityInteractionRange.replace(6.0, List.of(CREATIVE));
            setup.self().onGameModeChange(false);
            assertEquals(8.0, setup.self().getEntityInteractionRange());
            setup.self().onRespawn(true, false);
            assertEquals(8.0, setup.self().getEntityInteractionRange());
            setup.self().onRespawn(false, false);
            assertEquals(6.0, setup.self().getEntityInteractionRange());
        }
    }

    @Test
    void mainHandAttackRangeReplacesTheAttributeLimit() throws Exception {
        try (var setup = new Setup()) {
            setup.hold("{\"max_reach\":6,\"hitbox_margin\":0.3}");
            assertFalse(setup.attack(6.2));
            assertTrue(setup.attack(6.5));
            setup.self().entityInteractionRange.replace(10.0, List.of());
            assertTrue(setup.attack(6.5));
        }
    }

    @Test
    void itemRaysHitWithinTheHitboxMargin() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of("PlayerHitboxes"), setup.attack(EntityTypeIds.PLAYER, .9, 2.5));
            setup.hold("{\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            assertEquals(List.of(), setup.attack(EntityTypeIds.PLAYER, .9, 4.0));
            assertEquals(List.of("PlayerHitboxes"), setup.attack(EntityTypeIds.PLAYER, 1.0, 4.0));
        }
    }

    @Test
    void itemRaysLookWithTheYawTheLastTickEndedWith() throws Exception {
        try (var setup = new Setup()) {
            setup.hold("{\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            setup.look(0, 0, -30, 90, 0);
            assertFalse(setup.attack(4.0));
            assertTrue(setup.attack(4.8));
            setup.look(90, 0, -30, 90, 0);
            assertTrue(setup.attack(4.0));
        }
    }

    @Test
    void itemHitsNearerThanTheMinimumReachFlag() throws Exception {
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":2,\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            assertTrue(setup.attack(1.0));
            assertFalse(setup.attack(1.5));
            setup.player.gamemode = GameMode.CREATIVE;
            setup.hold("{\"min_creative_reach\":1,\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            assertFalse(setup.attack(1.0));
        }
    }

    @Test
    void piercingWeaponsGrantNoItemReach() throws Exception {
        try (var setup = new Setup()) {
            setup.hold("{\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            assertFalse(setup.attack(4.5));
            setup.hold(Map.of(
                    "minecraft:attack_range",
                    JsonParser.parseString("{\"max_reach\":4.5,\"hitbox_margin\":0.125}"),
                    "minecraft:piercing_weapon",
                    JsonParser.parseString("{}")));
            assertTrue(setup.attack(4.5));
            assertFalse(setup.attack(2.5));
        }
    }

    @Test
    void itemRaysTraceBackFromAMinimumBeyondTheMaximum() throws Exception {
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}");
            assertFalse(setup.attack(4.1));
            assertTrue(setup.attack(5.1));
            assertTrue(setup.attack(3.3));
        }
    }

    @Test
    void knownMovementAlongTheLookShortensAReversedItemRay() throws Exception {
        // ProjectileUtil#getHitEntitiesAlong: from 5 looks back to 4 plus the .5 the last tick left along the look.
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}");
            assertEquals(.5, setup.drift(.55), 1.0E-3);
            assertEquals(List.of(), setup.attackAlong(0, 4.1, 0));
        }
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}");
            setup.drift(.55);
            assertEquals(List.of("PlayerReach"), setup.attackAlong(0, 3.6, 0));
        }
    }

    @Test
    void knownMovementAlongTheLookCollapsesAReversedItemRay() throws Exception {
        // From 5 to 4 plus a movement of 1 along the look: no segment is left to hit the box between 4.1 and 4.7.
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}");
            assertEquals(1, setup.drift(1.0989), 1.0E-4);
            assertEquals(List.of("PlayerReach"), setup.attackAlong(0, 4.1, 0));
        }
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}");
            assertEquals(List.of(), setup.attackAlong(0, 4.1, 0));
        }
    }

    @Test
    void knownMovementAlongTheLookFlipsAReversedItemRayForward() throws Exception {
        // From 4.5 to 4 plus .6 along the look runs forward to 4.6, entering a box at 4.55 but not one at 4.8.
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":4.5,\"max_reach\":4,\"hitbox_margin\":1}");
            assertEquals(.6, setup.drift(.66), 1.0E-3);
            assertEquals(List.of(), setup.attackAlong(0, 4.55, 0));
        }
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":4.5,\"max_reach\":4,\"hitbox_margin\":1}");
            setup.drift(.66);
            assertEquals(List.of("PlayerReach"), setup.attackAlong(0, 4.8, 0));
        }
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":4.5,\"max_reach\":4,\"hitbox_margin\":1}");
            assertEquals(List.of("PlayerReach"), setup.attackAlong(0, 4.55, 0));
        }
    }

    @Test
    void aMotionHandledBeforeThePickReplacesTheKnownMovement() throws Exception {
        // ClientPacketListener#handleSetEntityMotion sets the delta movement to 1 along the look: from 5 to 4 plus 1.
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}");
            assertEquals(0, setup.drift(0));
            setup.motion(1);
            assertEquals(List.of("PlayerReach"), setup.attackAlong(0, 4.1, 0));
        }
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}");
            setup.drift(1.0989);
            setup.motion(0);
            assertEquals(List.of(), setup.attackAlong(0, 4.1, 0));
        }
    }

    @Test
    void aMotionHandledBeforeThePickDropsTheMovementThatRanTheItemRayForward() throws Exception {
        // The .6 the last tick left would run the ray from 4.5 forward to 4.6; the motion leaves it 4.5 back to 4.
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":4.5,\"max_reach\":4,\"hitbox_margin\":1}");
            setup.drift(.66);
            setup.motion(0);
            assertEquals(List.of("PlayerReach"), setup.attackAlong(0, 4.55, 0));
        }
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":4.5,\"max_reach\":4,\"hitbox_margin\":1}");
            setup.motion(.6);
            assertEquals(List.of(), setup.attackAlong(0, 4.55, 0));
        }
    }

    @Test
    void aMotionTheClientMayNotHaveHandledKeepsBothKnownMovements() throws Exception {
        // Until the motion's trailing ping is answered, the pick may read the motion or what the last tick left.
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}");
            setup.drift(0);
            setup.pendingMotion(1);
            assertEquals(List.of(), setup.attackAlong(0, 4.1, 0));
        }
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}");
            setup.drift(1.0989);
            setup.pendingMotion(0);
            assertEquals(List.of(), setup.attackAlong(0, 4.1, 0));
        }
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}");
            setup.drift(1.0989);
            setup.pendingMotion(1);
            assertEquals(List.of("PlayerReach"), setup.attackAlong(0, 4.1, 0));
        }
    }

    @Test
    void anExplosionAddsToTheMotionBeforeItAndAMotionReplacesTheExplosionBeforeIt() throws Exception {
        // ClientPacketListener#handleExplosion pushes .5 onto the motion's .5: from 5 to 4 plus 1 leaves no segment.
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}");
            setup.drift(0);
            setup.motion(.5);
            setup.explosion(.5);
            assertEquals(List.of("PlayerReach"), setup.attackAlong(0, 4.1, 0));
        }
        // The motion after the push sets .5: from 5 back to 4.5 enters the box at 4.7.
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}");
            setup.drift(0);
            setup.explosion(.5);
            setup.motion(.5);
            assertEquals(List.of(), setup.attackAlong(0, 4.1, 0));
        }
    }

    @Test
    void anAttackBeforeTheFirstTickAfterARespawnIsCancelledAfterAMotionAndAnUnloadedTickEnd() throws Exception {
        try (var setup = new Setup()) {
            setup.respawnFacing(0, SPEAR);
            setup.motion(1);
            setup.unloadedTickEnd();
            setup.playerLoaded();
            Vec3 to = new Vec3(setup.player.x, setup.player.y, setup.player.z + 1);
            setup.assertCancelledAlong(0, 4.1, () -> {
                setup.move(to);
                setup.loadedTickEnd();
            });
            var result = setup.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
            // The first travel leaves the motion times the air drag .91: from 5 back to 4.91, past a box from 4.1 to
            // 4.7 and inside one from 4.5 to 5.1.
            assertEquals(List.of("PlayerReach"), setup.attackAlong(0, 4.1, 0));
            assertEquals(List.of(), setup.attackAlong(0, 4.5, 0));
        }
    }

    @Test
    void anAttackBeforeTheFirstTickAfterARespawnIsCancelledAfterAMotionAndABoatMount() throws Exception {
        try (var setup = new Setup()) {
            setup.respawnFacing(0, SPEAR);
            setup.motion(1);
            setup.board(0);
            setup.unloadedTickEnd();
            setup.playerLoaded();
            setup.assertCancelledAlong(0, 4.1, () -> {
                setup.rotate(0);
                setup.loadedTickEnd();
            });
            // Entity#rideTick zeroes the passenger's delta movement: from 5 back to 4, into a box from 4.1 to 4.7 and
            // short of one from 5.1.
            assertEquals(List.of(), setup.attackAlong(0, 4.1, 0));
            assertEquals(List.of("PlayerReach"), setup.attackAlong(0, 5.1, 0));
        }
    }

    @Test
    void anAttackBeforeTheFirstTickAfterARespawnIsCancelledAfterExplosionsAroundAnUnloadedTickEnd() throws Exception {
        try (var setup = new Setup()) {
            setup.respawnFacing(0, SPEAR);
            setup.motion(1);
            setup.explosion(.5);
            setup.unloadedTickEnd();
            setup.explosion(-.5);
            setup.playerLoaded();
            Vec3 to = new Vec3(setup.player.x, setup.player.y, setup.player.z + 1);
            setup.assertCancelledAlong(0, 4.1, () -> {
                setup.move(to);
                setup.loadedTickEnd();
            });
            // The pushes cancel out, and the first travel leaves 1 times the air drag .91.
            assertEquals(List.of("PlayerReach"), setup.attackAlong(0, 4.1, 0));
            assertEquals(List.of(), setup.attackAlong(0, 4.5, 0));
        }
    }

    @Test
    void anAttackInThePlayerLoadedTickIsCancelled() throws Exception {
        // Minecraft#tick sends ServerboundPlayerLoadedPacket before handleKeybinds and the LocalPlayer's first tick.
        try (var setup = new Setup()) {
            setup.respawnFacing(0, REACH);
            setup.unloadedTickEnd();
            setup.playerLoaded();
            setup.assertCancelledAlong(0, 4.0, () -> {
                setup.rotate(0);
                setup.loadedTickEnd();
            });
            assertEquals(List.of(), setup.attackAlong(0, 4.0, 0));
            assertEquals(List.of("PlayerReach"), setup.attackAlong(0, 4.8, 0));
        }
    }

    @Test
    void attacksStayCancelledWhileALoadedClientEndsNoTick() throws Exception {
        try (var setup = new Setup()) {
            setup.respawnFacing(0, REACH);
            setup.unloadedTickEnd();
            setup.playerLoaded();
            Vec3 here = new Vec3(setup.player.x, setup.player.y, setup.player.z);
            for (int i = 0; i < 3; i++) {
                setup.assertCancelledAlong(0, 4.0, () -> setup.move(here));
                setup.assertCancelledAlong(0, 4.8, () -> setup.move(here));
            }
            assertTrue(setup.player.packetStateData.playerLoadedIntoLevel);
            assertFalse(setup.player.packetStateData.localPlayerTickedSinceSpawn);
        }
    }

    @Test
    void theRetainedViewHitIsBoundedByTheItemSelectedAfterThePick() throws Exception {
        try (var setup = new Setup()) {
            setup.hold(0, null);
            setup.hold(1, "{\"min_reach\":2,\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            setup.rotate(90);
            setup.tickEnd();
            setup.selectSlot(1);
            assertEquals(1, setup.player.getInventory().inventory.selected);
            assertEquals(List.of("PlayerReach"), setup.attackAhead(1.7, 0));
            assertEquals(List.of(), setup.attackAhead(2.1, 0));
        }
    }

    @Test
    void boatRidersPickWithTheHeadYawTheBoatLeftUnclamped() throws Exception {
        try (var setup = new Setup()) {
            setup.hold("{\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            setup.board(0);
            setup.rotate(0);
            setup.tickEnd();
            // ClientPacketListener#handleRotatePlayer turns the rider backwards; positionRider clamps only its yaw.
            setup.rotate(180);
            setup.rotate(255);
            setup.tickEnd();
            assertEquals(List.of(), setup.attackAlong(180, 4.0, 255));
            assertEquals(List.of("PlayerReach"), setup.attackAlong(180, 4.0, 255));
        }
        try (var setup = new Setup()) {
            setup.hold("{\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            setup.board(0);
            setup.rotate(0);
            setup.tickEnd();
            setup.rotate(180);
            setup.rotate(255);
            setup.tickEnd();
            assertEquals(List.of("PlayerReach"), setup.attackAlong(180, 4.8, 255));
        }
    }

    @Test
    void firstBoatMountsTurnTheHeadToTheBoat() throws Exception {
        try (var setup = new Setup()) {
            setup.hold("{\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            setup.rotate(0);
            setup.tickEnd();
            setup.board(90);
            assertEquals(List.of(), setup.attackAlong(90, 4.0, 90));
            assertEquals(List.of("PlayerReach"), setup.attackAlong(90, 4.8, 90));
            setup.rotate(0);
            setup.tickEnd();
            setup.board(90);
            assertEquals(List.of("PlayerReach"), setup.attackAlong(90, 4.0, 0));
        }
    }

    @Test
    void boatRidersPickWithTheHeadYawTheirMouseTurnLeftPastATurningBoat() throws Exception {
        // The mouse turns the rider to 100 against the boat at 0; the remote boat's interpolation then turns it to
        // -20, so clampRotation sends 85 and leaves the head Player#aiStep copied at 100.
        try (var setup = new Setup()) {
            setup.turnedPastTheBoat();
            assertEquals(List.of(), setup.attackAlong(100, 4.0, 85));
        }
        try (var setup = new Setup()) {
            setup.turnedPastTheBoat();
            assertEquals(List.of("PlayerReach"), setup.attackAlong(100, 4.8, 85));
        }
        try (var setup = new Setup()) {
            setup.turnedPastTheBoat();
            assertEquals(List.of("PlayerHitboxes"), setup.attackAlong(120, 4.0, 85));
        }
        try (var setup = new Setup()) {
            setup.turnedPastTheBoat();
            assertEquals(List.of("PlayerHitboxes"), setup.attackAlong(65, 4.0, 85));
        }
    }

    @Test
    void aNewLocalPlayerIsJudgedWithTheHeadYawOfItsFirstTick() throws Exception {
        // LivingEntity's constructor head yaw, between 0 and 2*PI degrees, holds only until the first tick.
        try (var setup = new Setup()) {
            setup.respawnFacing(90, REACH);
            setup.unloadedTickEnd();
            setup.playerLoaded();
            setup.assertCancelledAlong(3, 4.0, () -> {
                setup.rotate(90);
                setup.loadedTickEnd();
            });
            assertEquals(List.of("PlayerReach"), setup.attackAlong(3, 4.0, 90));
            assertEquals(List.of("PlayerHitboxes"), setup.attackAlong(20, 4.0, 90));
            assertEquals(List.of(), setup.attackAlong(90, 4.0, 90));
            assertEquals(List.of("PlayerReach"), setup.attackAlong(90, 4.8, 90));
        }
    }

    @Test
    void playerAndEntityTargetsFlagTheirOwnReachAndHitboxChecks() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of("PlayerReach"), setup.attack(EntityTypeIds.PLAYER, .5, 3.5));
            assertEquals(List.of("EntityReach"), setup.attack(EntityTypeIds.ZOMBIE, .5, 3.5));
            assertEquals(List.of(), setup.attack(EntityTypeIds.ZOMBIE, .5, 2.5));
            assertEquals(List.of("PlayerHitboxes"), setup.attack(EntityTypeIds.PLAYER, 2.5, 2.0));
            assertEquals(List.of("EntityHitboxes"), setup.attack(EntityTypeIds.ZOMBIE, 2.5, 2.0));
        }
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture;
        final CultPlayer player;
        static final int BOAT = 90;
        final CheckManagerListener listener = new CheckManagerListener();
        final List<Check> checks;
        int nextEntity = 100;

        Setup() throws Exception {
            fixture = new RecordReceiveFixture(ProtocolVersion.V26_3, false);
            services.useNetwork(fixture.manager);
            CultAPI.INSTANCE.getPlayerDataManager().addUser(fixture.user);
            player = fixture.user.getCultPlayer();
            player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
            checks = List.of(
                    player.checkManager.getCheck(PlayerReach.class),
                    player.checkManager.getCheck(EntityReach.class),
                    player.checkManager.getCheck(PlayerHitboxes.class),
                    player.checkManager.getCheck(EntityHitboxes.class));
            checks.forEach(check -> check.setEnabled(true));
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -1.5;
            player.registryState = new ClientComponentRegistries();
            player.getSetbackTeleportUtil().hasFullyLoaded = true;
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            world.ensureValidationChunkLoaded(0, 0);
            world.ensureValidationChunkLoaded(0, -1);
            // ReachCheck#measuredAttacks keeps a tick's attacks only while something observes the player.
            player.observer = new PlayerObserver() {};
            fixture.registrar.register(routes -> routes.registerReceiveListener(PacketListenerPriority.LOW, listener));
        }

        ac.cult.cultac.utils.data.packetentity.PacketEntitySelf self() {
            return player.compensatedEntities.getSelf();
        }

        void hold(String attackRange) {
            hold(Map.of("minecraft:attack_range", JsonParser.parseString(attackRange)));
        }

        void hold(Map<String, JsonElement> components) {
            var sword = ItemUtil.modelItems().stack("minecraft:iron_sword", 1);
            player.getInventory()
                    .inventory
                    .setHeldItem(new SimItemStack(sword.definition(), 1, new Components(components)));
        }

        void hold(int slot, String attackRange) {
            var sword = ItemUtil.modelItems().stack("minecraft:iron_sword", 1);
            Map<String, JsonElement> components = attackRange == null
                    ? Map.of()
                    : Map.of("minecraft:attack_range", JsonParser.parseString(attackRange));
            player.getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(36 + slot, new SimItemStack(sword.definition(), 1, new Components(components)));
        }

        void selectSlot(int slot) {
            var packet = new ServerboundSetCarriedItem(slot);
            player.checkManager.dispatchReceiveHandlers(new PacketReceiveEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ServerboundPackets.SET_CARRIED_ITEM, packet));
        }

        void rotate(float yaw) {
            var packet = new ServerboundMovePlayer(0, 0, 0, yaw, 0, false, false, false, true);
            listener.onMovePlayer(
                    new PacketReceiveEvent<>(
                            fixture.user, ConnectionPhase.PLAY, ServerboundPackets.MOVE_PLAYER, packet),
                    player,
                    packet);
        }

        void tickEnd() {
            listener.processClientTickEndReceive(
                    new PacketReceiveEvent<>(
                            fixture.user,
                            ConnectionPhase.PLAY,
                            ServerboundPackets.CLIENT_TICK_END,
                            ServerboundPackets.CLIENT_TICK_END.opaqueValue()),
                    player);
        }

        // One client tick falling through the air at this speed along +z, looking along +z; the speed along +z its
        // committed prediction leaves as the delta movement the next pick reads.
        double drift(double speed) {
            var simulation = player.checkManager.getSimulationProcessor();
            player.lastX = player.x;
            player.lastY = player.y;
            player.lastZ = player.z;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);
            simulation.seedStartingVelocity(new Vec3(0, 0, speed));
            var packet =
                    new ServerboundMovePlayer(player.x, player.y, player.z + speed, 0, 0, false, false, true, true);
            listener.onMovePlayer(
                    new PacketReceiveEvent<>(
                            fixture.user, ConnectionPhase.PLAY, ServerboundPackets.MOVE_PLAYER, packet),
                    player,
                    packet);
            tickEnd();
            Set<Vec3> movements = simulation.getLastDerivedNextTickVelocities();
            assertEquals(1, movements.size(), movements::toString);
            return movements.iterator().next().z;
        }

        // A set_entity_motion of this speed along +z for the player, its proof answered before the next pick.
        void motion(double speed) {
            bind();
            var packet = new ClientboundEntityMotion(player.entityID, new Vec3d(0, 0, speed));
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_MOTION, packet, false);
            int proof = player.checkManager.getKnockbackHandler().handleEntityVelocity(event, packet);
            event.getTasksAfterSend().forEach(Runnable::run);
            pongThrough(Math.max(proof, player.lastTransactionSent.get()));
            assertNotNull(player.checkManager.getKnockbackHandler().secondBread);
        }

        // A motion of this speed along +z between two pings, the client answering the first but not yet the second.
        void pendingMotion(double speed) {
            bind();
            var knockback = player.checkManager.getKnockbackHandler();
            int proof = knockback.handleDebugPseudoEvent(new Vec3(0, 0, speed), true, player.entityID);
            pongThrough(proof - 1);
            assertNotNull(knockback.firstBread);
        }

        // An explosion pushing the player this far along +z, its proof answered before the next pick.
        void explosion(double push) {
            bind();
            var packet = new ClientboundExplode(new Vec3d(player.x, player.y, player.z), new Vec3d(0, 0, push));
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.EXPLODE, packet, false);
            player.checkManager.getExplosionHandler().onExplode(event, player, packet);
            event.getTasksAfterSend().forEach(Runnable::run);
            pongThrough(player.lastTransactionSent.get());
            assertNotNull(player.checkManager.getExplosionHandler().secondBread);
        }

        // The fixture writes pings only to a bound player.
        private void bind() {
            if (fixture.user.getPlayer() == null) fixture.user.bind(Mockito.mock(PlatformPlayer.class));
        }

        private void pongThrough(int transaction) {
            for (int id = player.lastTransactionReceived.get() + 1; id <= transaction; id++) {
                player.lastTransactionReceived.set(id);
                player.latencyUtils.handleNettySyncTransaction(id);
            }
        }

        // Seats the player behind another passenger of a boat with this yaw at its feet, answering the ping.
        void board(float boatYaw) {
            int boat = BOAT;
            if (player.compensatedEntities.getEntity(boat) == null) {
                player.compensatedEntities.addEntity(
                        boat, EntityTypeIds.OAK_BOAT, new Vec3(.5, 64, -1.5), boatYaw, 0, 0);
                player.compensatedEntities.addEntity(boat + 1, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, -1.5), 0, 0, 0);
            }
            var packet = new ClientboundSetPassengers(boat, List.of(boat + 1, player.entityID));
            player.packetEntityReplication.onSetPassengers(
                    new PacketSendEvent<>(
                            fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false),
                    player,
                    packet);
            int proof = player.compensatedEntities.vehicles.serverPlayerVehicleTransaction;
            player.lastTransactionReceived.set(proof);
            player.latencyUtils.handleNettySyncTransaction(proof);
        }

        // A rider of a boat at 0 whose mouse turns it to 100 while the remote boat's interpolation turns it to -20.
        void turnedPastTheBoat() {
            hold("{\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            board(0);
            rotate(0);
            tickEnd();
            var packet = new ClientboundTeleportEntity(
                    BOAT, new Vec3d(.5, 64, -1.5), new Vec3d(0, 0, 0), -60, 0, Set.of(), false);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false);
            player.packetEntityReplication.onTeleportEntity(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
            // The fixture writes no pings; the client answers the teleport's first and second transactions.
            int first = player.lastTransactionSent.get();
            for (int id = first; id <= first + 1; id++) {
                player.lastTransactionReceived.set(id);
                player.latencyUtils.handleNettySyncTransaction(id);
            }
            rotate(85);
            tickEnd();
            assertEquals(-20.0F, player.compensatedEntities.getEntity(BOAT).clientPhysicalYaw);
        }

        // A respawn after facing this yaw whose position teleport the client accepted, answering its ping, holding this
        // attack range.
        void respawnFacing(float yaw, String attackRange) {
            rotate(yaw);
            tickEnd();
            var spawnInfo = new PlayerSpawnInfo(
                    ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:overworld"),
                    player.compensatedWorld.getLastClientboundDimension().dimension(),
                    GameMode.SURVIVAL);
            var packet = new ClientboundRespawn(spawnInfo, (byte) 0);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
            new PacketPlayerRespawn().onRespawn(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
            // The fixture writes no pings; the client answers the one after the respawn.
            pongThrough(player.lastTransactionSent.get() + 1);
            assertFalse(player.packetStateData.localPlayerTickedSinceSpawn);
            player.getSetbackTeleportUtil().hasFullyLoaded = true;
            hold(attackRange);
        }

        // A client tick end before the client has loaded, which LocalPlayer#tick skips.
        void unloadedTickEnd() {
            tickEnd();
            assertFalse(player.packetStateData.playerLoadedIntoLevel);
            assertFalse(player.packetStateData.localPlayerTickedSinceSpawn);
        }

        // ClientPacketListener#notifyPlayerLoaded's ServerboundPlayerLoadedPacket, through the receive pipeline.
        void playerLoaded() {
            fixture.forward(fixture.id(ServerboundPackets.PLAYER_LOADED, "player_loaded"));
            assertTrue(player.packetStateData.playerLoadedIntoLevel);
        }

        // A client tick end of a tick that ran LocalPlayer#tick.
        void loadedTickEnd() {
            tickEnd();
            assertTrue(player.packetStateData.localPlayerTickedSinceSpawn);
        }

        // A client tick that sends this position, from where the player stands.
        void move(Vec3 to) {
            player.lastX = player.x;
            player.lastY = player.y;
            player.lastZ = player.z;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);
            var packet = new ServerboundMovePlayer(to.x, to.y, to.z, 0, 0, false, false, true, true);
            listener.onMovePlayer(
                    new PacketReceiveEvent<>(
                            fixture.user, ConnectionPhase.PLAY, ServerboundPackets.MOVE_PLAYER, packet),
                    player,
                    packet);
        }

        // One client tick attacking a player whose hitbox face is this far straight ahead, then sending this yaw.
        List<String> attackAhead(double distance, float yaw) {
            return attackAt(new Vec3(.5, 64, distance - 1.2), yaw);
        }

        // One client tick attacking a player whose hitbox face is this far along a look of this yaw from the seat.
        List<String> attackAlong(float lookYaw, double distance, float yaw) {
            return attackAlong(lookYaw, distance, () -> rotate(yaw));
        }

        // One client tick attacking a player whose hitbox face is this far along a look of this yaw, then this
        // movement.
        List<String> attackAlong(float lookYaw, double distance, Runnable movement) {
            return attackAt(along(lookYaw, distance), movement);
        }

        // Attacks a player whose hitbox face is this far along a look of this yaw, then runs the rest of the client's
        // packets; asserts ReachCheck cancelled the attack unmeasured and nothing flagged.
        void assertCancelledAlong(float lookYaw, double distance, Runnable rest) {
            int id = nextEntity++;
            player.compensatedEntities.addEntity(id, EntityTypeIds.PLAYER, along(lookYaw, distance), 0, 0, 0);
            double[] before =
                    checks.stream().mapToDouble(check -> check.violations).toArray();
            var packet = new ServerboundInteract(id, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            var event =
                    new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, packet);
            player.checkManager.dispatchReceiveHandlers(event);
            assertTrue(event.isCancelled(), "attack on " + id + " passed");
            rest.run();
            assertArrayEquals(
                    before,
                    checks.stream().mapToDouble(check -> check.violations).toArray());
            assertTrue(player.checkManager.getCheck(PlayerReach.class).measuredAttacks().stream()
                    .noneMatch(attack -> attack.entityId() == id));
        }

        private Vec3 along(float lookYaw, double distance) {
            Vec3 seat = player.checkManager
                    .getCheck(PlayerReach.class)
                    .getAttackOrigins()
                    .getFirst();
            double x = -Math.sin(Math.toRadians(lookYaw));
            double z = Math.cos(Math.toRadians(lookYaw));
            double center = distance + .3;
            return new Vec3(seat.x + x * center, seat.y, seat.z + z * center);
        }

        private List<String> attackAt(Vec3 position, float yaw) {
            return attackAt(position, () -> rotate(yaw));
        }

        private List<String> attackAt(Vec3 position, Runnable movement) {
            int id = nextEntity++;
            player.compensatedEntities.addEntity(id, EntityTypeIds.PLAYER, position, 0, 0, 0);
            double[] before =
                    checks.stream().mapToDouble(check -> check.violations).toArray();
            var packet = new ServerboundInteract(id, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            player.checkManager.dispatchReceiveHandlers(
                    new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, packet));
            movement.run();
            tickEnd();
            assertMeasured(id, EntityTypeIds.PLAYER);
            List<String> flagged = new ArrayList<>();
            for (int i = 0; i < checks.size(); i++) {
                if (checks.get(i).violations > before[i])
                    flagged.add(checks.get(i).getCheckName());
            }
            return flagged;
        }

        // CultPlayer keeps yaw in xRot and pitch in yRot; headYaw is the yaw the last tick ended with.
        void look(float headYaw, float lastYaw, float lastPitch, float yaw, float pitch) {
            player.headYaw = headYaw;
            player.lastTickXRot = lastYaw;
            player.lastTickYRot = lastPitch;
            player.xRot = yaw;
            player.yRot = pitch;
        }

        // Attacks a player looked at head-on whose hitbox face is this far from the eye; returns whether it flagged.
        boolean attack(double distance) {
            return !attack(EntityTypeIds.PLAYER, .5, distance).isEmpty();
        }

        // Attacks an entity at this x whose hitbox face is this far down the look; returns the checks that flagged.
        List<String> attack(int type, double x, double distance) {
            int id = nextEntity++;
            player.compensatedEntities.addEntity(id, type, new Vec3(x, 64, distance - 1.2), 0, 0, 0);
            var packet = new ServerboundInteract(id, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            var event =
                    new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, packet);
            double[] before =
                    checks.stream().mapToDouble(check -> check.violations).toArray();
            player.checkManager.dispatchReceiveHandlers(event);
            for (Check check : checks) {
                if (check instanceof ReachCheck reach) reach.onPlayerTickEnd(null);
            }
            player.checkManager.getListener(ClientVisibilityProcessor.class).onPlayerTickEnd(null);
            assertMeasured(id, type);
            List<String> flagged = new java.util.ArrayList<>();
            for (int i = 0; i < checks.size(); i++) {
                if (checks.get(i).violations > before[i])
                    flagged.add(checks.get(i).getCheckName());
            }
            return flagged;
        }

        // The attack reached ReachCheck's measurement, so no exemption in ReachCheck#handleInteract dropped it.
        private void assertMeasured(int id, int type) {
            ReachCheck reach = type == EntityTypeIds.PLAYER
                    ? player.checkManager.getCheck(PlayerReach.class)
                    : player.checkManager.getCheck(EntityReach.class);
            assertTrue(
                    reach.measuredAttacks().stream().anyMatch(attack -> attack.entityId() == id),
                    "attack on " + id + " exempt");
        }

        @Override
        public void close() throws Exception {
            try {
                fixture.close();
            } finally {
                services.close();
            }
        }
    }
}
