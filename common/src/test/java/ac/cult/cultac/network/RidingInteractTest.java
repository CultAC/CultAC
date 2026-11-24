package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.combat.RidingInteract;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.manager.player.SetbackFixtures;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RidingInteractTest {
    private static final int BOAT = 40, ZOMBIE = 41, COW = 42, CRYSTAL = 43, RIDER = 44;
    // A 26.1 client sends a right-click as one packet with the clicked point.
    private static final List<InteractAction> ACTIONS = List.of(InteractAction.ATTACK, InteractAction.INTERACT_AT);

    @Test
    void clicksOnTheVehicleAndAFellowPassengerFlag() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            setup.rideBoatWithZombie();
            for (int target : new int[] {BOAT, ZOMBIE}) {
                for (var action : ACTIONS) {
                    double before = setup.check.violations;
                    assertTrue(setup.route(target, action).isCancelled());
                    assertEquals(before + 1, setup.check.violations);
                }
            }
        }
    }

    @Test
    void clicksOutsideTheRidingTreeAreNotJudged() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            setup.rideBoatWithZombie();
            setup.entity(COW, EntityTypeIds.COW, new Vec3(.5, 64, .5));
            for (var action : ACTIONS) assertFalse(setup.route(COW, action).isCancelled());
            // On foot the boat is no longer the player's vehicle.
            setup.self().eject();
            for (var action : ACTIONS) assertFalse(setup.route(BOAT, action).isCancelled());
            assertEquals(0, setup.check.violations);
        }
    }

    @Test
    void aRiderWhoseBoxHoldsTheEyeIsPickedFromInside() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            // A box around the eye that rides the player, as a carried player does.
            setup.entity(RIDER, EntityTypeIds.END_CRYSTAL, new Vec3(.5, 64.6, -1.5));
            setup.entity(RIDER).mount(setup.self());
            assertTrue(setup.player.compensatedEntities.certainlySharesRootVehicleWithSelf(setup.entity(RIDER)));
            for (var action : ACTIONS) assertFalse(setup.route(RIDER, action).isCancelled());
            assertEquals(0, setup.check.violations);
        }
    }

    @Test
    void anotherEntityAroundTheEyeLetsTheRayPickTheVehicle() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            setup.rideBoatWithZombie();
            assertTrue(setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled());
            Vec3 eye = setup.eye();
            setup.entity(CRYSTAL, EntityTypeIds.END_CRYSTAL, new Vec3(eye.x, eye.y - 1, eye.z));
            for (var action : ACTIONS) assertFalse(setup.route(BOAT, action).isCancelled());
            assertEquals(1, setup.check.violations);
        }
    }

    @Test
    void anItemRangePickSelectsTheVehicle() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            setup.rideBoatWithZombie();
            setup.hold("{\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            for (var action : ACTIONS) assertFalse(setup.route(BOAT, action).isCancelled());
            assertEquals(0, setup.check.violations);
            setup.tickEnd();
            setup.player.getInventory().inventory.setHeldItem(SimItemStack.EMPTY);
            assertTrue(setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @Test
    void anUnansweredPassengerChangeIsNotJudged() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            setup.rideBoatWithZombie();
            setup.entity(COW, EntityTypeIds.COW, new Vec3(.5, 64, .5));
            // The cow mounts the zombie: until the client answers, it may still stand outside the riding tree.
            int mounted = setup.setPassengers(ZOMBIE, List.of(COW));
            assertFalse(setup.route(COW, InteractAction.INTERACT_AT).isCancelled());
            assertTrue(setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled());
            setup.pongThrough(mounted);
            assertTrue(setup.route(COW, InteractAction.INTERACT_AT).isCancelled());
            // The cow leaves: until the client answers, it may already stand outside the riding tree.
            int left = setup.setPassengers(ZOMBIE, List.of());
            assertFalse(setup.route(COW, InteractAction.INTERACT_AT).isCancelled());
            assertTrue(setup.route(ZOMBIE, InteractAction.INTERACT_AT).isCancelled());
            setup.pongThrough(left);
            assertFalse(setup.route(COW, InteractAction.INTERACT_AT).isCancelled());
            assertEquals(3, setup.check.violations);
        }
    }

    @Test
    void aSpectatorActionOnTheVehicleFlags() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            setup.rideBoatWithZombie();
            setup.player.gamemode = GameMode.SPECTATOR;
            var packet = new ServerboundSpectatorAction(OptionalInt.of(BOAT));
            var event = new PacketReceiveEvent<>(
                    setup.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.SPECTATOR_ACTION, packet);
            setup.player.checkManager.dispatchEarlyReceive(event);
            assertTrue(event.isCancelled());
            assertEquals(1, setup.check.violations);
        }
    }

    @Test
    void anInteractionDecodedAcrossTheAttackPacketSplitIsNotJudged() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V1_21_11)) {
            setup.rideBoatWithZombie();
            for (var action : InteractAction.values())
                assertFalse(setup.route(BOAT, action).isCancelled());
            assertEquals(0, setup.check.violations);
        }
    }

    /** A client on the backend of its own release, so the interaction family is judged. */
    record Pairing(ProtocolVersion host, ClientVersion client) {
        @Override
        public String toString() {
            return client.getReleaseName();
        }
    }

    static Stream<Pairing> nativePairings() {
        return Stream.of(
                new Pairing(ProtocolVersion.V1_21_3, ClientVersion.V_1_21_2),
                new Pairing(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11),
                new Pairing(ProtocolVersion.V26_2, ClientVersion.V_26_2),
                new Pairing(ProtocolVersion.V26_3, ClientVersion.V_26_3));
    }

    private static final int MOUNT = 50, FELLOW = 51, SNOWBALL = 52, DRAGON = 53;

    private static int type(String key) {
        return EntityTypes.defaults().byKey("minecraft:" + key).id();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void nativeClicksOnTheRidingTreeFlagAndOthersAreNotJudged(Pairing pairing) throws Exception {
        try (var setup = new Setup(pairing)) {
            setup.rideBoatWithZombie();
            setup.tickEnd();
            setup.entity(COW, EntityTypeIds.COW, new Vec3(.5, 64, .5));
            for (var action : ACTIONS) {
                assertTrue(setup.route(BOAT, action).isCancelled());
                assertTrue(setup.route(ZOMBIE, action).isCancelled());
                assertFalse(setup.route(COW, action).isCancelled());
            }
            assertEquals(4, setup.check.violations);
            // Another entity around the eye lets the ray reach the vehicle.
            Vec3 eye = setup.eye();
            setup.entity(CRYSTAL, EntityTypeIds.END_CRYSTAL, new Vec3(eye.x, eye.y - 1, eye.z));
            for (var action : ACTIONS) assertFalse(setup.route(BOAT, action).isCancelled());
            assertEquals(4, setup.check.violations);
        }
    }

    /** R-2: a mount the standing, sitting rider never holds the eye of, at scale 1. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void clicksOnEveryMountTypeFlagAtScaleOne(Pairing pairing) throws Exception {
        List<String> mounts = new java.util.ArrayList<>(
                List.of("horse", "donkey", "camel", "pig", "llama", "strider", "bamboo_raft", "player"));
        if (pairing.client().isNewerThanOrEquals(ClientVersion.V_1_21_6)) mounts.add("happy_ghast");
        if (pairing.client().isNewerThanOrEquals(ClientVersion.V_1_21_11)) mounts.add("nautilus");
        for (String mount : mounts) {
            try (var setup = new Setup(pairing)) {
                setup.ride(type(mount), 1);
                for (var action : ACTIONS) assertTrue(setup.route(MOUNT, action).isCancelled(), mount + " " + action);
                assertEquals(2, setup.check.violations, mount);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void aFellowCamelRiderFlags(Pairing pairing) throws Exception {
        try (var setup = new Setup(pairing)) {
            setup.ride(EntityTypeIds.CAMEL, 1);
            setup.entity(FELLOW, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, -1.5));
            setup.entity(FELLOW).mount(setup.entity(MOUNT));
            setup.tickEnd();
            for (var action : ACTIONS) assertTrue(setup.route(FELLOW, action).isCancelled());
        }
    }

    /**
     * R-3/R-4: Avatar's standing eye 1.62 * scale above the feet, 0.6 * scale under the seat, against the mount's
     * height. Cells within 0.02 of the box top are left out.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void theStandingEyeDecidesTheMountByScale(Pairing pairing) throws Exception {
        for (String mount : List.of("oak_boat", "minecart", "horse", "donkey", "camel", "llama")) {
            for (float scale : new float[] {1, .75F, .5F, .25F, .0625F}) {
                try (var setup = new Setup(pairing)) {
                    setup.ride(type(mount), scale);
                    PacketEntity vehicle = setup.entity(MOUNT);
                    var box = vehicle.getPositionCollisionBoxCandidates().getFirst();
                    double eye = setup.feet().y + Pose.STANDING.eyeHeight * scale;
                    if (Math.abs(eye - box.maxY) < .02) continue;
                    boolean inside = eye <= box.maxY;
                    assertEquals(
                            !inside,
                            setup.route(MOUNT, InteractAction.INTERACT_AT).isCancelled(),
                            mount + " at " + scale + ": eye " + eye + " box top " + box.maxY);
                }
            }
        }
    }

    /** R-5: Minecraft#tick picks before LocalPlayer#rideTick seats a player mounted since the last tick. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void boardingFromBelowPicksFromWhereThePlayerSwam(Pairing pairing) throws Exception {
        try (var setup = new Setup(pairing)) {
            setup.entity(BOAT, EntityTypeIds.OAK_BOAT, new Vec3(.5, 64, -1.5));
            setup.place(new Vec3(.5, 62.7, -1.5));
            setup.tickEnd();
            setup.self().mount(setup.entity(BOAT));
            for (var action : ACTIONS) assertFalse(setup.route(BOAT, action).isCancelled());
            setup.tickEnd();
            assertTrue(setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    /** R-6: the 0.4 high poses sit at the seat, with no vehicle attachment under it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void aGlidingOrSpinningRiderPicksTheMinecartFromInside(Pairing pairing) throws Exception {
        try (var setup = new Setup(pairing)) {
            setup.ride(type("minecart"), 1);
            assertTrue(setup.route(MOUNT, InteractAction.INTERACT_AT).isCancelled());
            setup.player.isGliding = true;
            setup.tickEnd();
            assertFalse(setup.route(MOUNT, InteractAction.INTERACT_AT).isCancelled());
            // Player#updatePlayerPose already ran with the flag: the pose outlasts it to the next tick end.
            setup.player.isGliding = false;
            setup.player.refreshPlayerPose(Pose.FALL_FLYING);
            assertFalse(setup.route(MOUNT, InteractAction.INTERACT_AT).isCancelled());
            setup.player.refreshPlayerPose(Pose.STANDING);
            setup.tickEnd();
            assertTrue(setup.route(MOUNT, InteractAction.INTERACT_AT).isCancelled());
            // TridentItem#releaseUsing spins a passenger before 26.1.
            setup.player.riptideSpinAttackTicks = 20;
            setup.tickEnd();
            assertFalse(setup.route(MOUNT, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    /** R-7: through 1.21.3 an ender dragon's parts come right after it, before the entities listed later. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void anEnderDragonPartAroundTheEyeOnlyCountsWhileListedInline(Pairing pairing) throws Exception {
        try (var setup = new Setup(pairing)) {
            setup.rideBoatWithZombie();
            setup.tickEnd();
            setup.entity(DRAGON, EntityTypeIds.ENDER_DRAGON, new Vec3(6.5, 60, -1.5));
            boolean inline = pairing.client().isOlderThan(ClientVersion.V_1_21_4);
            assertEquals(!inline, setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    /** R-8: Projectile#isPickable is the received redirectable_projectile tag, with a 1.0 pick radius. */
    @Test
    void aProjectileTheServerTagsPickableAroundTheEyeLetsTheRayReachTheBoat() throws Exception {
        try (var setup = new Setup(new Pairing(ProtocolVersion.V26_3, ClientVersion.V_26_3))) {
            setup.rideBoatWithZombie();
            setup.tickEnd();
            Vec3 eye = setup.eye();
            setup.entity(SNOWBALL, type("snowball"), new Vec3(eye.x + .8, eye.y - .1, eye.z));
            assertTrue(setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled());
            setup.player.registryState.appendTags(new RegistryTags(Map.of(
                    "minecraft:entity_type",
                    new RegistryTags.Payload(Map.of(
                            "minecraft:redirectable_projectile",
                            List.of(EntityTypeIds.FIREBALL, type("wind_charge"), type("snowball")))))));
            assertFalse(setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    /** R-9: ClientPacketListener#handleMoveVehicle moves the vehicle; the rider follows at its next rideTick. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void aVehicleMovedSinceTheTickEndKeepsTheEyeAtTheOldSeat(Pairing pairing) throws Exception {
        try (var setup = new Setup(pairing)) {
            setup.ride(EntityTypeIds.OAK_BOAT, 1);
            assertTrue(setup.route(MOUNT, InteractAction.INTERACT_AT).isCancelled());
            PacketEntity boat = setup.entity(MOUNT);
            SimpleCollisionBox box =
                    boat.getPositionCollisionBoxCandidates().getFirst().copy();
            // Lifted by 1, the boat's box holds the eye of the old seat.
            boat.setPositionRaw(box.offset(0, 1, 0));
            assertFalse(setup.route(MOUNT, InteractAction.INTERACT_AT).isCancelled());
            setup.tickEnd();
            assertTrue(setup.route(MOUNT, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    /** R-10: a remount every tick, with use held across it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void remountingEveryTickWithUseHeld(Pairing pairing) throws Exception {
        try (var setup = new Setup(pairing)) {
            setup.entity(BOAT, EntityTypeIds.OAK_BOAT, new Vec3(.5, 64, -1.5));
            setup.place(new Vec3(.5, 62.8, -1.5));
            for (int i = 0; i < 5; i++) {
                setup.tickEnd();
                setup.self().mount(setup.entity(BOAT));
                assertFalse(setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled());
                setup.tickEnd();
                setup.self().eject();
                assertFalse(setup.route(BOAT, InteractAction.ATTACK).isCancelled());
            }
            assertEquals(0, setup.check.violations);
        }
    }

    /** R-11: a hotbar key handled after the tick's pick keeps the item pick it ran with. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void aHotbarSwitchAfterTheItemPick(Pairing pairing) throws Exception {
        try (var setup = new Setup(pairing)) {
            setup.rideBoatWithZombie();
            setup.tickEnd();
            setup.hold("{\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            var processor = setup.player.checkManager.getListener(ClientVisibilityProcessor.class);
            processor.onSetCarriedItem(null, setup.player, new ServerboundSetCarriedItem(1));
            setup.player.getInventory().inventory.setHeldItem(SimItemStack.EMPTY);
            // 1.21.2 has no item pick: the vehicle rule holds whatever is held (R-12).
            boolean itemPick = pairing.client().isNewerThanOrEquals(ClientVersion.V_1_21_11);
            assertEquals(!itemPick, setup.route(BOAT, InteractAction.ATTACK).isCancelled());
        }
    }

    /** R-13: a spectator's AttackRange pick exists on 1.21.11 only; LivingEntity#getActiveItem empties from 26.1. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void aSpectatorsItemPick(Pairing pairing) throws Exception {
        try (var setup = new Setup(pairing)) {
            setup.rideBoatWithZombie();
            setup.tickEnd();
            setup.player.gamemode = GameMode.SPECTATOR;
            setup.hold("{\"max_reach\":4.5,\"hitbox_margin\":0.125}");
            boolean itemPick = pairing.client() == ClientVersion.V_1_21_11;
            assertEquals(!itemPick, setup.route(BOAT, InteractAction.ATTACK).isCancelled());
        }
    }

    /** R-14: Entity#startRiding refuses these vehicles, so the client never links them. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void vehiclesTheClientRefusesAreNotJudged(Pairing pairing) throws Exception {
        for (String vehicle : List.of("marker", "ominous_item_spawner", "creaking_transient")) {
            if (EntityTypes.defaults().byKey("minecraft:" + vehicle) == null) continue;
            try (var setup = new Setup(pairing)) {
                setup.ride(type(vehicle), 1);
                assertFalse(setup.route(MOUNT, InteractAction.ATTACK).isCancelled(), vehicle);
            }
        }
    }

    /** R-15: entities whose box may hold the eye, each letting the ray continue to the boat. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void otherPickableEntitiesAroundTheEye(Pairing pairing) throws Exception {
        for (String other : List.of("fireball", "wind_charge", "interaction", "armor_stand", "end_crystal", "tnt")) {
            try (var setup = new Setup(pairing)) {
                setup.rideBoatWithZombie();
                setup.tickEnd();
                Vec3 eye = setup.eye();
                setup.entity(SNOWBALL, type(other), new Vec3(eye.x, eye.y - .3, eye.z));
                assertFalse(setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled(), other);
            }
        }
    }

    /** R-16: no tick since the spawn and a camera that is not the player are not judged. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void unknownEyesAreNotJudged(Pairing pairing) throws Exception {
        try (var setup = new Setup(pairing)) {
            setup.rideBoatWithZombie();
            setup.player.packetStateData.localPlayerTickedSinceSpawn = false;
            assertFalse(setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled());
            setup.player.packetStateData.localPlayerTickedSinceSpawn = true;
            assertTrue(setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    /** R-17: a vehicle removed and added again under its id is a new, unlinked entity. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nativePairings")
    void aVehicleAddedAgainUnderItsIdIsNotTheRidingTree(Pairing pairing) throws Exception {
        try (var setup = new Setup(pairing)) {
            setup.rideBoatWithZombie();
            setup.tickEnd();
            setup.entity(BOAT, EntityTypeIds.OAK_BOAT, new Vec3(.5, 64, -1.5));
            assertFalse(setup.route(BOAT, InteractAction.INTERACT_AT).isCancelled());
            assertEquals(0, setup.check.violations);
        }
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture;
        final CultPlayer player;
        final ProtocolVersion version;
        final RidingInteract check;

        Setup(ProtocolVersion version) throws Exception {
            this(version, null);
        }

        Setup(Pairing pairing) throws Exception {
            this(pairing.host(), pairing.client());
        }

        private Setup(ProtocolVersion version, ClientVersion client) throws Exception {
            this.version = version;
            fixture = version == ProtocolVersion.V26_3
                    ? new RecordReceiveFixture(version, false)
                    : RecordReceiveFixture.olderHost(version, false);
            services.useNetwork(fixture.manager);
            CultAPI.INSTANCE.getPlayerDataManager().addUser(fixture.user);
            player = fixture.user.getCultPlayer();
            if (client != null) JavaTickFixture.setField(CultPlayer.class, player, "resolvedClientVersion", client);
            // PacketOrderC's verbose shapes name the hand tag.
            ac.cult.cultac.checks.impl.verbose.VerboseCodecs.ensureRegistered();
            player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
            check = player.checkManager.getCheck(RidingInteract.class);
            check.setEnabled(true);
            player.setExperimentalChecks(true);
            SetbackFixtures.setLoaded(player.getSetbackTeleportUtil(), true);
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -1.5;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, .5, 64, -1.5);
            player.registryState = new ClientComponentRegistries();
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            world.ensureValidationChunkLoaded(0, 0);
            world.ensureValidationChunkLoaded(0, -1);
            // A camel spawned long ago stands: Camel#getPoseTime is past its 52-tick transition.
            JavaTickFixture.setField(
                    ac.cult.cultac.utils.latency.CompensatedWorld.class, world, "clientGameTime", 1000L);
            fixture.registrar.register(routes -> new CheckManagerListener()
                    .registerForwardingEarlyReceivePackets(routes, PacketListenerPriority.LOW));
        }

        PacketEntity self() {
            return player.compensatedEntities.getSelf();
        }

        PacketEntity entity(int id) {
            return player.compensatedEntities.getEntity(id);
        }

        void entity(int id, int type, Vec3 position) {
            player.compensatedEntities.addEntity(id, type, position, 0, 0, 0);
            // ClientboundAddEntity carries a remote player's profile UUID.
            if (entity(id) instanceof ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer remote)
                remote.profile = java.util.UUID.randomUUID();
        }

        // The player behind a zombie in a boat at its feet, as the client holds them.
        void rideBoatWithZombie() {
            entity(BOAT, EntityTypeIds.OAK_BOAT, new Vec3(.5, 64, -1.5));
            entity(ZOMBIE, EntityTypeIds.ZOMBIE, new Vec3(.5, 64, -1.5));
            entity(ZOMBIE).mount(entity(BOAT));
            self().mount(entity(BOAT));
        }

        // The player alone on a freshly spawned mount at its feet, at the given scale, after one ride tick.
        void ride(int type, float scale) {
            entity(MOUNT, type, new Vec3(.5, 64, -1.5));
            self().scale = scale;
            player.refreshPlayerPose(Pose.STANDING);
            self().mount(entity(MOUNT));
            tickEnd();
        }

        void place(Vec3 position) {
            player.x = position.x;
            player.y = position.y;
            player.z = position.z;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, position.x, position.y, position.z);
        }

        // Entity#positionRider's position for the standing pose.
        Vec3 feet() {
            var riding = self().getRiding();
            return BoundingBoxSize.getRidingOffsetFromVehicle(
                    riding,
                    self(),
                    player,
                    riding.getPositionCollisionBoxCandidates().getFirst());
        }

        // The standing eye of the player on its seat.
        Vec3 eye() {
            var riding = self().getRiding();
            Vec3 seat = BoundingBoxSize.getRidingOffsetFromVehicle(
                    riding,
                    self(),
                    player,
                    riding.getPositionCollisionBoxCandidates().getFirst());
            return seat.add(0, player.getEyeHeight(), 0);
        }

        void hold(String attackRange) {
            var sword = ItemUtil.modelItems().stack("minecraft:iron_sword", 1);
            var components = new Components(Map.of("minecraft:attack_range", JsonParser.parseString(attackRange)));
            player.getInventory().inventory.setHeldItem(new SimItemStack(sword.definition(), 1, components));
        }

        /** Sends a set_passengers without answering it; returns the transaction that proves it. */
        int setPassengers(int vehicle, List<Integer> passengers) {
            var packet = new ClientboundSetPassengers(vehicle, passengers);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
            player.packetEntityReplication.onSetPassengers(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
            return player.lastTransactionSent.get() + 1;
        }

        void pongThrough(int transaction) {
            for (int id = player.lastTransactionReceived.get() + 1; id <= transaction; id++) {
                player.lastTransactionReceived.set(id);
                player.latencyUtils.handleNettySyncTransaction(id);
            }
        }

        PacketReceiveEvent<ServerboundInteract> route(int id, InteractAction action) {
            var packet = new ServerboundInteract(
                    id,
                    action,
                    Hand.MAIN_HAND,
                    action == InteractAction.INTERACT_AT ? Optional.of(new Vec3d(0, .5, -.3)) : Optional.empty(),
                    false);
            var event =
                    new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, packet);
            player.checkManager.dispatchEarlyReceive(event);
            return event;
        }

        void tickEnd() {
            player.packetStateData.localPlayerTickedSinceSpawn = true;
            player.checkManager.getListener(ClientVisibilityProcessor.class).onPlayerTickEnd(null);
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
