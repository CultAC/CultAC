package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.combat.EntityHitboxes;
import ac.cult.cultac.checks.impl.combat.EntityReach;
import ac.cult.cultac.checks.impl.combat.PlayerHitboxes;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.checks.impl.combat.ReachCheck;
import ac.cult.cultac.checks.impl.visibility.AttackMobVisibility;
import ac.cult.cultac.checks.impl.visibility.AttackPlayerVisibility;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundExplode;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveMinecart;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import ac.grim.grimac.api.event.events.FlagEvent;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReachInteractionRangeTest {
    // ServerPlayer#CREATIVE_ENTITY_INTERACTION_RANGE_MODIFIER
    private static final AttributeModifier CREATIVE =
            new AttributeModifier("minecraft:creative_mode_entity_range", 2.0, AttributeModifier.Operation.ADD_VALUE);
    private static final String REACH = "{\"max_reach\":4.5,\"hitbox_margin\":0.125}";
    private static final String SPEAR = "{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}";
    private static final int MANNEQUIN =
            EntityTypes.defaults().byKey("mannequin").id();

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
    void hitboxVerboseNamesOnlyANonPlayerTargetsType() throws Exception {
        try (var setup = new Setup()) {
            List<String> verbose = new ArrayList<>();
            FlagEvent.Channel flags = CultAPI.INSTANCE.getEventBus().get(FlagEvent.class);
            FlagEvent.SupplierHandler record = (user, flagged, text, cancelled) -> {
                if (flagged.getCheckName().endsWith("Hitboxes"))
                    verbose.add(flagged.getCheckName() + ": " + text.get());
                return cancelled;
            };
            flags.onFlagSupplier(CultAPI.INSTANCE.getGrimPlugin(), record, 0, false);
            try {
                assertEquals(List.of("PlayerHitboxes"), setup.attack(EntityTypeIds.PLAYER, .9, 2.5));
                assertEquals(List.of("EntityHitboxes"), setup.attack(EntityTypeIds.ZOMBIE, .9, 2.5));
            } finally {
                flags.unsubscribe(record);
            }
            assertEquals(List.of("PlayerHitboxes: ", "EntityHitboxes: type=minecraft:zombie"), verbose);
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
    void anAttackBeyondReachInPlainSightIsPlayerReachsToFlag() throws Exception {
        try (var setup = new Setup()) {
            Check visibility = setup.player.checkManager.getCheck(AttackPlayerVisibility.class);
            visibility.setEnabled(true);
            var plain = setup.routedAttack(3.7);
            assertEquals(List.of("PlayerReach"), plain.flagged());
            assertTrue(plain.cancelled(), "PlayerReach cancels it");
            assertEquals(0, visibility.violations);
            setup.wall();
            var walled = setup.routedAttack(3.7);
            assertEquals(List.of("PlayerReach"), walled.flagged(), "Beyond every pick range reach owns it");
            assertTrue(walled.cancelled(), "PlayerReach cancels it");
            assertEquals(0, visibility.violations);
            var near = setup.routedAttack(2.5);
            assertEquals(List.of(), near.flagged(), "Visibility cancels an attack through blocks");
            assertTrue(near.cancelled());
            assertEquals(1, visibility.violations);
        }
    }

    @Test
    void anotherSelectionsItemRangeKeepsAnAttackThroughBlocksWithVisibility() throws Exception {
        try (var setup = new Setup()) {
            Check visibility = setup.player.checkManager.getCheck(AttackPlayerVisibility.class);
            visibility.setEnabled(true);
            setup.hold(0, "{\"min_reach\":2,\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            setup.hold(1, null);
            setup.tickEnd();
            setup.wall();
            // Within the held weapon's range, beyond the hand's: the hotbar switch keeps both selections this tick.
            setup.selectSlot(1);
            var routed = setup.routedAttack(3.5);
            assertTrue(routed.cancelled(), "The weapon's selection reaches it and every ray is blocked");
            assertEquals(List.of(), routed.flagged());
            assertEquals(1, visibility.violations);
        }
    }

    @Test
    void aJavaClientsBoatBeyondReachInPlainSightIsEntityReachsToFlag() throws Exception {
        try (var setup = new Setup()) {
            Check visibility = setup.player.checkManager.getCheck(AttackMobVisibility.class);
            visibility.setEnabled(true);
            // The boat's near face 3.7 blocks ahead at the eye's height.
            var ahead = new Vec3(setup.player.x, setup.player.y + 1.62 - .3, setup.player.z + 3.7 + 1.375 / 2);
            var java = setup.routedAttack(EntityTypeIds.OAK_BOAT, ahead);
            assertEquals(List.of("EntityReach"), java.flagged());
            assertTrue(java.cancelled(), "EntityReach cancels it");
            assertEquals(0, visibility.violations);
        }
    }

    @Test
    void aDisabledReachLeavesTheDistanceToVisibility() throws Exception {
        try (var setup = new Setup()) {
            Check visibility = setup.player.checkManager.getCheck(AttackPlayerVisibility.class);
            visibility.setEnabled(true);
            setup.player.checkManager.getCheck(PlayerReach.class).setEnabled(false);
            var routed = setup.routedAttack(3.7);
            assertTrue(routed.cancelled());
            assertEquals(1, visibility.violations);
        }
    }

    @Test
    void aLowTargetOnlyTheEyeInsideGrassReachesStaysWithVisibility() throws Exception {
        for (String mode : new String[] {"plain", "grass", "grass+wall"}) {
            try (var setup = new Setup()) {
                Check visibility = setup.player.checkManager.getCheck(AttackPlayerVisibility.class);
                visibility.setEnabled(true);
                // LocalPlayer#pick's block clip from the crawling eye inside the grass outline hits the grass.
                if (!mode.equals("plain")) setup.block(0, 64, -2, "short_grass");
                if (mode.endsWith("wall")) for (int y = 58; y <= 68; y++) setup.block(0, y, 0, "stone");
                setup.look(0, 0, 22, 0, 22);
                // The near face 2.65 ahead and its top 0.5 below the feet: only the crawling eye is within range.
                var routed = setup.routedAttack(EntityTypeIds.PLAYER, new Vec3(.5, 61.7, 1.45));
                assertEquals(List.of(), routed.flagged(), mode);
                if (mode.equals("plain")) {
                    assertFalse(routed.cancelled(), "The crawling eye sees it");
                    assertEquals(0, visibility.violations);
                } else {
                    assertTrue(routed.cancelled(), mode);
                    assertEquals(1, visibility.violations, mode);
                }
            }
        }
    }

    @Test
    void anInexactTargetBehindAWallJustBeyondItsPhysicalBoxStaysCancelled() throws Exception {
        for (boolean walled : new boolean[] {false, true}) {
            for (double face : new double[] {3.01, 3.025, 3.04}) {
                try (var setup = new Setup()) {
                    Check visibility = setup.player.checkManager.getCheck(AttackPlayerVisibility.class);
                    visibility.setEnabled(true);
                    if (walled) for (int y = 60; y <= 68; y++) setup.block(0, y, 0, "stone");
                    int id = setup.addTarget(EntityTypeIds.PLAYER, new Vec3(.5, 64, -1.5 + face + .3));
                    setup.player.compensatedEntities.getEntity(id).markPositionInexact();
                    var routed = setup.routedAttack(id);
                    String at = "face=" + face + " wall=" + walled;
                    if (face > 3.03) {
                        // The reach box, 0.03125 larger, is beyond range from every eye.
                        assertEquals(List.of("PlayerReach"), routed.flagged(), at);
                        assertTrue(routed.cancelled(), at);
                        assertEquals(0, visibility.violations, at);
                    } else if (walled) {
                        assertEquals(List.of(), routed.flagged(), at);
                        assertTrue(routed.cancelled(), at);
                        assertEquals(1, visibility.violations, at);
                    } else {
                        assertEquals(List.of(), routed.flagged(), at);
                        assertFalse(routed.cancelled(), "In sight and within reach of the reach box: " + at);
                        assertEquals(0, visibility.violations, at);
                    }
                }
            }
        }
    }

    @Test
    void anExemptPlayersAttackThroughAWallBeyondRangeStaysWithVisibility() throws Exception {
        for (String permission : new String[] {
            "cult.exempt.playerreach", "cult.nomodifypacket.playerreach", "cult.exempt.playerhitboxes"
        }) {
            try (var setup = new Setup()) {
                Check visibility = setup.player.checkManager.getCheck(AttackPlayerVisibility.class);
                visibility.setEnabled(true);
                PlatformPlayer platform = Mockito.mock(PlatformPlayer.class);
                Mockito.when(platform.hasPermission(permission)).thenReturn(true);
                setup.player.platformPlayer = platform;
                setup.player.checkManager.getCheck(PlayerReach.class).updatePermissions();
                setup.player.checkManager.getCheck(PlayerHitboxes.class).updatePermissions();
                setup.wall();
                // At 7 blocks the tick end reports a missed hitbox, which PlayerHitboxes flags.
                var routed = setup.routedAttack(permission.endsWith("hitboxes") ? 7 : 3.7);
                assertTrue(routed.cancelled(), permission);
                assertEquals(List.of(), routed.flagged(), permission);
                assertEquals(1, visibility.violations, permission);
            }
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
    void oneFlushPingProvingAMotionAndAnExplosionKeepsTheirWireOrder() throws Exception {
        // ClientPacketListener handles the bundle in wire order: the push after the motion adds to it, from 5 to 4
        // plus 1 leaving no segment.
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}");
            setup.drift(0);
            setup.sharedFlush(true, .5, .5);
            assertEquals(List.of("PlayerReach"), setup.attackAlong(0, 4.1, 0));
        }
        // The motion after the push replaces it: from 5 back to 4.5 enters the box at 4.7.
        try (var setup = new Setup()) {
            setup.hold("{\"min_reach\":5,\"max_reach\":4,\"hitbox_margin\":1}");
            setup.drift(0);
            setup.sharedFlush(false, .5, .5);
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
    void aTargetMovingAwayPastTheMinimumReachIsJudgedAtItsFarthestPosition() throws Exception {
        try (var setup = new Setup()) {
            setup.hold(0, null);
            setup.hold(1, "{\"min_reach\":2,\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            setup.rotate(90);
            setup.tickEnd();
            setup.selectSlot(1);
            assertEquals(List.of(), setup.attackAhead(1.5, .6, 0));
            assertEquals(List.of("PlayerReach"), setup.attackAhead(1, .2, 0));
        }
    }

    @Test
    void aMarginOnlyHitIsJudgedAtTheFarthestCornerOfTheTargetBoxes() throws Exception {
        try (var setup = new Setup()) {
            setup.hold(0, "{\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            setup.hold(1, "{\"min_reach\":2,\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            setup.rotate(0);
            setup.tickEnd();
            assertEquals(List.of(), setup.attackAheadSelecting(.4, 1.5, .6, 1));
            setup.selectSlot(0);
            setup.tickEnd();
            assertEquals(List.of(), setup.attackAheadSelecting(.4, 1.5, 0, 1));
        }
    }

    @Test
    void aMarginHitOnATargetMovingAwayIsBoundedByItsNearestPointForTheMaximum() throws Exception {
        try (var setup = new Setup()) {
            setup.hold(0, "{\"max_reach\":3,\"hitbox_margin\":0.3}");
            setup.rotate(0);
            setup.tickEnd();
            assertEquals(List.of(), setup.attackSelecting(new Vec3(.58, 0, 3.55), new Vec3(0, 0, .6), 0, 0));
        }
    }

    @Test
    void aCrouchingTargetBelowTheAttackerKeepsItsLegalMinimumReach() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of(), setup.attackBelow(EntityPose.CROUCHING));
        }
    }

    @Test
    void aStandingTargetBelowTheAttackerHitTooCloseFlagsTheMinimumReach() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of("PlayerReach"), setup.attackBelow(EntityPose.STANDING));
        }
    }

    @Test
    void aStandingPoseTheClientMayNotHaveHandledKeepsTheCrouchingMinimumReach() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of(), setup.attackBelowStandingUp(false));
            assertEquals(List.of("PlayerReach"), setup.attackBelowStandingUp(true));
        }
    }

    @Test
    void aCrouchingMannequinBelowTheAttackerKeepsItsLegalMinimumReach() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of(), setup.attackBelow(MANNEQUIN, EntityPose.CROUCHING));
        }
    }

    @Test
    void aStandingMannequinBelowTheAttackerHitTooCloseFlagsTheMinimumReach() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of("EntityReach"), setup.attackBelow(MANNEQUIN, EntityPose.STANDING));
        }
    }

    @Test
    void aScaleTheClientMayNotHaveHandledKeepsTheSmallerMinimumReach() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of(), setup.attackBelowScaledUp(false));
            assertEquals(List.of("PlayerReach"), setup.attackBelowScaledUp(true));
        }
    }

    @Test
    void anEarlierHorseFlagsPingKeepsALaterRideableScaleItCannotProve() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of(), setup.horseScaledAfterFlags(false, 1));
        }
        try (var setup = new Setup()) {
            assertEquals(List.of("EntityReach"), setup.horseScaledAfterFlags(true, 2));
        }
    }

    @Test
    void anEarlierBoostProofKeepsALaterRideableScaleItCannotProve() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of(), setup.pigScaledAfterBoost(false, 1));
        }
        try (var setup = new Setup()) {
            assertEquals(List.of("EntityReach"), setup.pigScaledAfterBoost(true, 2));
        }
    }

    @Test
    void aScaleHeldForAnUnansweredSpawnKeepsTheDefaultScaleUntilItsOwnProof() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of(), setup.horseScaledBeforeSpawnAnswer(false, 2F, 1F, 2));
        }
        try (var setup = new Setup()) {
            assertEquals(List.of("EntityReach"), setup.horseScaledBeforeSpawnAnswer(true, 2F, 2F, 2));
        }
    }

    @Test
    void scalesHeldForAnUnansweredSpawnKeepEveryIntermediateScale() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of(), setup.horseScaledBeforeSpawnAnswer(false, 2F, .5F, .5, 2));
        }
        try (var setup = new Setup()) {
            assertEquals(List.of("EntityReach"), setup.horseScaledBeforeSpawnAnswer(true, 2F, 2F, .5, 2));
        }
    }

    @Test
    void manyScalesHeldForAnUnansweredSpawnStayBoundedAndKeepTheSmallest() throws Exception {
        double[] bases = {1.5, .5, 3, 2.5, 1.25, 4, 1.75, 3.5, 2};
        try (var setup = new Setup()) {
            assertEquals(List.of(), setup.horseScaledBeforeSpawnAnswer(false, 2F, .5F, bases));
        }
        try (var setup = new Setup()) {
            assertEquals(List.of("EntityReach"), setup.horseScaledBeforeSpawnAnswer(true, 2F, 2F, bases));
        }
    }

    @Test
    void aScaleForTheLastOfManyUnansweredSpawnsIsHeld() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of(), setup.horseScaledAfterManySpawns());
        }
    }

    @Test
    void attributesForManyIdsNeverSpawnedStayBounded() throws Exception {
        try (var setup = new Setup()) {
            setup.holdForUnspawnedIds();
        }
    }

    @Test
    void aHeldScaleMergedIntoALaterSizeNeutralProofRetiresAtThatProof() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of("EntityReach"), setup.horseScaledThenSpedBeforeSpawnAnswer(new double[] {2}, 1F));
        }
    }

    @Test
    void heldScalesMergedIntoALaterSizeNeutralProofKeepTheSmallestUntilIt() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of("EntityReach"), setup.horseScaledThenSpedBeforeSpawnAnswer(new double[] {.5, 2}, .5F));
        }
    }

    @Test
    void interleavedSizeUpdatesNeverBoundTheTargetAboveWhatTheClientMayShow() throws Exception {
        try (var setup = new Setup()) {
            setup.convergeThroughSizeUpdates();
        }
    }

    @Test
    void aScaleProvenWhileTheTargetWasTheVehicleLeavesNoSmallerAlternative() throws Exception {
        try (var setup = new Setup()) {
            List<String> flagged = setup.attackDismountedScaledHorse(
                    horse -> assertEquals(2F, horse.leastScale()), horse -> assertEquals(2F, horse.leastScale()));
            assertEquals(List.of("EntityReach"), flagged);
        }
    }

    @Test
    void aSmallTargetsUnhandledPosesKeepTheirLeastWidthAndHeightSeparately() throws Exception {
        try (var setup = new Setup()) {
            List<String> flagged = setup.attackSmallStanderPosedAsleep(target -> {
                assertEquals(.12F, target.leastPoseWidth(), 1.0E-6F);
                assertEquals(.2F, target.leastPoseHeight(), 1.0E-6F);
            });
            assertEquals(List.of(), flagged);
            PacketEntityPlayer target = setup.answerPoses();
            assertEquals(.2F, target.leastPoseWidth(), 1.0E-6F);
            assertEquals(.2F, target.leastPoseHeight(), 1.0E-6F);
        }
    }

    @Test
    void aMarginHitFromInsideTheGrownTargetBoxesIsJudgedAtItsFarthestProjection() throws Exception {
        try (var setup = new Setup()) {
            setup.hold(0, "{\"min_reach\":2,\"max_reach\":4.5,\"hitbox_margin\":1}");
            setup.hold(1, "{\"min_reach\":4.3,\"max_reach\":4.5,\"hitbox_margin\":1}");
            setup.rotate(0);
            setup.tickEnd();
            assertEquals(List.of(), setup.attackSelecting(new Vec3(0, 0, 3), new Vec3(1.1, 0, .5), 1, 0));
        }
    }

    @Test
    void aMarginHitFromInsideTheGrownTargetBoxesThatMissesTheirUnionIsJudgedAtItsFarthestProjection() throws Exception {
        try (var setup = new Setup()) {
            setup.hold(0, "{\"min_reach\":2,\"max_reach\":4.5,\"hitbox_margin\":1}");
            setup.hold(1, "{\"min_reach\":4.3,\"max_reach\":4.5,\"hitbox_margin\":1}");
            setup.rotate(0);
            setup.tickEnd();
            assertEquals(List.of(), setup.attackSelecting(new Vec3(.35, 0, 3), new Vec3(.75, 0, .5), 1, 0));
        }
    }

    @Test
    void aRayStartingWithinTheMarginPastEveryTargetBoxMisses() throws Exception {
        try (var setup = new Setup()) {
            setup.hold(0, "{\"min_reach\":2,\"max_reach\":4.5,\"hitbox_margin\":1}");
            setup.hold(1, "{\"min_reach\":2,\"max_reach\":4.5,\"hitbox_margin\":1}");
            setup.rotate(0);
            setup.tickEnd();
            assertEquals(
                    List.of("PlayerHitboxes"), setup.attackSelecting(new Vec3(1, 0, 2.3), new Vec3(0, 0, 0), 1, 0));
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

    // A Java client holds a target moved only by proven movements exactly where the server tracks it.
    @Test
    void aTargetMovedOnlyByBundledMovementsIsJudgedWithoutAMargin() throws Exception {
        try (var setup = new Setup()) {
            assertFalse(setup.attack(2.99));
            assertTrue(setup.attack(3.01));
            assertEquals(List.of(), setup.attackAfter(2.99, entity -> setup.moveAwayAndBack(entity, 4, true)));
            assertEquals(
                    List.of("EntityReach"), setup.attackAfter(3.01, entity -> setup.moveAwayAndBack(entity, 4, true)));
        }
    }

    // The client may have received an unbundled movement before any tick between its proofs.
    @Test
    void aTargetMovedByAnUnbundledMovementKeepsTheMargin() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of(), setup.attackAfter(3.01, entity -> setup.moveAwayAndBack(entity, 2, false)));
            assertEquals(
                    List.of("EntityReach"), setup.attackAfter(3.10, entity -> setup.moveAwayAndBack(entity, 2, false)));
        }
    }

    // A folded queue samples the client's interpolation only approximately.
    @Test
    void aTargetWhoseQueueFoldedKeepsTheMargin() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of(), setup.attackAfter(3.01, entity -> setup.moveAwayAndBack(entity, 200, true)));
            assertEquals(
                    List.of("EntityReach"),
                    setup.attackAfter(3.10, entity -> setup.moveAwayAndBack(entity, 200, true)));
        }
    }

    // A snap assigns a position without proving that every feasible client took it.
    @Test
    void aSnapOntoAFarPositionSyncKeepsTheMargin() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of(), setup.attackAfter(3.01, entity -> snapAwayAndBack(setup, entity)));
            assertEquals(List.of("EntityReach"), setup.attackAfter(3.10, entity -> snapAwayAndBack(setup, entity)));
        }
    }

    private static void snapAwayAndBack(Setup setup, PacketEntity entity) {
        setup.moveAwayAndBack(entity, 2, false);
        Vec3 at = entity.newPacketLocation.targetPosition();
        setup.positionSync(entity.getEntityId(), at.add(100, 0, 0));
        setup.positionSync(entity.getEntityId(), at);
        setup.moveAwayAndBack(entity, 2, true);
    }

    // The client sets a far teleport's position at once, where the model interpolates toward it.
    @Test
    void aTargetTeleportedAwayAndBackKeepsTheMargin() throws Exception {
        try (var setup = new Setup()) {
            setup.bind();
            int id = 101;
            double z = 3.01 - 1.2;
            setup.player.compensatedEntities.addEntity(id, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, z), 0, 0, 0);
            setup.teleport(id, new Vec3(.5, 64, z + 100));
            setup.teleport(id, new Vec3(.5, 64, z - .02));
            assertEquals(List.of(), setup.attackEntity(id, EntityTypeIds.ZOMBIE, () -> {}));
        }
    }

    private static final List<ProtocolVersion> CODEC_BASE_VERSIONS =
            List.of(ProtocolVersion.V26_3, ProtocolVersion.V1_21_4);

    // ClientPacketListener#handleTeleportEntity places the entity and keeps the base relative moves decode from.
    @Test
    void aRelativeMoveAfterATeleportDecodesFromTheBaseTheTeleportKept() throws Exception {
        List<String> failures = new ArrayList<>();
        for (ProtocolVersion version : CODEC_BASE_VERSIONS) {
            for (boolean bundled : new boolean[] {true, false}) {
                try (var setup = new Setup(version)) {
                    int id = setup.spawn(new Vec3(.5, 64, 1));
                    setup.inBundles(bundled, () -> {
                        setup.teleport(id, new Vec3(10.5, 64, 1));
                        setup.moveRelative(id, 0, 0, 1024);
                    });
                    PacketEntity entity = setup.player.compensatedEntities.getEntity(id);
                    Vec3 target = entity.newPacketLocation.targetPosition();
                    if (!target.equals(new Vec3(.5, 64, 1.25)))
                        failures.add(version + " bundled=" + bundled + ": " + target);
                }
            }
        }
        assertEquals(List.of(), failures);
    }

    // The move lands a quarter block past the spawn the teleport left as the base, inside reach.
    @Test
    void aTargetMovedFromTheBaseATeleportKeptIsReachedWhereTheClientPutsIt() throws Exception {
        assertEquals(
                List.of(),
                teleportThenMoveFlags(new Vec3(.5, 64, 1.3), (setup, id) -> {}, new Vec3(.5, 64, 11.3), List.of()));
    }

    // The move lands a quarter block past the spawn, half a block beyond reach, though the teleport placed it in reach.
    @Test
    void aTargetMovedFromTheBaseATeleportKeptFlagsWhereTheTeleportPlacedIt() throws Exception {
        assertEquals(
                List.of(),
                teleportThenMoveFlags(
                        new Vec3(.5, 64, 2.05), (setup, id) -> {}, new Vec3(.5, 64, .8), List.of("EntityReach")));
    }

    // ClientPacketListener#handleEntityPositionSync sets the base that later teleports keep.
    @Test
    void aPositionSyncAfterATeleportResetsTheBase() throws Exception {
        assertEquals(
                List.of(),
                teleportThenMoveFlags(
                        new Vec3(.5, 64, 21.3),
                        (setup, id) -> {
                            setup.teleport(id, new Vec3(.5, 64, 31.3));
                            setup.sendPositionSync(id, new Vec3(.5, 64, 1.3));
                            setup.pongThrough(setup.player.lastTransactionSent.get() + 1);
                        },
                        new Vec3(.5, 64, 11.3),
                        List.of()));
    }

    // ClientPacketListener#handleMoveEntity leaves the position and the base of a rotation-only move.
    @Test
    void aRotationAfterATeleportKeepsThePositionAndTheBase() throws Exception {
        List<String> failures = new ArrayList<>();
        for (ProtocolVersion version : CODEC_BASE_VERSIONS) {
            for (boolean bundled : new boolean[] {true, false}) {
                try (var setup = new Setup(version)) {
                    int id = setup.spawn(new Vec3(.5, 64, 1));
                    setup.inBundles(bundled, () -> {
                        setup.teleport(id, new Vec3(.5, 64, 10.5));
                        setup.turnEntity(id, 90);
                    });
                    var tracked = setup.player.compensatedEntities.getTrackedEntity(id);
                    List<Double> state = List.of(
                            tracked.getX(),
                            tracked.getY(),
                            tracked.getZ(),
                            tracked.getCodecBaseX(),
                            tracked.getCodecBaseY(),
                            tracked.getCodecBaseZ());
                    if (!state.equals(List.of(.5, 64.0, 10.5, .5, 64.0, 1.0)))
                        failures.add(version + " bundled=" + bundled + ": " + state);
                }
            }
        }
        assertEquals(List.of(), failures);
    }

    // ViaBackwards 26.3->26.2 sums a stepped move into the one delta handleMoveEntity decodes from the kept base.
    @Test
    void aViaBackwardsSteppedMoveAfterATeleportDecodesFromTheBaseTheTeleportKept() throws Exception {
        var steps = new EntityDelta.Stepped(List.of(
                new EntityDelta.Step((short) 0, (short) 0, (short) 512, 1),
                new EntityDelta.Step((short) 0, (short) 0, (short) 512, 1)));
        assertEquals(
                List.of(),
                trackedAfterAMove(
                        () -> new Setup(ProtocolVersion.V26_3, ProtocolVersion.V26_2),
                        "minecraft:zombie",
                        (setup, id) -> setup.teleport(id, new Vec3(.5, 64, 10)),
                        steps,
                        new Vec3(.5, 64, 1.25)));
    }

    // ItemFrame#recalculateBoundingBox syncs the base on the Entity#setPos a hanging entity's teleport reaches.
    @Test
    void aRelativeMoveAfterAnItemFrameTeleportDecodesFromTheTeleport() throws Exception {
        assertEquals(
                List.of(),
                trackedAfterAMove(
                        () -> new Setup(ProtocolVersion.V26_3),
                        "minecraft:item_frame",
                        (setup, id) -> setup.teleport(id, new Vec3(.5, 64, 10)),
                        new EntityDelta.Linear((short) 0, (short) 0, (short) 1024),
                        new Vec3(.5, 64, 10.25)));
    }

    // ClientPacketListener#handleMinecartAlongTrack only queues lerp steps.
    @Test
    void aRelativeMoveAfterAMinecartStepDecodesFromTheBaseTheStepKept() throws Exception {
        assertEquals(
                List.of(),
                trackedAfterAMove(
                        () -> new Setup(ProtocolVersion.V26_3),
                        "minecraft:minecart",
                        (setup, id) -> setup.moveMinecart(id, new Vec3(.5, 64, 10)),
                        new EntityDelta.Linear((short) 0, (short) 0, (short) 1024),
                        new Vec3(.5, 64, 1.25)));
    }

    /**
     * Spawns this type at (.5, 64, 1), places it, then moves it by this delta; returns each bundling whose tracked
     * position differs from expected.
     */
    private static List<String> trackedAfterAMove(
            Callable<Setup> setups, String type, ObjIntConsumer<Setup> place, EntityDelta move, Vec3 expected)
            throws Exception {
        List<String> failures = new ArrayList<>();
        for (boolean bundled : new boolean[] {true, false}) {
            try (var setup = setups.call()) {
                int id = setup.spawn(type, new Vec3(.5, 64, 1));
                setup.inBundles(bundled, () -> {
                    place.accept(setup, id);
                    setup.moveRelative(id, move);
                });
                var tracked = setup.player.compensatedEntities.getTrackedEntity(id);
                Vec3 position = new Vec3(tracked.getX(), tracked.getY(), tracked.getZ());
                if (!position.equals(expected)) failures.add("bundled=" + bundled + ": " + position);
            }
        }
        return failures;
    }

    /**
     * Spawns a zombie, runs before, teleports it and moves it a quarter block along the look, then attacks it once its
     * interpolation finished; returns each version and bundling whose flags differ from expected.
     */
    private static List<String> teleportThenMoveFlags(
            Vec3 spawn, ObjIntConsumer<Setup> before, Vec3 teleport, List<String> expected) throws Exception {
        List<String> failures = new ArrayList<>();
        for (ProtocolVersion version : CODEC_BASE_VERSIONS) {
            for (boolean bundled : new boolean[] {true, false}) {
                try (var setup = new Setup(version)) {
                    int id = setup.spawn(spawn);
                    setup.inBundles(bundled, () -> {
                        before.accept(setup, id);
                        setup.teleport(id, teleport);
                        setup.moveRelative(id, 0, 0, 1024);
                    });
                    PacketEntity entity = setup.player.compensatedEntities.getEntity(id);
                    for (int tick = 0; tick < 100 && entity.newPacketLocation.hasActiveInterpolationTarget(); tick++)
                        setup.tickEnd();
                    List<String> flags = setup.attackEntity(id, EntityTypeIds.ZOMBIE, () -> {});
                    if (!flags.equals(expected)) failures.add(version + " bundled=" + bundled + ": " + flags);
                }
            }
        }
        return failures;
    }

    // A legacy client snaps onto a far position sync, where the model interpolates toward it.
    @Test
    void aLegacyTargetSyncedAwayAndBackKeepsTheMargin() throws Exception {
        List<String> failures = new ArrayList<>();
        for (ProtocolVersion version : ProtocolVersion.values()) {
            if (version == ProtocolVersion.V26_3) continue;
            try (var setup = new Setup(version)) {
                setup.bind();
                int id = 101;
                double z = 3.01 - 1.2;
                setup.player.compensatedEntities.addEntity(id, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, z), 0, 0, 0);
                setup.bundledPositionSync(id, new Vec3(.5, 64, z + 100));
                setup.bundledPositionSync(id, new Vec3(.5, 64, z - .02));
                List<String> flags = setup.attackEntity(id, EntityTypeIds.ZOMBIE, () -> {});
                if (!flags.isEmpty()) failures.add(version + ":" + flags);
            }
        }
        assertEquals(List.of(), failures);
    }

    // A client that received an earlier movement late can be within 64 blocks of a sync the model snaps to.
    @Test
    void aFarSyncDoesNotProveASnap() throws Exception {
        try (var setup = new Setup()) {
            int id = 101;
            setup.player.compensatedEntities.addEntity(id, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, 1), 0, 0, 0);
            PacketEntity entity = setup.player.compensatedEntities.getEntity(id);
            entity.onPositionPath(
                    EntityPositionPath.linear(new Vec3(1.5, 64, 1)), true, null, null, setup.player, false);
            entity.onMovement(setup.player, true);
            setup.positionSync(id, new Vec3(-63.3, 64, 1));
            assertFalse(entity.newPacketLocation.hasExactPosition());
        }
    }

    // Answering the ping before an unbundled sync does not prove the client handled it.
    @Test
    void anUnbundledFarSyncAtItsFirstBoundaryKeepsTheMargin() throws Exception {
        try (var setup = new Setup()) {
            int id = 101;
            setup.player.compensatedEntities.addEntity(id, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, 1), 0, 0, 0);
            PacketEntity entity = setup.player.compensatedEntities.getEntity(id);
            setup.pongThrough(setup.sendUnbundledPositionSync(id, new Vec3(100.5, 64, 1)));
            assertFalse(entity.newPacketLocation.hasExactPosition());
        }
    }

    // A seat derived from a vehicle whose position is uncertain is uncertain too.
    @Test
    void aRiderSettledAtAnUncertainSeatKeepsTheMargin() throws Exception {
        try (var setup = new Setup()) {
            setup.player.compensatedEntities.addEntity(101, EntityTypeIds.OAK_BOAT, new Vec3(.5, 64, 1), 0, 0, 0);
            setup.player.compensatedEntities.addEntity(102, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, 1), 0, 0, 0);
            PacketEntity boat = setup.player.compensatedEntities.getEntity(101);
            PacketEntity rider = setup.player.compensatedEntities.getEntity(102);
            rider.mount(boat);
            boat.onFirstTransaction(false, true, .56, 64, 1, setup.player);
            assertFalse(boat.newPacketLocation.hasExactPosition());
            setup.player.compensatedEntities.settleIfUntrackedRider(rider);
            rider.eject();
            assertFalse(rider.newPacketLocation.hasExactPosition());
        }
    }

    // A passenger attached to a vehicle whose position is uncertain is uncertain too.
    @Test
    void aPassengerOfAnUncertainVehicleKeepsTheMargin() throws Exception {
        try (var setup = new Setup()) {
            setup.player.compensatedEntities.addEntity(101, EntityTypeIds.OAK_BOAT, new Vec3(.5, 64, 1), 0, 0, 0);
            setup.player.compensatedEntities.addEntity(102, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, 1), 0, 0, 0);
            PacketEntity boat = setup.player.compensatedEntities.getEntity(101);
            PacketEntity passenger = setup.player.compensatedEntities.getEntity(102);
            passenger.mount(boat);
            setup.player.compensatedEntities.positionPassenger(boat, passenger);
            assertTrue(passenger.newPacketLocation.hasExactPosition());
            boat.onFirstTransaction(false, true, .56, 64, 1, setup.player);
            setup.player.compensatedEntities.positionPassenger(boat, passenger);
            passenger.eject();
            assertFalse(passenger.newPacketLocation.hasExactPosition());
        }
    }

    // A late receipt of the root's move leaves the client's rider 2.98 away at any chain depth.
    @Test
    void aSeatAtTheEndOfAVehicleChainKeepsTheMargin() throws Exception {
        for (int depth : new int[] {1, 65, 66, 80}) {
            try (var setup = new Setup()) {
                var entities = setup.player.compensatedEntities;
                entities.addEntity(200, EntityTypeIds.OAK_BOAT, new Vec3(.5, 64, 1.78), 0, 0, 0);
                PacketEntity root = entities.getEntity(200);
                PacketEntity rider = root;
                for (int i = 1; i <= depth; i++) {
                    entities.addEntity(200 + i, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, 1.78), 0, 0, 0);
                    PacketEntity child = entities.getEntity(200 + i);
                    child.mount(rider);
                    rider = child;
                }
                setup.tickEnd();
                root.onFirstTransaction(false, true, .5, 64, 1.84, setup.player);
                entities.settleIfUntrackedRider(rider);
                rider.eject();
                Vec3 seat = rider.clientPhysicalPosition;
                rider.onPositionPath(EntityPositionPath.linear(seat), true, 1F, 0F, setup.player, true);
                setup.player.y = seat.y;
                setup.player.lastY = seat.y;
                assertEquals(
                        List.of(),
                        setup.attackEntity(rider.getEntityId(), EntityTypeIds.ZOMBIE, () -> {}),
                        "depth " + depth);
            }
        }
    }

    // A rider whose vehicle chain has no root gets no seat and no proof.
    @Test
    void aRiderOfAVehicleCycleKeepsTheMargin() throws Exception {
        try (var setup = new Setup()) {
            var entities = setup.player.compensatedEntities;
            entities.addEntity(101, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, 1), 0, 0, 0);
            entities.addEntity(102, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, 1), 0, 0, 0);
            PacketEntity rider = entities.getEntity(101);
            PacketEntity vehicle = entities.getEntity(102);
            rider.mount(vehicle);
            vehicle.mount(rider);
            assertTrue(rider.newPacketLocation.hasExactPosition());
            entities.settleIfUntrackedRider(rider);
            assertFalse(rider.newPacketLocation.hasExactPosition());
        }
    }

    // Mounting and dismounting before any ride tick leaves the client's rider at its spawn, 2.99 away.
    @Test
    void aRiderMountedAndDismountedBeforeItsRideTickKeepsTheMargin() throws Exception {
        try (var setup = new Setup()) {
            setup.bind();
            var entities = setup.player.compensatedEntities;
            entities.addEntity(301, EntityTypeIds.OAK_BOAT, new Vec3(.5, 64, 1.81), 0, 0, 0);
            entities.addEntity(302, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, 1.79), 0, 0, 0);
            PacketEntity rider = entities.getEntity(302);
            setup.setPassengers(301, List.of(302));
            setup.setPassengers(301, List.of());
            Vec3 seat = rider.clientPhysicalPosition;
            setup.bundledPositionSync(302, seat);
            setup.player.y = seat.y;
            setup.player.lastY = seat.y;
            assertEquals(List.of(), setup.attackEntity(302, EntityTypeIds.ZOMBIE, () -> {}));
        }
    }

    // A rider seated before its ride tick is still at its spawn on the client, 2.99 away.
    @Test
    void aRiderMountedBeforeItsRideTickKeepsTheMargin() throws Exception {
        try (var setup = new Setup()) {
            setup.bind();
            var entities = setup.player.compensatedEntities;
            entities.addEntity(301, EntityTypeIds.OAK_BOAT, new Vec3(.5, 64, 1.81), 0, 0, 0);
            entities.addEntity(302, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, 1.79), 0, 0, 0);
            PacketEntity rider = entities.getEntity(302);
            setup.setPassengers(301, List.of(302));
            assertFalse(rider.newPacketLocation.hasExactPosition());
        }
    }

    // ServerGamePacketListenerImpl#handleAttack accepts attacks up to 3 past entity_interaction_range.
    @Test
    void boatsAreBoundedByTheEntityInteractionRange() throws Exception {
        try (var setup = new Setup()) {
            for (String key : List.of("oak_boat", "oak_chest_boat", "bamboo_raft", "bamboo_chest_raft")) {
                int type = EntityTypes.defaults().byKey(key).id();
                assertEquals(List.of(), setup.attackBoat(type, 2.9, 0), key);
                assertEquals(List.of("EntityReach"), setup.attackBoat(type, 3.1, 0), key);
                assertEquals(List.of("EntityReach"), setup.attackBoat(type, 5.5, 0), key);
            }
        }
    }

    // AbstractBoat interpolates linearly to the last position the client may have received.
    @Test
    void aMovingBoatIsJudgedWhereverItsInterpolationMayHaveIt() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of(), setup.attackBoat(EntityTypeIds.OAK_BOAT, 3.5, -1));
            assertEquals(List.of(), setup.attackBoat(EntityTypeIds.OAK_BOAT, 2.9, 1));
            assertEquals(List.of("EntityReach"), setup.attackBoat(EntityTypeIds.OAK_BOAT, 3.1, 1));
        }
    }

    @Test
    void aRiderAttackingItsOwnBoatIsInRange() throws Exception {
        try (var setup = new Setup()) {
            setup.board(0);
            setup.rotate(0, 90);
            setup.tickEnd();
            assertEquals(List.of(), setup.attackEntity(Setup.BOAT, EntityTypeIds.OAK_BOAT, () -> setup.rotate(0, 90)));
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
            this(ProtocolVersion.V26_3, false);
        }

        // A client that joined with this version, not one Via translates from 26.3.
        Setup(ProtocolVersion version) throws Exception {
            this(version, true);
        }

        // A client of this version that Via translates the server's packets for.
        Setup(ProtocolVersion server, ProtocolVersion client) throws Exception {
            this(server, false);
            Mockito.when(fixture.connection.platform().getClientProtocol()).thenReturn(client);
        }

        private Setup(ProtocolVersion version, boolean originalClient) throws Exception {
            fixture = new RecordReceiveFixture(version, false);
            if (originalClient)
                Mockito.when(fixture.connection.platform().getClientProtocol()).thenReturn(version);
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
            var event = new PacketReceiveEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ServerboundPackets.SET_CARRIED_ITEM, packet);
            player.checkManager.dispatchEarlyReceive(event);
            player.checkManager.dispatchReceiveHandlers(event);
        }

        void rotate(float yaw) {
            rotate(yaw, 0);
        }

        // A client tick at this yaw that travels on the delta movement the last tick left: Minecraft#handleKeybinds
        // attacks before LivingEntity#travel, and LocalPlayer#sendPosition sends the end unless within 2.0E-4.
        void travel(float yaw) {
            Set<Vec3> next = player.checkManager.getSimulationProcessor().getLastDerivedNextTickVelocities();
            // Alternatives the client may have travelled on leave the tick's end open; only one known delta decides it.
            Vec3 delta = next.isEmpty() ? Vec3.ZERO : next.iterator().next();
            for (Vec3 alternative : next) {
                if (alternative.distanceToSqr(delta) > 1.0E-18) delta = Vec3.ZERO;
            }
            if (delta.lengthSqr() <= 2.0E-4 * 2.0E-4) {
                rotate(yaw);
                return;
            }
            player.lastX = player.x;
            player.lastY = player.y;
            player.lastZ = player.z;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);
            var packet = new ServerboundMovePlayer(
                    player.x + delta.x, player.y + delta.y, player.z + delta.z, yaw, 0, false, false, true, true);
            listener.onMovePlayer(
                    new PacketReceiveEvent<>(
                            fixture.user, ConnectionPhase.PLAY, ServerboundPackets.MOVE_PLAYER, packet),
                    player,
                    packet);
        }

        void rotate(float yaw, float pitch) {
            var packet = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, false, false, false, true);
            listener.onMovePlayer(
                    new PacketReceiveEvent<>(
                            fixture.user, ConnectionPhase.PLAY, ServerboundPackets.MOVE_PLAYER, packet),
                    player,
                    packet);
        }

        // Stone from the feet to above the eye, 1.5 blocks down the look.
        void wall() {
            block(0, 64, 0, "stone");
            block(0, 65, 0, "stone");
        }

        void block(int x, int y, int z, String block) {
            int state =
                    DataTables.defaults().registry().block("minecraft:" + block).defaultState();
            player.compensatedWorld.applyBlockChangeRawDANGER(x, y, z, state);
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

        // A motion and an explosion along +z in one flush bundle, both proven by its one ping, answered before the
        // next pick.
        void sharedFlush(boolean motionFirst, double speed, double push) {
            bind();
            int ping = player.sendTrailingTransaction();
            var motionPacket = new ClientboundEntityMotion(player.entityID, new Vec3d(0, 0, speed));
            var motion = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_MOTION, motionPacket, false);
            var explodePacket =
                    new ClientboundExplode(new Vec3d(player.x, player.y, player.z), new Vec3d(0, 0, push));
            var explode = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.EXPLODE, explodePacket, false);
            motion.sharedPing(ping);
            explode.sharedPing(ping);
            var knockback = player.checkManager.getKnockbackHandler();
            var explosion = player.checkManager.getExplosionHandler();
            if (!motionFirst) explosion.onExplode(explode, player, explodePacket);
            knockback.handleEntityVelocity(motion, motionPacket);
            if (motionFirst) explosion.onExplode(explode, player, explodePacket);
            motion.getTasksAfterSend().forEach(Runnable::run);
            explode.getTasksAfterSend().forEach(Runnable::run);
            assertEquals(ping, knockback.getLastSent().getTransaction());
            assertEquals(ping, explosion.getLastSent().getTransaction());
            pongThrough(ping);
            assertNotNull(knockback.secondBread);
            assertNotNull(explosion.secondBread);
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

        // As attackAhead, the target's relative move this much further ahead sent but not yet proven, and the client
        // ticks its interpolation could have finished in.
        List<String> attackAhead(double distance, double moved, float yaw) {
            return attackAt(new Vec3(.5, 64, distance - 1.2), moved, () -> rotate(yaw));
        }

        // As attackAhead looking straight ahead at a target centred this far to the side, selecting this slot after
        // the client's last pick.
        List<String> attackAheadSelecting(double side, double distance, double moved, int slot) {
            return attackAt(
                    new Vec3(.5 + side, 64, distance - 1.2),
                    new Vec3(0, 0, moved),
                    () -> selectSlot(slot),
                    () -> rotate(0));
        }

        // A target whose feet are this far from the attacker's, its relative move pending as in attackAhead, attacked
        // looking at this pitch with yaw 0 and selecting this slot after the client's last pick.
        List<String> attackSelecting(Vec3 offset, Vec3 moved, int slot, float pitch) {
            Vec3 position = new Vec3(player.x + offset.x, player.y + offset.y, player.z + offset.z);
            return attackAt(position, moved, () -> selectSlot(slot), () -> rotate(0, pitch));
        }

        // A still player in this pose with its feet 1.9 below and 2 ahead of the attacker's, attacked with an item of
        // minimum reach 2.7 and no margin after a tick ending at yaw 90, pitch 45 and sending yaw 0, pitch 45: the
        // item ray looks along yaw 90 and misses, so the view hit at 2.02 * sqrt(2) through a crouching target's top
        // is the vanilla hit.
        List<String> attackBelow(EntityPose pose) {
            return attackBelow(EntityTypeIds.PLAYER, pose);
        }

        // As attackBelow, the target an entity of this type.
        List<String> attackBelow(int type, EntityPose pose) {
            return attackBelow(type, 2.7, () -> player.compensatedEntities.getEntity(nextEntity - 1).actionPose = pose);
        }

        // As attackBelow with minimum reach 2.4, a standing target's attributes scaling it by 1.25 applied at the ping
        // before it, and the ping after it answered or not: unscaled, the view hit through its top is at 2.43.
        List<String> attackBelowScaledUp(boolean handled) {
            return attackBelow(EntityTypeIds.PLAYER, 2.4, () -> {
                int id = nextEntity - 1;
                scale(id, 1.25);
                int before = player.lastTransactionSent.get();
                pongThrough(handled ? before + 1 : before);
                assertEquals(1.25F, player.compensatedEntities.getEntity(id).scale);
            });
        }

        // A horse 3.5 ahead scaled by 2 while it was the player's vehicle, its proof answered, then left and its later
        // pings answered, attacked with an item of minimum reach 2.7 and no margin after a tick ending at yaw 90 and
        // sending yaw 0: the view hit through its near face is at 2.1. afterProof and beforeAttack see the horse then.
        List<String> attackDismountedScaledHorse(
                Consumer<PacketEntity> afterProof, Consumer<PacketEntity> beforeAttack) {
            hold("{\"min_reach\":2.7,\"max_reach\":4.5,\"hitbox_margin\":0}");
            rotate(90, 0);
            tickEnd();
            Vec3 position = new Vec3(player.x, player.y, player.z + 3.5);
            return attackAt(
                    EntityTypeIds.HORSE,
                    position,
                    Vec3.ZERO,
                    () -> {
                        int id = nextEntity - 1;
                        PacketEntity horse = player.compensatedEntities.getEntity(id);
                        player.compensatedEntities.vehicles.serverPlayerVehicle = id;
                        scale(id, 2);
                        pongThrough(player.lastTransactionSent.get());
                        afterProof.accept(horse);
                        player.compensatedEntities.vehicles.serverPlayerVehicle = null;
                        for (int tick = 0; tick < 3; tick++) tickEnd();
                        pongThrough(player.lastTransactionSent.get());
                        beforeAttack.accept(horse);
                    },
                    () -> rotate(0, 0));
        }

        // An update_attributes setting this entity's scale base.
        private void scale(int id, double base) {
            attribute(id, "minecraft:scale", base);
        }

        // An update_attributes setting this base of this entity's attribute.
        private void attribute(int id, String attribute, double base) {
            bind();
            var packet =
                    new ClientboundUpdateAttributes(id, List.of(new AttributeSnapshot(attribute, base, List.of())));
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, packet, false);
            player.packetEntityReplication.onUpdateAttributes(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
        }

        // As attackBelow, a crouching target's entity data standing it up applied at the ping before it, and the ping
        // after it answered or not.
        List<String> attackBelowStandingUp(boolean handled) {
            return attackBelow(EntityTypeIds.PLAYER, 2.7, () -> {
                int id = nextEntity - 1;
                player.compensatedEntities.getEntity(id).actionPose = EntityPose.CROUCHING;
                pose(id, EntityPose.STANDING);
                int before = player.lastTransactionSent.get();
                pongThrough(handled ? before + 1 : before);
                assertEquals(EntityPose.STANDING, player.compensatedEntities.getEntity(id).actionPose);
            });
        }

        // A standing player of scale .2 whose feet are 1.5 above and 2.78 ahead of the attacker's, its last move
        // proven, attacked with an item of minimum reach 2.7 and no margin after a tick ending at yaw 90 and sending
        // yaw 0: the item ray misses and the view hit through its near face at 2.72 is the vanilla hit. Entity data
        // posing it sleeping, standing and sleeping is applied at the ping before the attack, the ping after it
        // unanswered; beforeAttack sees the target then.
        List<String> attackSmallStanderPosedAsleep(Consumer<PacketEntityPlayer> beforeAttack) {
            hold("{\"min_reach\":2.7,\"max_reach\":4.5,\"hitbox_margin\":0}");
            rotate(90, 0);
            tickEnd();
            Vec3 position = new Vec3(player.x, player.y + 1.5, player.z + 2.77);
            return attackAt(
                    position,
                    Vec3.ZERO,
                    () -> {
                        int id = nextEntity - 1;
                        var target = (PacketEntityPlayer) player.compensatedEntities.getEntity(id);
                        target.scale = .2F;
                        target.onFirstTransaction(true, true, 0, 0, .01, player);
                        for (int tick = 0; tick < 3; tick++) tickEnd();
                        target.onSecondTransaction();
                        pose(id, EntityPose.SLEEPING);
                        pose(id, EntityPose.STANDING);
                        pose(id, EntityPose.SLEEPING);
                        pongThrough(player.lastTransactionSent.get());
                        assertEquals(EntityPose.SLEEPING, target.actionPose);
                        beforeAttack.accept(target);
                    },
                    () -> rotate(0, 0));
        }

        // Answers the ping after the last entity data, returning the last target.
        PacketEntityPlayer answerPoses() {
            pongThrough(player.lastTransactionSent.get() + 1);
            return (PacketEntityPlayer) player.compensatedEntities.getEntity(nextEntity - 1);
        }

        // Entity data posing this entity, applied at the last ping sent.
        private void pose(int id, EntityPose pose) {
            metadata(id, WatchableIndexUtil.ENTITY_POSE, pose);
        }

        // Entity data setting this field of this entity.
        private void metadata(int id, int index, Object value) {
            bind();
            var packet =
                    new EntityMetadata(id, List.of(new EntityMetadata.Entry(index, value, ByteBuffer.allocate(0))));
            var event = new PacketSendEvent<>(fixture.user, ConnectionPhase.PLAY, metadataType(), packet, false);
            player.packetEntityReplication.onSetEntityData(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
        }

        // A horse 3.5 ahead with its feet .5 above the attacker's, its view hit at 2.8 at scale 1 and 2.1 at scale 2,
        // as in attackRideableScaledAfterData with horse flags as the data; asserts its least scale before the attack.
        List<String> horseScaledAfterFlags(boolean scaleProven, float leastScale) {
            int flags =
                    WatchableIndexUtil.forModel(player.user.getCultConnection()).horseFlags();
            return attackRideableScaledAfterData(
                    EntityTypeIds.HORSE,
                    new Vec3(0, .5, 3.5),
                    flags,
                    (byte) 2,
                    scaleProven,
                    horse -> assertEquals(leastScale, horse.leastScale()));
        }

        // A pig 3.2 ahead with its feet 1 above the attacker's, its view hit at 2.75 at scale 1 and 2.3 at scale 2,
        // as in attackRideableScaledAfterData with its boost time as the data; asserts its least scale before the
        // attack.
        List<String> pigScaledAfterBoost(boolean scaleProven, float leastScale) {
            int boost =
                    WatchableIndexUtil.forModel(player.user.getCultConnection()).pigBoostTime();
            return attackRideableScaledAfterData(
                    EntityTypeIds.PIG,
                    new Vec3(0, 1, 3.2),
                    boost,
                    40,
                    scaleProven,
                    pig -> assertEquals(leastScale, pig.leastScale()));
        }

        // A rideable of this type at this offset from the attacker's feet, attacked with an item of minimum reach 2.7
        // and no margin after a tick ending at yaw 90 and sending yaw 0, so the view hit is the vanilla hit. Before the
        // attack, entity data setting this field is sent with its ping N, attributes scaling it by 2 are applied at
        // once before N's answer, then N is answered, and the scale's own ping too if scaleProven. beforeAttack sees
        // the target then.
        List<String> attackRideableScaledAfterData(
                int type,
                Vec3 offset,
                int index,
                Object value,
                boolean scaleProven,
                Consumer<PacketEntity> beforeAttack) {
            hold("{\"min_reach\":2.7,\"max_reach\":4.5,\"hitbox_margin\":0}");
            rotate(90, 0);
            tickEnd();
            Vec3 position = new Vec3(player.x + offset.x, player.y + offset.y, player.z + offset.z);
            return attackAt(
                    type,
                    position,
                    Vec3.ZERO,
                    () -> {
                        int id = nextEntity - 1;
                        int before = player.lastTransactionSent.get();
                        metadata(id, index, value);
                        int data = player.lastTransactionSent.get();
                        assertEquals(before + 1, data);
                        scale(id, 2);
                        assertEquals(2F, player.compensatedEntities.getEntity(id).scale);
                        pongThrough(data);
                        if (scaleProven) pongThrough(data + 1);
                        beforeAttack.accept(player.compensatedEntities.getEntity(id));
                    },
                    () -> rotate(0, 0));
        }

        // A horse 3.5 ahead with its feet .5 above the attacker's, its view hit at 2.8 at scale 1 and 2.1 at scale 2,
        // attacked with an item of minimum reach 2.7 and no margin after a tick ending at yaw 90 and sending yaw 0.
        // It spawns through add_entity with its proof S, attributes with these scale bases follow before S's answer,
        // then S is answered, and the scales' own ping too if scaleProven; asserts its scale and least scale then.
        // Once every ping is answered, its least scale is its scale.
        List<String> horseScaledBeforeSpawnAnswer(boolean scaleProven, float scale, float leastScale, double... bases) {
            hold("{\"min_reach\":2.7,\"max_reach\":4.5,\"hitbox_margin\":0}");
            rotate(90, 0);
            tickEnd();
            int id = nextEntity++;
            spawn(id, "minecraft:horse", new Vec3d(player.x, player.y + .5, player.z + 3.5));
            int spawn = player.lastTransactionSent.get();
            assertNull(player.compensatedEntities.getEntity(id));
            for (int held = 1; held <= bases.length; held++) {
                scale(id, bases[held - 1]);
                assertEquals(Math.min(held, 4), player.compensatedEntities.heldAttributeUpdates(id));
            }
            pongThrough(spawn);
            if (scaleProven) pongThrough(spawn + 1);
            PacketEntity horse = player.compensatedEntities.getEntity(id);
            assertEquals(scale, horse.scale);
            assertEquals(leastScale, horse.leastScale());
            List<String> flagged = attackEntity(id, EntityTypeIds.HORSE, () -> rotate(0, 0));
            pongThrough(player.lastTransactionSent.get() + 1);
            assertEquals(horse.scale, horse.leastScale());
            return flagged;
        }

        // As horseScaledBeforeSpawnAnswer, attributes with these scale bases each followed by a ping, then four
        // movement_speed-only attributes, all before the spawn proof S's answer: the held updates merge into the last
        // proof P. Until P is answered the least scale is leastScale; once it is, 2, and the attack flags or not.
        List<String> horseScaledThenSpedBeforeSpawnAnswer(double[] bases, float leastScale) {
            hold("{\"min_reach\":2.7,\"max_reach\":4.5,\"hitbox_margin\":0}");
            rotate(90, 0);
            tickEnd();
            int id = nextEntity++;
            spawn(id, "minecraft:horse", new Vec3d(player.x, player.y + .5, player.z + 3.5));
            int spawn = player.lastTransactionSent.get();
            for (double base : bases) {
                scale(id, base);
                player.sendTransaction();
            }
            for (int sped = 0; sped < 4; sped++) attribute(id, "minecraft:movement_speed", .3);
            int last = player.lastTransactionSent.get() + 1;
            assertEquals(4, player.compensatedEntities.heldAttributeUpdates(id));
            for (int answered = spawn; answered < last; answered++) {
                pongThrough(answered);
                assertEquals(
                        leastScale, player.compensatedEntities.getEntity(id).leastScale(), "after " + answered);
            }
            pongThrough(last);
            PacketEntity horse = player.compensatedEntities.getEntity(id);
            assertEquals(2F, horse.scale);
            assertEquals(2F, horse.leastScale());
            return attackEntity(id, EntityTypeIds.HORSE, () -> rotate(0, 0));
        }

        // 256 zombies behind the attacker spawn with attributes each, then a horse 4 ahead with its feet .5 above the
        // attacker's spawns scaled by 2, all before any answer; once every ping is answered, the empty hand attacks
        // it at yaw 0: its scale-2 near face is at 2.6, within the interaction range 3, and its scale-1 one at 3.3.
        List<String> horseScaledAfterManySpawns() {
            for (int filler = 0; filler < 256; filler++) {
                int id = nextEntity++;
                spawn(id, "minecraft:zombie", new Vec3d(player.x, player.y, player.z - 20));
                scale(id, 1);
            }
            int id = nextEntity++;
            spawn(id, "minecraft:horse", new Vec3d(player.x, player.y + .5, player.z + 4));
            scale(id, 2);
            assertNull(player.compensatedEntities.getEntity(id));
            pongThrough(player.lastTransactionSent.get() + 1);
            PacketEntity horse = player.compensatedEntities.getEntity(id);
            assertEquals(2F, horse.scale);
            assertEquals(2F, horse.leastScale());
            rotate(0, 0);
            tickEnd();
            return attackEntity(id, EntityTypeIds.HORSE, () -> rotate(0, 0));
        }

        // Attributes for 257 ids never spawned hold only the first 256, while a tracked spawn's still hold.
        void holdForUnspawnedIds() {
            int first = nextEntity;
            for (int orphan = 0; orphan < 257; orphan++) scale(nextEntity++, 2);
            assertEquals(1, player.compensatedEntities.heldAttributeUpdates(first + 255));
            assertEquals(0, player.compensatedEntities.heldAttributeUpdates(first + 256));
            int id = nextEntity++;
            spawn(id, "minecraft:horse", new Vec3d(player.x, player.y, player.z + 4));
            scale(id, 2);
            assertEquals(1, player.compensatedEntities.heldAttributeUpdates(id));
        }

        // An add_entity of this type at this position, its proof unanswered.
        private void spawn(int id, String type, Vec3d position) {
            bind();
            var packet = new ClientboundAddEntity(id, type, position, 0, 0, 0);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, packet, false);
            player.packetEntityReplication.onAddEntity(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
        }

        // A player 3.2 ahead with its feet .3 below the attacker's eyes takes interleaved pose, scale and size-neutral
        // entity data, pings and in-order answers. After each step the least size is at most every size the client
        // may show, and an attack straight ahead with an item of minimum reach 2.7 and no margin, whose view hit lies
        // between 2.75 and 2.9 for every such size, flags nothing.
        void convergeThroughSizeUpdates() {
            hold("{\"min_reach\":2.7,\"max_reach\":4.5,\"hitbox_margin\":0}");
            rotate(0, 0);
            tickEnd();
            bind();
            int id = nextEntity++;
            player.compensatedEntities.addEntity(
                    id, EntityTypeIds.PLAYER, new Vec3(player.x, player.y + 1.32, player.z + 3.2), 0, 0, 0);
            PacketEntity target = player.compensatedEntities.getEntity(id);
            EntityPose pose = EntityPose.STANDING;
            float scale = 1;
            // Each size the client may show and the transaction whose answer proves it applied it: an attack follows
            // the answer to every ping before the size, so the client shows a size from the last proven to the last
            // whose preceding ping is answered.
            List<float[]> shown = new ArrayList<>(List.of(new float[] {.6F, 1.8F}));
            List<Integer> provenBy = new ArrayList<>(List.of(Integer.MIN_VALUE));
            ArrayDeque<Integer> pings = new ArrayDeque<>();
            List<String> steps = List.of(
                    "crouch",
                    "scale 1.25",
                    "ping",
                    "flags",
                    "swim",
                    "pong",
                    "scale 1.5",
                    "ping",
                    "stand",
                    "ping",
                    "pong",
                    "flags",
                    "scale 1",
                    "ping",
                    "fly",
                    "pong",
                    "pong",
                    "ping",
                    "pong");
            for (String step : steps) {
                int proof = player.lastTransactionSent.get() + 1;
                String[] words = step.split(" ");
                switch (words[0]) {
                    case "crouch" -> pose = EntityPose.CROUCHING;
                    case "swim" -> pose = EntityPose.SWIMMING;
                    case "stand" -> pose = EntityPose.STANDING;
                    case "fly" -> pose = EntityPose.FALL_FLYING;
                    case "scale" -> scale = Float.parseFloat(words[1]);
                    default -> {}
                }
                switch (words[0]) {
                    case "crouch", "swim", "stand", "fly" -> pose(id, pose);
                    case "scale" -> scale(id, scale);
                    case "flags" -> metadata(id, WatchableIndexUtil.ENTITY_SHARED_FLAGS, (byte) 0);
                    case "ping" -> {
                        player.sendTransaction();
                        pings.add(player.lastTransactionSent.get());
                    }
                    case "pong" -> pongThrough(pings.remove());
                    default -> throw new IllegalArgumentException(step);
                }
                if (!words[0].equals("ping") && !words[0].equals("pong") && !words[0].equals("flags")) {
                    shown.add(new float[] {PacketEntity.avatarWidth(pose, scale), PacketEntity.avatarHeight(pose, scale)
                    });
                    provenBy.add(proof);
                }
                int received = player.lastTransactionReceived.get();
                int first = 0;
                for (int k = 0; k < provenBy.size(); k++) if (provenBy.get(k) <= received) first = k;
                float width = Float.MAX_VALUE, height = Float.MAX_VALUE;
                for (int k = first; k < shown.size() && provenBy.get(k) <= received + 1; k++) {
                    width = Math.min(width, shown.get(k)[0]);
                    height = Math.min(height, shown.get(k)[1]);
                }
                String state = step + ": least " + target.leastPoseWidth() + " x " + target.leastPoseHeight()
                        + ", shown " + width + " x " + height + ", answered " + received + ", proofs " + provenBy;
                assertTrue(target.leastPoseWidth() <= width + 1.0E-6F, state);
                assertTrue(target.leastPoseHeight() <= height + 1.0E-6F, state);
                assertEquals(List.of(), attackEntity(id, EntityTypeIds.PLAYER, () -> rotate(0, 0)), step);
            }
            pongThrough(player.lastTransactionSent.get());
            assertEquals(PacketEntity.avatarWidth(pose, scale), target.leastPoseWidth(), 1.0E-6F);
            assertEquals(PacketEntity.avatarHeight(pose, scale), target.leastPoseHeight(), 1.0E-6F);
        }

        private List<String> attackBelow(int type, double minReach, Runnable beforeAttack) {
            hold("{\"min_reach\":" + minReach + ",\"max_reach\":4.5,\"hitbox_margin\":0}");
            rotate(90, 45);
            tickEnd();
            Vec3 position = new Vec3(player.x, player.y - 1.9, player.z + 2);
            return attackAt(type, position, Vec3.ZERO, beforeAttack, () -> rotate(0, 45));
        }

        @SuppressWarnings("unchecked")
        private static PacketType<EntityMetadata> metadataType() {
            return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                    .filter(type -> type.recordClass() == EntityMetadata.class)
                    .findFirst()
                    .orElseThrow();
        }

        // One client tick attacking a boat of this type straight ahead at the eye's height whose near face is this far
        // from the eye, its relative move this much further ahead sent but not yet proven.
        List<String> attackBoat(int type, double distance, double moved) {
            Vec3 position = new Vec3(player.x, player.y + 1.62 - .3, player.z + distance + 1.375 / 2);
            return attackAt(type, position, new Vec3(0, 0, moved), () -> {}, () -> rotate(0, 0));
        }

        // One client tick attacking a player whose hitbox face is this far along a look of this yaw from the seat.
        List<String> attackAlong(float lookYaw, double distance, float yaw) {
            return attackAlong(lookYaw, distance, () -> travel(yaw));
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
            return attackAt(position, () -> travel(yaw));
        }

        private List<String> attackAt(Vec3 position, Runnable movement) {
            return attackAt(position, 0, movement);
        }

        private List<String> attackAt(Vec3 position, double moved, Runnable movement) {
            return attackAt(position, new Vec3(0, 0, moved), () -> {}, movement);
        }

        private List<String> attackAt(Vec3 position, Vec3 moved, Runnable beforeAttack, Runnable movement) {
            return attackAt(EntityTypeIds.PLAYER, position, moved, beforeAttack, movement);
        }

        private List<String> attackAt(int type, Vec3 position, Vec3 moved, Runnable beforeAttack, Runnable movement) {
            int id = nextEntity++;
            player.compensatedEntities.addEntity(id, type, position, 0, 0, 0);
            if (moved.x != 0 || moved.y != 0 || moved.z != 0) {
                player.compensatedEntities
                        .getEntity(id)
                        .onFirstTransaction(true, true, moved.x, moved.y, moved.z, player);
                for (int tick = 0; tick < 3; tick++) tickEnd();
            }
            beforeAttack.run();
            return attackEntity(id, type, movement);
        }

        // One client tick attacking this entity, then this movement; returns the checks that flagged.
        private List<String> attackEntity(int id, int type, Runnable movement) {
            double[] before =
                    checks.stream().mapToDouble(check -> check.violations).toArray();
            var packet = new ServerboundInteract(id, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            player.checkManager.dispatchReceiveHandlers(
                    new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, packet));
            movement.run();
            tickEnd();
            assertMeasured(id, type);
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

        record Routed(boolean cancelled, List<String> flagged) {}

        // Attacks a player head-on through the early handlers first, as PacketDispatcher routes it; a cancel ends it.
        Routed routedAttack(double distance) {
            return routedAttack(EntityTypeIds.PLAYER, new Vec3(.5, 64, distance - 1.2));
        }

        Routed routedAttack(int type, Vec3 position) {
            return routedAttack(addTarget(type, position));
        }

        int addTarget(int type, Vec3 position) {
            int id = nextEntity++;
            player.compensatedEntities.addEntity(id, type, position, 0, 0, 0);
            return id;
        }

        Routed routedAttack(int id) {
            var packet = new ServerboundInteract(id, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            var event =
                    new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, packet);
            double[] before =
                    checks.stream().mapToDouble(check -> check.violations).toArray();
            player.checkManager.dispatchEarlyReceive(event);
            if (!event.isCancelled()) player.checkManager.dispatchReceiveHandlers(event);
            for (Check check : checks) {
                if (check instanceof ReachCheck reach) reach.onPlayerTickEnd(null);
            }
            player.checkManager.getListener(ClientVisibilityProcessor.class).onPlayerTickEnd(null);
            List<String> flagged = new ArrayList<>();
            for (int i = 0; i < checks.size(); i++) {
                if (checks.get(i).violations > before[i])
                    flagged.add(checks.get(i).getCheckName());
            }
            return new Routed(event.isCancelled(), flagged);
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

        // Attacks a zombie whose hitbox face is this far down the look once this history of tracked movements, ending
        // where it spawned, has finished interpolating; returns the checks that flagged.
        List<String> attackAfter(double distance, Consumer<PacketEntity> history) {
            int id = nextEntity++;
            player.compensatedEntities.addEntity(id, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, distance - 1.2), 0, 0, 0);
            PacketEntity entity = player.compensatedEntities.getEntity(id);
            history.accept(entity);
            for (int tick = 0; tick < 1_000 && entity.newPacketLocation.hasActiveInterpolationTarget(); tick++)
                tickEnd();
            assertFalse(entity.newPacketLocation.hasActiveInterpolationTarget());
            assertTrue(entity.newPacketLocation.usesSteppedInterpolation());
            return attackEntity(id, EntityTypeIds.ZOMBIE, () -> {});
        }

        // This many movements half a block along +z and back, each proven by its bundle or by its second transaction.
        void moveAwayAndBack(PacketEntity entity, int moves, boolean bundled) {
            Vec3 at = entity.desyncClientPos;
            for (int move = 1; move <= moves; move++) {
                Vec3 to = move % 2 == 0 ? at : at.add(0, 0, .5);
                entity.onPositionPath(EntityPositionPath.linear(to), true, null, null, player, bundled);
                if (!bundled) entity.onSecondTransaction();
            }
        }

        // A position sync outside any bundle, its first and second transactions answered.
        void positionSync(int id, Vec3 to) {
            pongThrough(sendUnbundledPositionSync(id, to) + 1);
        }

        // Sends a position sync outside any bundle; returns its first transaction, unanswered.
        int sendUnbundledPositionSync(int id, Vec3 to) {
            bind();
            useBundleDelimiter(false);
            try {
                sendPositionSync(id, to);
                return player.lastTransactionSent.get();
            } finally {
                useBundleDelimiter(true);
            }
        }

        void setPassengers(int vehicle, List<Integer> passengers) {
            var packet = new ClientboundSetPassengers(vehicle, passengers);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
            player.packetEntityReplication.onSetPassengers(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
            pongThrough(player.lastTransactionSent.get() + 1);
        }

        // A position sync in its own bundle, its proof answered.
        void bundledPositionSync(int id, Vec3 to) {
            sendPositionSync(id, to);
            pongThrough(player.lastTransactionSent.get() + 1);
        }

        private void sendPositionSync(int id, Vec3 to) {
            var packet = new ClientboundEntityPositionSync(
                    id, new PositionPath.Linear(new Vec3d(to.x, to.y, to.z)), 0, 0, false);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_POSITION_SYNC, packet, false);
            player.packetEntityReplication.onEntityPositionSync(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
        }

        // An absolute entity teleport in its own bundle, its proof answered.
        void teleport(int id, Vec3 to) {
            var packet =
                    new ClientboundTeleportEntity(id, new Vec3d(to.x, to.y, to.z), Vec3d.ZERO, 0, 0, Set.of(), false);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false);
            player.packetEntityReplication.onTeleportEntity(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
            pongThrough(player.lastTransactionSent.get() + 1);
        }

        // A zombie spawned by add_entity, which gives the tracker its codec base, its proof answered.
        int spawn(Vec3 at) {
            return spawn("minecraft:zombie", at);
        }

        int spawn(String type, Vec3 at) {
            bind();
            int id = nextEntity++;
            var packet = new ClientboundAddEntity(id, type, new Vec3d(at.x, at.y, at.z), 0, 0, 0);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, packet, false);
            player.packetEntityReplication.onAddEntity(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
            pongThrough(player.lastTransactionSent.get());
            assertNotNull(player.compensatedEntities.getEntity(id));
            return id;
        }

        // A move_entity of these 1/4096 block deltas, its proof answered.
        void moveRelative(int id, int xa, int ya, int za) {
            moveRelative(id, new EntityDelta.Linear((short) xa, (short) ya, (short) za));
        }

        void moveRelative(int id, EntityDelta delta) {
            var packet = new ClientboundMoveEntity(id, delta, 0, 0, false, true, false);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false);
            player.packetEntityReplication.onMoveEntity(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
            pongThrough(player.lastTransactionSent.get() + 1);
        }

        // A move_minecart_along_track ending at this position, its proof answered.
        void moveMinecart(int id, Vec3 to) {
            var step = new ClientboundMoveMinecart.Step(new Vec3d(to.x, to.y, to.z), new Vec3d(0, 0, 0), 0, 0, 1);
            var packet = new ClientboundMoveMinecart(id, List.of(step));
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_MINECART, packet, false);
            player.packetEntityReplication.onMoveMinecart(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
            pongThrough(player.lastTransactionSent.get() + 1);
        }

        // A rotation-only move_entity, its proof answered.
        void turnEntity(int id, float yaw) {
            var packet = new ClientboundMoveEntity(
                    id, new EntityDelta.Linear((short) 0, (short) 0, (short) 0), yaw, 0, false, false, true);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false);
            player.packetEntityReplication.onMoveEntity(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
            pongThrough(player.lastTransactionSent.get() + 1);
        }

        // Sends entity movements in their own bundles, or each between its two pings.
        void inBundles(boolean bundled, Runnable sends) {
            useBundleDelimiter(bundled);
            try {
                sends.run();
            } finally {
                useBundleDelimiter(true);
            }
        }

        private void useBundleDelimiter(boolean use) {
            try {
                Field field = player.packetEntityReplication.getClass().getDeclaredField("useReachBundleDelimiter");
                field.setAccessible(true);
                field.setBoolean(player.packetEntityReplication, use);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
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
