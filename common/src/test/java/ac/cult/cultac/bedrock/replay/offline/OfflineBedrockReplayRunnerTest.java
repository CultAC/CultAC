package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockLiquidSensing;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockMoveVector;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class OfflineBedrockReplayRunnerTest {
    private static final Path SCENARIOS = Path.of("/home/hunter/Downloads/CultAC/bedrock-smoketest-scenarios");
    private static final UUID PLAYER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SMALL_REPLAY_INJECTION_DOMAIN = "cultac-bedrock-small-replay-injection/v1:1";
    private static final String SMALL_REPLAY_VERTICAL_INJECTION_DOMAIN = SMALL_REPLAY_INJECTION_DOMAIN + ":vertical";
    private static final long IMPOSSIBLE_MOVEMENT_ALLOWED_FALLOUT_TICKS = 3L;
    private static final double IMPOSSIBLE_MOVEMENT_AXIS_OFFSET = 0.01D;
    private static final double IMPOSSIBLE_HORIZONTAL_MOVEMENT_OFFSET = 0.15D;
    private static final double IMPOSSIBLE_MOVEMENT_FLAG_OFFSET_TOLERANCE = 0.001D;
    private static final double MIN_HORIZONTAL_INPUT = 1.0E-6D;
    private static final String KNOWN_FALSE_FLAG_SCENARIO = "largerun1";
    private static final long KNOWN_FALSE_FLAG_TICK = 6147L;

    @Test
    public void smallReplaysRunThroughProductionBedrockPrediction() throws Exception {
        OfflineCultTestBootstrap.installConfig();

        List<String> failures = new ArrayList<>();
        for (String scenario : smallScenarioNames()) {
            String scenarioPath = "small-scenarios/" + scenario;
            ReplayResult baseline = runScenario(scenarioPath);
            OfflineBedrockReplayScenario loaded = OfflineBedrockReplayScenario.load(
                    SCENARIOS.resolve(scenarioPath));
            String authoredAttack = authoredMovementAttack(loaded.manifest());
            if (authoredAttack != null) {
                String failure = authoredMovementAttackExpectationFailure(
                        scenario + " " + authoredAttack,
                        validationFlags(scenario, baseline));
                if (failure != null) {
                    failures.add(failure);
                }
                // These captures already contain a movement attack. Layering
                // the generic one-frame injection on top would make its
                // allowed flag window meaningless, so injection coverage
                // remains on the clean and packet-flag-only fixtures below.
                continue;
            }
            String failure = bedrockMovementExpectationFailure(scenario + " baseline", baseline, 0, List.of(), 0.0D);
            if (failure != null) {
                failures.add(failure);
            }

            ReplayResult injected = runScenario(scenarioPath, ReplayVariant.injected(scenario));
            failure = injectedBedrockMovementExpectationFailure(scenario, injected);
            if (failure != null) {
                failures.add(failure);
            }
        }

        for (String scenario : List.of("cheating1", "cheating2", "cheating3", "cheating4")) {
            if (!cheatScenarioSelected(scenario)) {
                continue;
            }
            OfflineBedrockReplayScenario loaded = OfflineBedrockReplayScenario.load(
                    SCENARIOS.resolve("cheating").resolve(scenario));
            ReplayResult result = runScenario("cheating/" + scenario);
            JsonObject expected = loaded.manifest().getAsJsonObject("replay")
                    .getAsJsonObject("expectedBedrockMovementAlert");
            String failure = bedrockMovementExpectationFailure(
                    scenario,
                    result,
                    intValue(expected, "count", 0),
                    expectedOffsets(expected),
                    doubleValue(expected, "offsetTolerance", 0.0D));
            if (failure != null) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) {
            fail(String.join("\n", failures));
        }
    }

    @Test
    public void impossibleMovementInjectionIndexIgnoresPositionsAndTicks() {
        String replayName = "selection-stability";
        int frameCount = 9;
        List<BedrockAuthInputFrame> first = testFrames(frameCount, 1, 10L, 3.0D, 4.0D);
        List<BedrockAuthInputFrame> second = testFrames(frameCount, 1, 1000L, -8.0D, 15.0D);
        int horizontalIndex = selectHorizontalMovementInjectionFrameIndex(replayName, first);
        int verticalIndex = selectVerticalMovementInjectionFrameIndex(replayName, frameCount, horizontalIndex);

        ReplayFrames firstInjected = applyImpossibleMovementInjection(replayName, first);
        ReplayFrames secondInjected = applyImpossibleMovementInjection(replayName, second);

        assertEquals(horizontalIndex, firstInjected.injection().mutation(MutationKind.HORIZONTAL).selectedIndex());
        assertEquals(verticalIndex, firstInjected.injection().mutation(MutationKind.VERTICAL).selectedIndex());
        assertEquals(horizontalIndex, secondInjected.injection().mutation(MutationKind.HORIZONTAL).selectedIndex());
        assertEquals(verticalIndex, secondInjected.injection().mutation(MutationKind.VERTICAL).selectedIndex());
    }

    @Test
    public void bedrockTeleportAcceptanceUsesExistingTransactionAndExactEcho() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlinePlayer();
        Vec3 target = new Vec3(10.0D, 64.0D, 20.0D);
        player.getSetbackTeleportUtil().addSentTeleport(
                target, 10, new RelativeFlag(0), false, -1);
        long revision = player.getSetbackTeleportUtil()
                .addImmediateBedrockTransportTeleport(target, false);

        player.lastTransactionReceived.set(10);
        TeleportAcceptData accepted = player.getSetbackTeleportUtil()
                .acknowledgeBedrockTeleportFrame(target);
        assertTrue(accepted.isTeleport());
        assertTrue(accepted.isMatchedTeleportPosition());
        assertEquals(target, accepted.getTeleportData().getLocation());
        assertFalse(player.getSetbackTeleportUtil().hasPendingPlayerPositionTeleport());
        closeOfflinePlayer(player);
    }

    @Test
    public void translatedBedrockMovementRequiresEarlierAuthPluginMessage() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlinePlayer();
        player.x = 10.0D;
        player.y = 64.0D;
        player.z = 20.0D;
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.getSetbackTeleportUtil().hasFullyJoined = true;
        ServerboundMovePlayerPacket.Pos translatedMove = new ServerboundMovePlayerPacket.Pos(
                10.25D, 64.0D, 20.0D, true, false);

        PacketReceiveEvent outOfOrder = translatedMovementEvent(player, translatedMove);
        new CheckManagerListener().onMovePlayer(outOfOrder, player, translatedMove);
        assertTrue(outOfOrder.isCancelled());

        player.packetStateData.grantBedrockTranslatedMovementPermit(1L, true, true);
        PacketReceiveEvent ordered = translatedMovementEvent(player, translatedMove);
        new CheckManagerListener().onMovePlayer(ordered, player, translatedMove);
        assertFalse(ordered.isCancelled());

        PacketReceiveEvent duplicated = translatedMovementEvent(player, translatedMove);
        new CheckManagerListener().onMovePlayer(duplicated, player, translatedMove);
        assertTrue(duplicated.isCancelled());

        player.packetStateData.grantBedrockTranslatedMovementPermit(2L, true, true);
        player.packetStateData.grantBedrockTranslatedMovementPermit(2L, true, true);
        PacketReceiveEvent afterDuplicateProof = translatedMovementEvent(player, translatedMove);
        new CheckManagerListener().onMovePlayer(afterDuplicateProof, player, translatedMove);
        assertFalse(afterDuplicateProof.isCancelled());
        PacketReceiveEvent proofCannotAccumulate = translatedMovementEvent(player, translatedMove);
        new CheckManagerListener().onMovePlayer(proofCannotAccumulate, player, translatedMove);
        assertTrue(proofCannotAccumulate.isCancelled());
        closeOfflinePlayer(player);
    }

    @Test
    public void everyGeyserMovePlayerProjectionConsumesTheOneShotPermit() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlinePlayer();
        player.x = 10.0D;
        player.y = 64.0D;
        player.z = 20.0D;
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.getSetbackTeleportUtil().hasFullyJoined = true;
        CheckManagerListener listener = new CheckManagerListener();

        List<ServerboundMovePlayerPacket> geyserProjectionShapes = List.of(
                new ServerboundMovePlayerPacket.Pos(10.25D, 64.0D, 20.0D, true, false),
                new ServerboundMovePlayerPacket.PosRot(10.25D, 64.0D, 20.0D, 45.0F, 10.0F, true, false),
                new ServerboundMovePlayerPacket.Rot(45.0F, 10.0F, true, false),
                new ServerboundMovePlayerPacket.StatusOnly(true, false));

        for (ServerboundMovePlayerPacket projection : geyserProjectionShapes) {
            player.packetStateData.grantBedrockTranslatedMovementPermit(3L, true, true);
            PacketReceiveEvent permitted = translatedMovementEvent(player, projection);
            listener.onMovePlayer(permitted, player, projection);
            assertFalse(permitted.isCancelled());

            PacketReceiveEvent replayed = translatedMovementEvent(player, projection);
            listener.onMovePlayer(replayed, player, projection);
            assertTrue(replayed.isCancelled());
        }

        player.packetStateData.grantBedrockTranslatedMovementPermit(4L, true, true);
        ServerboundMovePlayerPacket.Rot rotation = new ServerboundMovePlayerPacket.Rot(
                90.0F, 15.0F, true, false);
        PacketReceiveEvent permittedRotation = translatedMovementEvent(player, rotation);
        listener.onMovePlayer(permittedRotation, player, rotation);
        assertFalse(permittedRotation.isCancelled());

        ServerboundMovePlayerPacket.Pos positionAfterRotation = new ServerboundMovePlayerPacket.Pos(
                10.5D, 64.0D, 20.0D, true, false);
        PacketReceiveEvent secondProjection = translatedMovementEvent(player, positionAfterRotation);
        listener.onMovePlayer(secondProjection, player, positionAfterRotation);
        assertTrue(secondProjection.isCancelled());
        closeOfflinePlayer(player);
    }

    @Test
    public void tickEndAndDisabledSetbacksCannotLeaveAReusableTranslationPermit() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlinePlayer();
        player.x = 10.0D;
        player.y = 64.0D;
        player.z = 20.0D;
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.getSetbackTeleportUtil().hasFullyJoined = true;
        player.bedrockState.setSetbacksEnabled(false);
        CheckManagerListener listener = new CheckManagerListener();
        ServerboundMovePlayerPacket.Pos translatedMove = new ServerboundMovePlayerPacket.Pos(
                10.25D, 64.0D, 20.0D, true, false);

        player.packetStateData.grantBedrockTranslatedMovementPermit(5L, true, true);
        PacketReceiveEvent tickEnd = new PacketReceiveEvent(
                player.user,
                ServerboundClientTickEndPacket.INSTANCE,
                ConnectionProtocol.PLAY);
        listener.onClientTickEnd(tickEnd, player, ServerboundClientTickEndPacket.INSTANCE);

        PacketReceiveEvent afterTickEnd = translatedMovementEvent(player, translatedMove);
        listener.onMovePlayer(afterTickEnd, player, translatedMove);
        assertTrue(afterTickEnd.isCancelled());
        closeOfflinePlayer(player);
    }

    @Test
    public void translatedGroundIsNormalizedWithoutAPlayerViolation() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlinePlayer();
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.getSetbackTeleportUtil().hasFullyJoined = true;
        ServerboundMovePlayerPacket.StatusOnly projection =
                new ServerboundMovePlayerPacket.StatusOnly(false, false);

        player.packetStateData.grantBedrockTranslatedMovementPermit(42L, false, true);
        PacketReceiveEvent event = translatedMovementEvent(player, projection);
        new CheckManagerListener().onMovePlayer(event, player, projection);

        assertFalse(event.isCancelled());
        assertTrue(event.shouldReEncode());
        assertTrue(((ServerboundMovePlayerPacket) event.getNmsPacket()).isOnGround());
        closeOfflinePlayer(player);
    }

    @Test
    public void mismatchedGeyserProjectionFailsClosedWithoutStagingCorrection() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlinePlayer();
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.getSetbackTeleportUtil().hasFullyJoined = true;
        ServerboundMovePlayerPacket.StatusOnly projection =
                new ServerboundMovePlayerPacket.StatusOnly(true, false);

        player.packetStateData.grantBedrockTranslatedMovementPermit(43L, false, false);
        PacketReceiveEvent event = translatedMovementEvent(player, projection);
        new CheckManagerListener().onMovePlayer(event, player, projection);

        assertTrue(event.isCancelled());
        assertFalse(event.shouldReEncode());
        assertEquals(null, player.packetStateData.consumeBedrockTranslatedCanonicalGround());
        closeOfflinePlayer(player);
    }

    @Test
    public void acceptedBedrockSetbackUsesRegularTeleportWithoutRestoringAnchorVelocity() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        OfflineBedrockReplayScenario scenario = OfflineBedrockReplayScenario.load(
                SCENARIOS.resolve("small-scenarios/jump"));
        List<BedrockAuthInputFrame> frames = OfflineBedrockAuthInputCapture.read(
                scenario.packetsPath(), PLAYER_UUID);
        Vec3 initialVelocity = initialVelocity(scenario.manifest(), scenario.packetsPath(), frames.getFirst());
        int firstReplayFrame = initialVelocity == null ? 0 : 1;
        CultPlayer player = offlinePlayer();
        applyFixturePlayerState(player, scenario.manifest());
        SpongeSchematicCompensatedWorldLoader.load(scenario, player.compensatedWorld);
        seedPlayerAt(player, frames.getFirst());
        seedInitialVelocity(player, initialVelocity);
        BedrockAuthInputFrame frame = frames.get(firstReplayFrame);
        player.bedrockState.offerAuthInputFrame(frame);

        PredictionResult result = player.checkManager.getSimulationProcessor()
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.AUTH_INPUT_PLUGIN_MESSAGE);

        assertNotNull(result);
        SetbackPosWithVector safe = player.getSetbackTeleportUtil().lastKnownGoodPosition;
        assertNotNull(safe);
        assertNotNull(safe.getProfileState());
        assertEquals(frame.getPosition(), safe.getPos());
        assertEquals(frame.getPosition(), safe.getProfileState().position());

        int bootstrapTransaction = player.lastTransactionSent.get();
        player.getSetbackTeleportUtil().addSentTeleport(
                safe.getPos(), bootstrapTransaction, new RelativeFlag(0), true, -2);
        player.lastTransactionReceived.set(bootstrapTransaction);
        assertTrue(player.getSetbackTeleportUtil().checkTeleportQueue(
                safe.getPos().x, safe.getPos().y, safe.getPos().z).isTeleport());
        player.getSetbackTeleportUtil().lastKnownGoodPosition = safe;

        assertTrue(player.getSetbackTeleportUtil().executeViolationSetback());
        SetBackData pending = player.getSetbackTeleportUtil().getRequiredSetBack();
        assertNotNull(pending);
        assertFalse(pending.isPlugin());
        assertFalse(pending.isComplete());
        assertNotSame(safe.getProfileState(), pending.getProfileState());
        assertEquals(safe.getProfileState().commit(), pending.getProfileState().commit());
        assertEquals(safe.getPos(), pending.getTeleportData().getLocation());

        for (int queuedFrame = 1; queuedFrame <= 3; queuedFrame++) {
            ServerboundMovePlayerPacket.Pos queuedTranslatedMove = new ServerboundMovePlayerPacket.Pos(
                    safe.getPos().x + queuedFrame * 0.25D,
                    safe.getPos().y,
                    safe.getPos().z,
                    true,
                    false);
            PacketReceiveEvent queuedMovementEvent = translatedMovementEvent(player, queuedTranslatedMove);
            new CheckManagerListener().onMovePlayer(queuedMovementEvent, player, queuedTranslatedMove);
            assertTrue(queuedMovementEvent.isCancelled());
        }

        long revision = player.getSetbackTeleportUtil()
                .addImmediateBedrockTransportTeleport(safe.getPos(), false);
        player.lastTransactionReceived.set(pending.getTeleportData().getTransaction());
        TeleportAcceptData accepted = player.getSetbackTeleportUtil()
                .acknowledgeBedrockTeleportFrame(safe.getPos());
        assertTrue(accepted.isTeleport());
        BedrockAuthInputFrame acknowledgement = withInputFlag(
                BedrockAuthInputFrame.builder(PLAYER_UUID)
                        .clientTick(frame.getClientTick())
                        .position(safe.getPos())
                        .rotation(frame.getYaw(), frame.getPitch(), frame.getHeadYaw())
                        .moveVector(0.0F, 0.0F)
                        .authorityMode("server"),
                PlayerAuthInputData.HANDLE_TELEPORT).build();
        player.checkManager.getSimulationProcessor().applyAcceptedBedrockTeleport(accepted);

        assertTrue(pending.isComplete());
        assertFalse(player.getSetbackTeleportUtil().isPendingSetback());
        SetbackPosWithVector restored = player.getSetbackTeleportUtil().lastKnownGoodPosition;
        assertNotNull(restored.getProfileState());
        assertEquals(safe.getPos(), restored.getPos());
        // The position packet carries absolute zero delta movement. Any later
        // SetEntityMotion is handled by the normal ordered velocity path; the
        // older safe-state velocity must not be restored with the anchor.
        assertEquals(Vec3.ZERO, restored.getVector());

        BedrockAuthInputFrame nextFrame = frames.get(firstReplayFrame + 1);
        player.bedrockState.offerAuthInputFrame(nextFrame);
        PredictionResult nextResult = player.checkManager.getSimulationProcessor()
                .processBedrockAuthInputFrame(nextFrame, BedrockPredictionTrigger.AUTH_INPUT_PLUGIN_MESSAGE);
        assertNotNull(nextResult);
        assertFalse(nextResult.hasFlag(BedrockMovement.class));
        closeOfflinePlayer(player);
    }

    @Test
    public void impossibleMovementInjectionSelectsFrameWithHorizontalInput() {
        String replayName = "input-candidates";
        int frameCount = 7;
        int onlyInputIndex = 3;
        List<BedrockAuthInputFrame> frames = new ArrayList<>(testFrames(frameCount, 1, 40L, 2.0D, -1.0D));
        for (int i = 1; i <= frameCount - 2; i++) {
            if (i != onlyInputIndex) {
                frames.set(i, withMoveVector(frames.get(i), 0.0F, 0.0F));
            }
        }

        assertEquals(onlyInputIndex, selectHorizontalMovementInjectionFrameIndex(replayName, frames));
    }

    @Test
    public void impossibleMovementInjectionVectorUsesSelectedInputDirection() {
        String replayName = "vector-shape";
        int frameCount = 7;
        List<BedrockAuthInputFrame> frames = testFrames(
                frameCount,
                1,
                20L,
                12.0D,
                -9.0D,
                3.0F,
                4.0F,
                0.0F);

        ReplayFrames injected = applyImpossibleMovementInjection(replayName, frames);
        ReplayMutation horizontal = injected.injection().mutation(MutationKind.HORIZONTAL);
        ReplayMutation vertical = injected.injection().mutation(MutationKind.VERTICAL);

        assertEquals(0.09D, horizontal.extra().x, 1.0E-12D);
        assertEquals(0.0D, horizontal.extra().y, 1.0E-12D);
        assertEquals(0.12D, horizontal.extra().z, 1.0E-12D);
        assertEquals(0.0D, vertical.extra().x, 1.0E-12D);
        assertEquals(IMPOSSIBLE_MOVEMENT_AXIS_OFFSET, vertical.extra().y, 1.0E-12D);
        assertEquals(0.0D, vertical.extra().z, 1.0E-12D);
    }

    @Test
    public void impossibleMovementInjectionShiftsOnlyHorizontalAndVerticalSelectedFrames() {
        String replayName = "mutation-boundary";
        int frameCount = 8;
        List<BedrockAuthInputFrame> frames = testFrames(frameCount, 1, 30L, 1.0D, 0.0D);
        int horizontalIndex = selectHorizontalMovementInjectionFrameIndex(replayName, frames);
        int verticalIndex = selectVerticalMovementInjectionFrameIndex(replayName, frameCount, horizontalIndex);

        ReplayFrames injected = applyImpossibleMovementInjection(replayName, frames);

        assertTrue(horizontalIndex != verticalIndex);
        Vec3 horizontalExtra = injected.injection().mutation(MutationKind.HORIZONTAL).extra();
        Vec3 verticalExtra = injected.injection().mutation(MutationKind.VERTICAL).extra();
        for (int i = 0; i < frameCount; i++) {
            BedrockAuthInputFrame original = frames.get(i);
            BedrockAuthInputFrame mutated = injected.frames().get(i);
            Vec3 expectedShift = Vec3.ZERO;
            if (i == horizontalIndex) {
                expectedShift = shift(expectedShift, horizontalExtra);
            }
            if (i == verticalIndex) {
                expectedShift = shift(expectedShift, verticalExtra);
            }
            if (expectedShift != Vec3.ZERO) {
                assertShifted(original.getPosition(), mutated.getPosition(), expectedShift);
                assertShifted(original.getPacketPosition(), mutated.getPacketPosition(), expectedShift);
                assertFrameMetadataUnchanged(original, mutated);
            } else {
                assertTrue(frames.get(i).samePacketAs(injected.frames().get(i)));
            }
        }
    }

    private static List<String> smallScenarioNames() throws Exception {
        Path smallScenarios = SCENARIOS.resolve("small-scenarios");
        try (Stream<Path> paths = Files.list(smallScenarios)) {
            return paths
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(OfflineBedrockReplayRunnerTest::scenarioSelected)
                    .sorted()
                    .toList();
        }
    }

    private static boolean scenarioSelected(String scenario) {
        String filter = System.getProperty("bedrockReplayScenarios", "").trim();
        if (filter.isEmpty()) {
            return true;
        }
        for (String selected : filter.split(",")) {
            if (scenario.equals(selected.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean cheatScenarioSelected(String scenario) {
        String filter = System.getProperty("bedrockReplayCheats", "").trim();
        if (filter.isEmpty()) {
            return true;
        }
        for (String selected : filter.split(",")) {
            if (scenario.equals(selected.trim())) {
                return true;
            }
        }
        return false;
    }

    private static ReplayResult runScenario(String scenarioName) throws Exception {
        return runScenario(scenarioName, ReplayVariant.baseline());
    }

    private static ReplayResult runScenario(String scenarioName, ReplayVariant variant) throws Exception {
        OfflineBedrockReplayScenario scenario = OfflineBedrockReplayScenario.load(
                SCENARIOS.resolve(scenarioName));
        List<BedrockAuthInputFrame> frames = OfflineBedrockAuthInputCapture.read(scenario.packetsPath(), PLAYER_UUID);
        OfflineBedrockReplayEvents.Cursor replayEvents = OfflineBedrockReplayEvents.load(scenario).cursor();
        assertTrue(frames.size() > 1);
        Vec3 initialVelocity = initialVelocity(scenario.manifest(), scenario.packetsPath(), frames.getFirst());
        boolean seededInitialVelocity = initialVelocity != null;
        int firstReplayFrame = seededInitialVelocity ? 1 : 0;
        frames = withReplayStartGliding(frames, scenario.manifest(), firstReplayFrame);
        ReplayInjectionPlan injection = null;
        if (variant.injectImpossibleMovement()) {
            ReplayFrames replayFrames = applyImpossibleMovementInjection(variant.replayName(), frames);
            frames = replayFrames.frames();
            injection = replayFrames.injection();
        }

        CultPlayer player = offlinePlayer();
        // Replays validate prediction against an already-recorded, open-loop
        // client packet stream. Applying a newly generated setback here would
        // insert a server correction that the recorded following frames did
        // not consume, so one accepted false flag would invalidate the rest of
        // the capture instead of remaining the isolated prediction result it
        // was on the original run. Dedicated setback tests keep enforcement
        // enabled through offlinePlayer().
        player.bedrockState.setSetbacksEnabled(false);
        applyFixturePlayerState(player, scenario.manifest());
        SpongeSchematicCompensatedWorldLoader.load(scenario, player.compensatedWorld);
        seedPlayerAt(player, frames.getFirst());
        seedInitialVelocity(player, initialVelocity);

        List<FlagSample> bedrockMovementFlags = new ArrayList<>();
        int processed = 0;
        for (BedrockAuthInputFrame frame : frames.subList(firstReplayFrame, frames.size())) {
            replayEvents.applyBeforeOrAt(player, frame.getClientTick());
            applySprintingAttributeTransition(player, frame);
            player.bedrockState.offerAuthInputFrame(frame);
            PredictionResult result = player.checkManager.getSimulationProcessor()
                    .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.AUTH_INPUT_PLUGIN_MESSAGE);

            assertNotNull(result);
            printReplayDebug(frame, result);
            PredictionResult.Flag bedrockMovementFlag = result.getFlag(BedrockMovement.class);
            if (!result.isExempt() && bedrockMovementFlag != null) {
                bedrockMovementFlags.add(FlagSample.from(frame.getClientTick(), bedrockMovementFlag.getSeverity(), result));
            }
            processed++;
        }
        replayEvents.applyRemaining(player);
        closeOfflinePlayer(player);
        assertTrue(processed > 1);
        return new ReplayResult(bedrockMovementFlags, injection);
    }

    private static void printReplayDebug(BedrockAuthInputFrame frame, PredictionResult result) {
        String filter = System.getProperty("bedrockReplayDebugTicks",
                System.getenv().getOrDefault("BEDROCK_REPLAY_DEBUG_TICKS", "")).trim();
        if (filter.isEmpty() || !debugTickSelected(filter, frame.getClientTick())) {
            return;
        }
        BedrockPredictionResult bedrockResult = result.getProfileResult(BedrockPredictionResult.class);
        if (bedrockResult == null || bedrockResult.movementResult() == null) {
            System.err.println("bedrock-replay-debug tick=" + frame.getClientTick() + " no movement result");
            return;
        }
        BedrockMovementResult movement = bedrockResult.movementResult();
        System.err.println("bedrock-replay-debug tick=" + frame.getClientTick()
                + " prevPos=" + movement.previousState().physicalFeetPosition()
                + " prevVel=" + movement.previousState().velocity()
                + " prevScaffoldAllowed=" + movement.previousState().scaffoldingDescendAllowed()
                + " prevClimbContact=" + movement.previousState().climbableContact()
                + " prevAutoClimb=" + movement.previousState().autoClimbTravel()
                + " prevSwimAmount=" + movement.previousState().swimAmount()
                + " prevFlags=" + movement.previousState().collisionFlags()
                + " prevBranch=" + movement.previousState().movementBranch()
                + " predPos=" + movement.predictedState().physicalFeetPosition()
                + " rawPredPos=" + movement.rawPredictedPhysicalFeetPosition()
                + " predVel=" + movement.predictedState().velocity()
                + " predScaffoldAllowed=" + movement.predictedState().scaffoldingDescendAllowed()
                + " predAutoClimb=" + movement.predictedState().autoClimbTravel()
                + " predSwimAmount=" + movement.predictedState().swimAmount()
                + " predFlags=" + movement.predictedState().collisionFlags()
                + " collisionInputVelocity=" + movement.collisionInputVelocity()
                + " currentSlowdown=" + movement.currentBlockMovementSlowdownState()
                + " predictedPendingSlowdown=" + movement.predictedState().pendingBlockMovementSlowdownState()
                + " selectedWaterTravel=" + movement.selectedWaterTravel()
                + " headInWater=" + BedrockLiquidSensing.waterHeadInWater(
                    movement.movementContext(), movement.previousState().physicalFeetPosition(),
                    movement.movementContext().playerDimensionsState())
                + " orderedPostMoveOwnsHorizontal=" + movement.orderedPostMoveOwnsHorizontalVelocity()
                + " orderedPostMoveOwnsVertical=" + movement.orderedPostMoveOwnsVerticalVelocity()
                + " horizontalFriction=" + movement.horizontalFriction()
                + " inputLimit=" + movement.horizontalInputLimit()
                + " nextBasePos=" + bedrockResult.nextTickBaseState().physicalFeetPosition()
                + " nextBaseVel=" + bedrockResult.nextTickBaseState().velocity()
                + " nextBaseAutoClimb=" + bedrockResult.nextTickBaseState().autoClimbTravel()
                + " nextBaseFlags=" + bedrockResult.nextTickBaseState().collisionFlags()
                + " nextBaseBranch=" + bedrockResult.nextTickBaseState().movementBranch()
                + " obs=" + bedrockResult.observation());
        printNearbyCollisionBoxes(frame, movement);
    }

    private static void printNearbyCollisionBoxes(BedrockAuthInputFrame frame, BedrockMovementResult movement) {
        Vec3d previous = movement.previousState().physicalFeetPosition();
        Vec3d predicted = movement.predictedState().physicalFeetPosition();
        double minX = Math.min(previous.x(), predicted.x()) - 1.0D;
        double maxX = Math.max(previous.x(), predicted.x()) + 1.0D;
        double minY = Math.min(previous.y(), predicted.y()) - 3.0D;
        double maxY = Math.max(previous.y(), predicted.y()) + 3.0D;
        double minZ = Math.min(previous.z(), predicted.z()) - 1.0D;
        double maxZ = Math.max(previous.z(), predicted.z()) + 1.0D;
        List<String> boxes = movement.movementContext().worldState().blockCollisionWorld().blocks().stream()
                .filter(block -> nearDebugRange(block, minX, maxX, minY, maxY, minZ, maxZ))
                .map(OfflineBedrockReplayRunnerTest::formatDebugBlock)
                .distinct()
                .limit(80)
                .toList();
        System.err.println("bedrock-replay-debug-boxes tick=" + frame.getClientTick()
                + " range=[" + minX + "," + minY + "," + minZ + " -> " + maxX + "," + maxY + "," + maxZ + "]"
                + " boxes=" + boxes);
    }

    private static boolean nearDebugRange(
            PlacedBlockCollision block,
            double minX,
            double maxX,
            double minY,
            double maxY,
            double minZ,
            double maxZ
    ) {
        if (block.position().x() + 1.0D < minX || block.position().x() > maxX
                || block.position().y() + 1.0D < minY || block.position().y() > maxY
                || block.position().z() + 1.0D < minZ || block.position().z() > maxZ) {
            return false;
        }
        return true;
    }

    private static String formatDebugBlock(PlacedBlockCollision block) {
        return block.javaState() + " -> " + block.bedrockIdentifier()
                + "@" + block.position()
                + " boxes=" + block.collisionBoxes().stream()
                .map(OfflineBedrockReplayRunnerTest::formatDebugBox)
                .toList();
    }

    private static String formatDebugBox(WorldCollisionBox box) {
        return "[" + box.minX() + "," + box.minY() + "," + box.minZ()
                + " -> " + box.maxX() + "," + box.maxY() + "," + box.maxZ() + "]";
    }

    private static boolean debugTickSelected(String filter, long tick) {
        for (String selected : filter.split(",")) {
            selected = selected.trim();
            if (selected.isEmpty()) {
                continue;
            }
            int range = selected.indexOf('-');
            if (range >= 0) {
                long start = Long.parseLong(selected.substring(0, range));
                long end = Long.parseLong(selected.substring(range + 1));
                if (tick >= start && tick <= end) {
                    return true;
                }
                continue;
            }
            if (tick == Long.parseLong(selected)) {
                return true;
            }
        }
        return false;
    }

    private static List<BedrockAuthInputFrame> withReplayStartGliding(
            List<BedrockAuthInputFrame> frames,
            JsonObject manifest,
            int firstReplayFrame
    ) {
        if (!shouldStartGliding(manifest)) {
            return frames;
        }
        List<BedrockAuthInputFrame> updated = new ArrayList<>(frames);
        updated.set(firstReplayFrame, withStartGliding(updated.get(firstReplayFrame)));
        return List.copyOf(updated);
    }

    static void closeOfflinePlayer(CultPlayer player) {
        Object channel = player.user.getChannel();
        player.onRemove();
        if (channel instanceof EmbeddedChannel embeddedChannel) {
            embeddedChannel.runPendingTasks();
            embeddedChannel.runScheduledPendingTasks();
            embeddedChannel.close();
        }
    }

    private static BedrockAuthInputFrame withStartGliding(BedrockAuthInputFrame frame) {
        long startGlidingFlag = inputFlagMask(PlayerAuthInputData.START_GLIDING);
        return copyFrameBuilder(frame)
                .rawInputFlags(frame.getRawInputFlags() | startGlidingFlag)
                .startGliding(true)
                .build();
    }

    private static BedrockAuthInputFrame.Builder copyFrameBuilder(BedrockAuthInputFrame frame) {
        BedrockMoveVector moveVector = frame.getMoveVector();
        BedrockAuthInputFrame.Builder builder = BedrockAuthInputFrame.builder(frame.getPlayerUuid())
                .protocolVersion(frame.getProtocolVersion().protocol())
                .clientTick(frame.getClientTick())
                .inputMode(frame.getInputMode())
                .playMode(frame.getPlayMode())
                .deviceId(frame.getDeviceId())
                .position(frame.getPosition())
                .packetPosition(frame.getPacketPosition())
                .delta(frame.getDelta())
                .rawInputFlags(frame.getRawInputFlags())
                .rawInputFlagsHigh(frame.getRawInputFlagsHigh())
                .jumping(frame.isJumping())
                .jumpStarted(frame.isJumpStarted())
                .jumpPressedRaw(frame.isJumpPressedRaw())
                .jumpCurrentRaw(frame.isJumpCurrentRaw())
                .wantUp(frame.isWantUp())
                .sneaking(frame.isSneaking())
                .startSneaking(frame.isStartSneaking())
                .stopSneaking(frame.isStopSneaking())
                .sprinting(frame.isSprinting())
                .startSwimming(frame.isStartSwimming())
                .stopSwimming(frame.isStopSwimming())
                .startCrawling(frame.isStartCrawling())
                .stopCrawling(frame.isStopCrawling())
                .startGliding(frame.isStartGliding())
                .stopGliding(frame.isStopGliding())
                .usingItem(frame.isUsingItem())
                .blockAction(frame.hasBlockAction())
                .authorityMode(frame.getAuthorityMode())
                .rewindCorrectionId(frame.getRewindCorrectionId());
        if (frame.hasRotation()) {
            builder.rotation(frame.getYaw(), frame.getPitch(), frame.getHeadYaw());
        }
        if (moveVector != null) {
            builder.moveVector(moveVector.x(), moveVector.z());
        }
        return builder;
    }

    private static boolean shouldStartGliding(JsonObject manifest) {
        JsonObject replay = manifest.getAsJsonObject("replay");
        return replay != null && booleanValue(replay, "startGliding", false);
    }

    private static void applySprintingAttributeTransition(CultPlayer player, BedrockAuthInputFrame frame) {
        if (frame.hasRawInputFlag(PlayerAuthInputData.STOP_SPRINTING)) {
            player.compensatedEntities.hasSprintingAttributeEnabled = false;
        }
        if (frame.hasRawInputFlag(PlayerAuthInputData.START_SPRINTING)) {
            player.compensatedEntities.hasSprintingAttributeEnabled = true;
        }
    }

    private static long inputFlagMask(PlayerAuthInputData input) {
        return input.ordinal() < Long.SIZE ? 1L << input.ordinal() : 0L;
    }

    private static BedrockAuthInputFrame.Builder withInputFlag(
            BedrockAuthInputFrame.Builder builder,
            PlayerAuthInputData input
    ) {
        int ordinal = input.ordinal();
        return ordinal < Long.SIZE
                ? builder.rawInputFlags(1L << ordinal)
                : builder.rawInputFlagsHigh(1L << (ordinal - Long.SIZE));
    }

    private static PacketReceiveEvent translatedMovementEvent(
            CultPlayer player,
            ServerboundMovePlayerPacket packet
    ) {
        return new PacketReceiveEvent(player.user, packet, ConnectionProtocol.PLAY);
    }

    private static ReplayFrames applyImpossibleMovementInjection(String replayName, List<BedrockAuthInputFrame> frames) {
        int horizontalIndex = selectHorizontalMovementInjectionFrameIndex(replayName, frames);
        int verticalIndex = selectVerticalMovementInjectionFrameIndex(replayName, frames.size(), horizontalIndex);
        BedrockAuthInputFrame horizontalSelected = frames.get(horizontalIndex);
        BedrockAuthInputFrame verticalSelected = frames.get(verticalIndex);
        Vec3 horizontalExtra = impossibleHorizontalMovementExtraVector(horizontalSelected);
        Vec3 verticalExtra = new Vec3(0.0D, IMPOSSIBLE_MOVEMENT_AXIS_OFFSET, 0.0D);

        List<BedrockAuthInputFrame> injected = new ArrayList<>(frames);
        for (int i = 0; i < injected.size(); i++) {
            Vec3 shift = Vec3.ZERO;
            if (i == horizontalIndex) {
                shift = shift(shift, horizontalExtra);
            }
            if (i == verticalIndex) {
                shift = shift(shift, verticalExtra);
            }
            if (shift != Vec3.ZERO) {
                injected.set(i, withPositionShift(injected.get(i), shift));
            }
        }
        return new ReplayFrames(
                List.copyOf(injected),
                new ReplayInjectionPlan(List.of(
                        new ReplayMutation(
                                MutationKind.HORIZONTAL,
                                horizontalIndex,
                                horizontalSelected.getClientTick(),
                                frames.get(horizontalIndex + 1).getClientTick(),
                                horizontalExtra),
                        new ReplayMutation(
                                MutationKind.VERTICAL,
                                verticalIndex,
                                verticalSelected.getClientTick(),
                                frames.get(verticalIndex + 1).getClientTick(),
                                verticalExtra))));
    }

    private static int selectHorizontalMovementInjectionFrameIndex(String replayName, List<BedrockAuthInputFrame> frames) {
        int frameCount = frames.size();
        if (frameCount < 3) {
            throw new IllegalArgumentException("Replay " + replayName + " must contain at least 3 auth-input frames");
        }
        List<Integer> candidates = new ArrayList<>();
        for (int i = 1; i <= frameCount - 2; i++) {
            if (hasHorizontalInput(frames.get(i))) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Replay " + replayName
                    + " must contain at least one auth-input frame with horizontal input");
        }
        return candidates.get(selectMovementInjectionOrdinal(
                SMALL_REPLAY_INJECTION_DOMAIN,
                replayName,
                frameCount,
                candidates.size()));
    }

    private static int selectVerticalMovementInjectionFrameIndex(String replayName, int frameCount, int horizontalIndex) {
        if (frameCount < 6) {
            throw new IllegalArgumentException("Replay " + replayName + " must contain at least 6 auth-input frames");
        }
        int splitIndex = Math.max(3, frameCount / 2);
        if (horizontalIndex < splitIndex) {
            return selectMovementInjectionFrameIndex(
                    SMALL_REPLAY_VERTICAL_INJECTION_DOMAIN,
                    replayName,
                    frameCount,
                    splitIndex,
                    frameCount - 2);
        }
        return selectMovementInjectionFrameIndex(
                SMALL_REPLAY_VERTICAL_INJECTION_DOMAIN,
                replayName,
                frameCount,
                1,
                splitIndex - 1);
    }

    private static int selectMovementInjectionFrameIndex(
            String domain,
            String replayName,
            int frameCount,
            int startInclusive,
            int endInclusive
    ) {
        if (startInclusive < 1 || endInclusive > frameCount - 2 || startInclusive > endInclusive) {
            throw new IllegalArgumentException("Invalid injection index range for " + replayName + ": "
                    + startInclusive + ".." + endInclusive + " of " + frameCount);
        }
        return startInclusive + selectMovementInjectionOrdinal(
                domain,
                replayName,
                frameCount,
                endInclusive - startInclusive + 1);
    }

    private static int selectMovementInjectionOrdinal(
            String domain,
            String replayName,
            int frameCount,
            int candidateCount
    ) {
        if (candidateCount <= 0) {
            throw new IllegalArgumentException("Replay " + replayName + " has no injection candidates");
        }
        byte[] input = (domain + "\n" + replayName + "\n" + frameCount + "\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] digest = sha256(input);
        long firstEightBytes = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            firstEightBytes = (firstEightBytes << Byte.SIZE) | (digest[i] & 0xFFL);
        }
        return (int) Long.remainderUnsigned(firstEightBytes, candidateCount);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private static Vec3 impossibleHorizontalMovementExtraVector(BedrockAuthInputFrame selected) {
        BedrockMoveVector input = selected.getMoveVector();
        double inputX = input == null ? 0.0D : input.x();
        double inputZ = input == null ? 0.0D : input.z();
        double inputMagnitude = Math.sqrt(inputX * inputX + inputZ * inputZ);
        if (!(inputMagnitude > MIN_HORIZONTAL_INPUT)) {
            throw new IllegalArgumentException(
                    "Selected auth-input frame has no provable horizontal input at tick "
                            + selected.getClientTick());
        }

        double yawRadians = Math.toRadians(selected.getYaw());
        double sinYaw = Math.sin(yawRadians);
        double cosYaw = Math.cos(yawRadians);
        double worldX = inputX * cosYaw - inputZ * sinYaw;
        double worldZ = inputX * sinYaw + inputZ * cosYaw;
        double worldMagnitude = Math.sqrt(worldX * worldX + worldZ * worldZ);
        return new Vec3(
                worldX / worldMagnitude * IMPOSSIBLE_HORIZONTAL_MOVEMENT_OFFSET,
                0.0D,
                worldZ / worldMagnitude * IMPOSSIBLE_HORIZONTAL_MOVEMENT_OFFSET);
    }

    private static boolean hasHorizontalInput(BedrockAuthInputFrame frame) {
        BedrockMoveVector input = frame.getMoveVector();
        double inputX = input == null ? 0.0D : input.x();
        double inputZ = input == null ? 0.0D : input.z();
        return Math.sqrt(inputX * inputX + inputZ * inputZ) > MIN_HORIZONTAL_INPUT;
    }

    private static BedrockAuthInputFrame withPositionShift(BedrockAuthInputFrame frame, Vec3 shift) {
        return copyFrameBuilder(frame)
                .position(shift(frame.getPosition(), shift))
                .packetPosition(shift(frame.getPacketPosition(), shift))
                .build();
    }

    private static Vec3 shift(Vec3 vector, Vec3 shift) {
        return new Vec3(vector.x + shift.x, vector.y + shift.y, vector.z + shift.z);
    }

    private static String bedrockMovementExpectationFailure(
            String scenarioName,
            ReplayResult result,
            int expectedCount,
            List<Double> expectedOffsets,
            double tolerance
    ) {
        List<FlagSample> flags = validationFlags(scenarioName, result);
        if (expectedCount != flags.size()) {
            return scenarioName + " BedrockMovement flags expected " + expectedCount + " but was "
                    + flags.size() + ": " + flags;
        }
        for (int i = 0; i < expectedOffsets.size(); i++) {
            double expected = expectedOffsets.get(i);
            double actual = flags.get(i).offset();
            if (Math.abs(actual - expected) > tolerance) {
                return scenarioName + " BedrockMovement offset " + i + " expected " + expected + " +/- " + tolerance
                        + " but was " + actual + ": " + flags;
            }
        }
        return null;
    }

    private static String authoredMovementAttack(JsonObject manifest) {
        JsonObject mutation = manifest == null
                || !manifest.has("mutation")
                || !manifest.get("mutation").isJsonObject()
                ? null
                : manifest.getAsJsonObject("mutation");
        if (mutation == null) {
            return null;
        }
        String kind = stringValue(mutation, "kind", "").trim();
        // These mutations alter only authored ground evidence. Their physical
        // position stream remains a legitimate BedrockMovement baseline and
        // is still required to pass the normal H/V injection variants.
        return switch (kind) {
            case "", "nofall", "nofall-twostage" -> null;
            case "nofall-upwipe", "fly", "timer" -> kind;
            default -> throw new IllegalArgumentException(
                    "Unclassified replay mutation kind: " + kind);
        };
    }

    private static String authoredMovementAttackExpectationFailure(
            String scenarioName,
            List<FlagSample> flags
    ) {
        if (flags.isEmpty()) {
            return scenarioName
                    + " authored movement attack produced no BedrockMovement flag";
        }
        if (flags.stream().noneMatch(flag ->
                flag.offset() > IMPOSSIBLE_MOVEMENT_FLAG_OFFSET_TOLERANCE)) {
            return scenarioName
                    + " authored movement attack never exceeded "
                    + IMPOSSIBLE_MOVEMENT_FLAG_OFFSET_TOLERANCE
                    + ": " + flags;
        }
        return null;
    }

    private static String injectedBedrockMovementExpectationFailure(String scenarioName, ReplayResult result) {
        ReplayInjectionPlan injection = result.injection();
        if (injection == null) {
            return scenarioName + " injected replay did not record its selected injection frames";
        }
        int expectedMutations = MutationKind.values().length;
        if (injection.mutations().size() != expectedMutations) {
            return scenarioName + " injected replay expected " + expectedMutations + " mutations but recorded "
                    + injection.mutations().size() + ": " + injection.mutations();
        }
        List<FlagSample> flags = validationFlags(scenarioName, result);
        int maxExpectedFlags = injection.allowedFlagTicks().size();
        if (flags.size() < expectedMutations || flags.size() > maxExpectedFlags) {
            return scenarioName + " injected BedrockMovement flags expected " + expectedMutations + ".."
                    + maxExpectedFlags + " but was "
                    + flags.size() + " at ticks " + injection.allowedFlagTicks()
                    + ": " + flags;
        }
        for (FlagSample flag : flags) {
            if (!injection.allowsFlagTick(flag.tick())) {
                return scenarioName + " injected BedrockMovement flag expected tick " + injection.allowedFlagTicks()
                        + " but was " + flag.tick()
                        + ": " + flags;
            }
        }
        for (ReplayMutation mutation : injection.mutations()) {
            FlagSample selectedFlag = flagAt(flags, mutation.selectedTick());
            if (selectedFlag == null) {
                return scenarioName + " injected BedrockMovement flags did not include "
                        + mutation.kind() + " selected tick " + mutation.selectedTick()
                        + ": " + flags;
            }
            if (mutation.kind() == MutationKind.VERTICAL
                    && selectedFlag.offset() + IMPOSSIBLE_MOVEMENT_FLAG_OFFSET_TOLERANCE
                    < IMPOSSIBLE_MOVEMENT_AXIS_OFFSET) {
                return scenarioName + " injected " + mutation.kind()
                        + " BedrockMovement offset expected at least " + IMPOSSIBLE_MOVEMENT_AXIS_OFFSET
                        + " - " + IMPOSSIBLE_MOVEMENT_FLAG_OFFSET_TOLERANCE + " but was "
                        + selectedFlag.offset() + ": " + flags;
            }
        }
        return null;
    }

    private static List<FlagSample> validationFlags(String scenarioName, ReplayResult result) {
        if (!scenarioName.equals(KNOWN_FALSE_FLAG_SCENARIO)
                && !scenarioName.equals(KNOWN_FALSE_FLAG_SCENARIO + " baseline")) {
            return result.bedrockMovementFlags();
        }
        return result.bedrockMovementFlags().stream()
                .filter(flag -> flag.tick() != KNOWN_FALSE_FLAG_TICK)
                .toList();
    }

    private static FlagSample flagAt(List<FlagSample> flags, long tick) {
        for (FlagSample flag : flags) {
            if (flag.tick() == tick) {
                return flag;
            }
        }
        return null;
    }

    static CultPlayer offlinePlayer() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);
        state.setSetbacksEnabled(true);
        User user = new User(
                new User.Profile(PLAYER_UUID, ".Replay_Client"),
                null,
                null,
                null,
                new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user, MovementPlatform.BEDROCK, state);
        player.gamemode = GameMode.CREATIVE;
        player.canFly = false;
        player.isFlying = false;
        return player;
    }

    private static void seedPlayerAt(CultPlayer player, BedrockAuthInputFrame frame) {
        Vec3 position = frame.getPosition();
        player.x = position.x;
        player.y = position.y;
        player.z = position.z;
        player.lastX = position.x;
        player.lastY = position.y;
        player.lastZ = position.z;
        player.xRot = frame.getPitch();
        player.yRot = frame.getYaw();
        player.lastTickXRot = frame.getPitch();
        player.lastTickYRot = frame.getYaw();
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, position.x, position.y, position.z);
        boolean onGround = Collisions.collide(player, 0, -SimpleCollisionBox.COLLISION_EPSILON, 0).y == 0;
        player.onGround = onGround;
        player.lastOnGround = onGround;
    }

    private static void applyFixturePlayerState(CultPlayer player, JsonObject manifest) {
        JsonObject playerState = manifest.getAsJsonObject("playerState");
        if (playerState == null) {
            return;
        }
        if (playerState.has("gameMode")) {
            player.gamemode = GameMode.valueOf(playerState.get("gameMode").getAsString());
        }
        JsonObject replay = manifest.getAsJsonObject("replay");
        if (replay != null && booleanValue(replay, "startGliding", false)) {
            player.isGliding = true;
            player.checkManager.getSimulationProcessor().applyAcknowledgedBedrockGliding(true);
            player.refreshPlayerPose();
        }
        JsonObject armor = playerState.getAsJsonObject("armor");
        if (armor != null) {
            setArmor(player, Inventory.SLOT_HELMET, armor, "helmet");
            setArmor(player, Inventory.SLOT_CHESTPLATE, armor, "chestplate");
            setArmor(player, Inventory.SLOT_LEGGINGS, armor, "leggings");
            setArmor(player, Inventory.SLOT_BOOTS, armor, "boots");
        }
    }

    private static void setArmor(CultPlayer player, int slot, JsonObject armor, String key) {
        String materialName = stringValue(armor, key, "AIR");
        Material material = Material.valueOf(materialName);
        ItemStack stack = itemStack(material);
        player.getInventory().inventory.getInventoryStorage().setItem(slot, stack);
    }

    private static ItemStack itemStack(Material material) {
        if (material == Material.AIR) {
            return ItemStack.empty();
        }
        return new ReplayItemStack(material);
    }

    private static final class ReplayItemStack extends ItemStack {
        private final Material material;

        private ReplayItemStack(Material material) {
            this.material = material;
        }

        @Override
        public Material getType() {
            return material;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public Map<Enchantment, Integer> getEnchantments() {
            return Map.of();
        }

        @Override
        public int getEnchantmentLevel(Enchantment ench) {
            return 0;
        }
    }

    private static void seedInitialVelocity(CultPlayer player, Vec3 velocity) {
        if (velocity == null) {
            return;
        }
        player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);
    }

    private static Vec3 initialVelocity(JsonObject manifest, Path packetsPath, BedrockAuthInputFrame firstFrame) throws Exception {
        JsonObject replay = manifest.getAsJsonObject("replay");
        JsonObject velocity = replay == null ? null : replay.getAsJsonObject("initialVelocity");
        if (velocity != null) {
            return new Vec3(
                    doubleValue(velocity, "x", 0.0D),
                    doubleValue(velocity, "y", 0.0D),
                    doubleValue(velocity, "z", 0.0D));
        }
        if (!startsWithHorizontalVelocity(firstFrame)) {
            return firstPacketHorizontalDebugVelocity(packetsPath);
        }
        return new Vec3(
                firstFrame.getDelta().x,
                0.0D,
                firstFrame.getDelta().z);
    }

    private static Vec3 firstPacketHorizontalDebugVelocity(Path packetsPath) throws Exception {
        // The first captured auth packet has no previous captured position, so
        // from/to packet delta is undefined at the replay boundary. This uses
        // the fixture's client debug delta only to seed the offline starting
        // velocity; production prediction never consumes this field.
        try (var reader = Files.newBufferedReader(packetsPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject packet = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                if (!"PLAYER_AUTH_INPUT".equals(stringValue(packet, "packetType", ""))) {
                    continue;
                }
                JsonObject decoded = packet.getAsJsonObject("decoded");
                JsonObject delta = decoded == null ? null : decoded.getAsJsonObject("untrustedClientDelta");
                if (delta == null) {
                    return null;
                }
                Vec3 debugDelta = new Vec3(
                        doubleValue(delta, "x", 0.0D),
                        0.0D,
                        doubleValue(delta, "z", 0.0D));
                return startsWithHorizontalVelocity(debugDelta) ? debugDelta : null;
            }
        }
        return null;
    }

    private static boolean startsWithHorizontalVelocity(BedrockAuthInputFrame firstFrame) {
        return startsWithHorizontalVelocity(firstFrame.getDelta());
    }

    private static boolean startsWithHorizontalVelocity(Vec3 delta) {
        return Math.abs(delta.x) > 1.0E-6D || Math.abs(delta.z) > 1.0E-6D;
    }

    private static List<Double> expectedOffsets(JsonObject expected) {
        if (expected.has("offsets")) {
            JsonArray offsets = expected.getAsJsonArray("offsets");
            List<Double> values = new ArrayList<>(offsets.size());
            for (JsonElement offset : offsets) {
                values.add(offset.getAsDouble());
            }
            return values;
        }
        int count = intValue(expected, "count", 0);
        if (count == 0) {
            return List.of();
        }
        return List.of(doubleValue(expected, "offset", 0.0D));
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
    }

    private static double doubleValue(JsonObject object, String key, double fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsDouble() : fallback;
    }

    private static List<BedrockAuthInputFrame> testFrames(
            int frameCount,
            int selectedIndex,
            long firstTick,
            double selectedDx,
            double selectedDz
    ) {
        return testFrames(frameCount, selectedIndex, firstTick, selectedDx, selectedDz, 0.25F, -0.5F, 45.0F);
    }

    private static List<BedrockAuthInputFrame> testFrames(
            int frameCount,
            int selectedIndex,
            long firstTick,
            double selectedDx,
            double selectedDz,
            float moveX,
            float moveZ,
            float yaw
    ) {
        List<BedrockAuthInputFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            double x = i * 0.25D;
            double z = i * 0.125D;
            if (i == selectedIndex - 1) {
                x = 10.0D;
                z = 20.0D;
            } else if (i >= selectedIndex) {
                x = 10.0D + selectedDx + (i - selectedIndex) * 0.5D;
                z = 20.0D + selectedDz + (i - selectedIndex) * 0.25D;
            }
            frames.add(testFrame(firstTick + i, x, 64.0D, z, moveX, moveZ, yaw));
        }
        return List.copyOf(frames);
    }

    private static BedrockAuthInputFrame testFrame(long clientTick, double x, double y, double z) {
        return testFrame(clientTick, x, y, z, 0.25F, -0.5F, 45.0F);
    }

    private static BedrockAuthInputFrame testFrame(
            long clientTick,
            double x,
            double y,
            double z,
            float moveX,
            float moveZ,
            float yaw
    ) {
        long inputFlags = inputFlagMask(PlayerAuthInputData.JUMPING)
                | inputFlagMask(PlayerAuthInputData.SPRINTING)
                | inputFlagMask(PlayerAuthInputData.START_GLIDING);
        return BedrockAuthInputFrame.builder(PLAYER_UUID)
                .protocolVersion(0)
                .clientTick(clientTick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .position(new Vec3(x, y, z))
                .packetPosition(new Vec3(x, y + 1.62D, z))
                .delta(new Vec3(0.125D, -0.03125D, 0.0625D))
                .rotation(yaw, 10.0F, 50.0F)
                .moveVector(moveX, moveZ)
                .rawInputFlags(inputFlags)
                .jumping(true)
                .jumpStarted(true)
                .jumpPressedRaw(true)
                .jumpCurrentRaw(true)
                .wantUp(true)
                .sneaking(true)
                .startSneaking(true)
                .stopSneaking(true)
                .sprinting(true)
                .startSwimming(true)
                .stopSwimming(true)
                .startCrawling(true)
                .stopCrawling(true)
                .startGliding(true)
                .stopGliding(true)
                .usingItem(true)
                .blockAction(true)
                .authorityMode("client-auth-input")
                .rewindCorrectionId(clientTick)
                .build();
    }

    private static BedrockAuthInputFrame withMoveVector(BedrockAuthInputFrame frame, float x, float z) {
        return copyFrameBuilder(frame)
                .moveVector(x, z)
                .build();
    }

    private static void assertShifted(Vec3 original, Vec3 mutated, Vec3 shift) {
        assertEquals(original.x + shift.x, mutated.x, 1.0E-12D);
        assertEquals(original.y + shift.y, mutated.y, 1.0E-12D);
        assertEquals(original.z + shift.z, mutated.z, 1.0E-12D);
    }

    private static void assertFrameMetadataUnchanged(BedrockAuthInputFrame original, BedrockAuthInputFrame mutated) {
        assertEquals(original.getDelta(), mutated.getDelta());
        assertEquals(original.getRawInputFlags(), mutated.getRawInputFlags());
        assertEquals(original.getMoveVector(), mutated.getMoveVector());
        assertEquals(original.getYaw(), mutated.getYaw(), 0.0D);
        assertEquals(original.getPitch(), mutated.getPitch(), 0.0D);
        assertEquals(original.getHeadYaw(), mutated.getHeadYaw(), 0.0D);
        assertEquals(original.isJumping(), mutated.isJumping());
        assertEquals(original.isJumpStarted(), mutated.isJumpStarted());
        assertEquals(original.isJumpPressedRaw(), mutated.isJumpPressedRaw());
        assertEquals(original.isJumpCurrentRaw(), mutated.isJumpCurrentRaw());
        assertEquals(original.isWantUp(), mutated.isWantUp());
        assertEquals(original.isSneaking(), mutated.isSneaking());
        assertEquals(original.isStartSneaking(), mutated.isStartSneaking());
        assertEquals(original.isStopSneaking(), mutated.isStopSneaking());
        assertEquals(original.isSprinting(), mutated.isSprinting());
        assertEquals(original.isStartSwimming(), mutated.isStartSwimming());
        assertEquals(original.isStopSwimming(), mutated.isStopSwimming());
        assertEquals(original.isStartCrawling(), mutated.isStartCrawling());
        assertEquals(original.isStopCrawling(), mutated.isStopCrawling());
        assertEquals(original.isStartGliding(), mutated.isStartGliding());
        assertEquals(original.isStopGliding(), mutated.isStopGliding());
        assertEquals(original.isUsingItem(), mutated.isUsingItem());
        assertEquals(original.hasBlockAction(), mutated.hasBlockAction());
        assertEquals(original.getAuthorityMode(), mutated.getAuthorityMode());
        assertEquals(original.getRewindCorrectionId(), mutated.getRewindCorrectionId());
    }

    private record ReplayVariant(boolean injectImpossibleMovement, String replayName) {
        private static ReplayVariant baseline() {
            return new ReplayVariant(false, "");
        }

        private static ReplayVariant injected(String replayName) {
            return new ReplayVariant(true, replayName);
        }
    }

    private record ReplayFrames(List<BedrockAuthInputFrame> frames, ReplayInjectionPlan injection) {
    }

    private record ReplayInjectionPlan(List<ReplayMutation> mutations) {
        private boolean allowsFlagTick(long tick) {
            for (ReplayMutation mutation : mutations) {
                if (mutation.allowsFlagTick(tick)) {
                    return true;
                }
            }
            return false;
        }

        private List<Long> allowedFlagTicks() {
            List<Long> ticks = new ArrayList<>(mutations.size() * ((int) IMPOSSIBLE_MOVEMENT_ALLOWED_FALLOUT_TICKS + 1));
            for (ReplayMutation mutation : mutations) {
                for (long tick = mutation.selectedTick();
                     tick <= mutation.followingTick() + IMPOSSIBLE_MOVEMENT_ALLOWED_FALLOUT_TICKS;
                     tick++) {
                    addUnique(ticks, tick);
                }
            }
            return List.copyOf(ticks);
        }

        private ReplayMutation mutation(MutationKind kind) {
            for (ReplayMutation mutation : mutations) {
                if (mutation.kind() == kind) {
                    return mutation;
                }
            }
            throw new IllegalArgumentException("Missing replay mutation " + kind);
        }

        private static void addUnique(List<Long> ticks, long tick) {
            if (!ticks.contains(tick)) {
                ticks.add(tick);
            }
        }
    }

    private record ReplayMutation(
            MutationKind kind,
            int selectedIndex,
            long selectedTick,
            long followingTick,
            Vec3 extra
    ) {
        private boolean allowsFlagTick(long tick) {
            return tick >= selectedTick && tick <= followingTick + IMPOSSIBLE_MOVEMENT_ALLOWED_FALLOUT_TICKS;
        }
    }

    private enum MutationKind {
        HORIZONTAL,
        VERTICAL
    }

    private record ReplayResult(List<FlagSample> bedrockMovementFlags, ReplayInjectionPlan injection) {
    }

    private record FlagSample(
            long tick,
            double offset,
            Vec3d actualDelta,
            Vec3d predictedDelta,
            Vec3d rawPredictedDelta,
            Vec3d predictedVelocity,
            Vec3d positionDelta,
            Vec3d rawPositionDelta,
            Vec3d requiredInput,
            Vec3d observedInput,
            double inputLimit,
            double inputMismatch,
            Vec3d previousPosition,
            Vec3d previousVelocity,
            Vec3d collisionInputVelocity,
            String previousFlags,
            String predictedFlags,
            boolean previousWaterTravel,
            String previousBranch,
            boolean startWater,
            boolean postWater,
            boolean postBubbleUp,
            boolean postBubbleDown,
            boolean steppedUp,
            boolean stepRetryAllowed,
            String postMoveFluid
    ) {
        private static FlagSample from(long tick, double offset, PredictionResult result) {
            BedrockPredictionResult bedrockResult = result.getProfileResult(BedrockPredictionResult.class);
            if (bedrockResult == null || bedrockResult.observation() == null) {
                return new FlagSample(tick, offset, null, null, null, null, null, null, null, null, 0.0D, 0.0D, null, null, null, "", "", false, "", false, false, false, false, false, false, "");
            }
            BedrockMovementResult movementResult = bedrockResult.movementResult();
            return new FlagSample(
                    tick,
                    offset,
                    bedrockResult.observation().actualDelta(),
                    bedrockResult.observation().predictedDelta(),
                    bedrockResult.observation().rawPredictedDelta(),
                    movementResult == null ? null : movementResult.predictedVelocity(),
                    bedrockResult.observation().positionDelta(),
                    bedrockResult.observation().rawPositionDelta(),
                    bedrockResult.observation().requiredHorizontalInput(),
                    bedrockResult.observation().observedHorizontalInput(),
                    bedrockResult.observation().horizontalInputLimit(),
                    bedrockResult.observation().horizontalInputExcess(),
                    movementResult == null ? null : movementResult.previousState().physicalFeetPosition(),
                    movementResult == null ? null : movementResult.previousState().velocity(),
                    movementResult == null ? null : movementResult.collisionInputVelocity(),
                    movementResult == null ? "" : movementResult.previousState().collisionFlags().toString(),
                    movementResult == null ? "" : movementResult.predictedState().collisionFlags().toString(),
                    movementResult != null && movementResult.previousState().waterTravelFlag(),
                    movementResult == null ? "" : movementResult.previousState().movementBranch().toString(),
                    movementResult != null && movementResult.movementContext().inWater(),
                    movementResult != null && movementResult.postMoveContext().inWater(),
                    movementResult != null && movementResult.postMoveContext().inUpwardBubbleColumn(),
                    movementResult != null && movementResult.postMoveContext().inDownwardBubbleColumn(),
                    movementResult != null && movementResult.steppedUp(),
                    movementResult != null && movementResult.stepRetryAllowed(),
                    movementResult == null ? "" : movementResult.postMoveContext().worldState().fluidState().toString());
        }
    }

    private static void bootstrapSetbackAnchor(CultPlayer player, SetbackPosWithVector safe) {
        int bootstrapTransaction = player.lastTransactionSent.get();
        player.getSetbackTeleportUtil().addSentTeleport(
                safe.getPos(), bootstrapTransaction, new RelativeFlag(0), true, -2);
        player.lastTransactionReceived.set(bootstrapTransaction);
        assertTrue(player.getSetbackTeleportUtil().checkTeleportQueue(
                safe.getPos().x, safe.getPos().y, safe.getPos().z).isTeleport());
        player.getSetbackTeleportUtil().lastKnownGoodPosition = safe;
    }

    private static void buildFlatGroundWithTwoBlockPillar(
            CultPlayer player,
            Vec3 start,
            int groundY,
            int pillarX,
            int pillarZ
    ) {
        int startX = (int) Math.floor(start.x);
        int startZ = (int) Math.floor(start.z);
        for (int x = startX - 2; x <= startX + 3; x++) {
            for (int z = startZ - 2; z <= startZ + 2; z++) {
                player.compensatedWorld.applyBlockChangeRawDANGER(x, groundY - 1, z, Blocks.STONE.defaultBlockState());
                for (int y = groundY; y <= groundY + 4; y++) {
                    player.compensatedWorld.applyBlockChangeRawDANGER(x, y, z, Blocks.AIR.defaultBlockState());
                }
            }
        }
        player.compensatedWorld.applyBlockChangeRawDANGER(
                pillarX, groundY, pillarZ, Blocks.STONE.defaultBlockState());
        player.compensatedWorld.applyBlockChangeRawDANGER(
                pillarX, groundY + 1, pillarZ, Blocks.STONE.defaultBlockState());
    }

    @Test
    public void adversarialPillarAscentBattery_AllImpossibleAttemptsFlagged() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        OfflineCultTestBootstrap.setDoubleConfigOverride("Simulation.immediate-setback-threshold", 0.01D);

        List<PillarAscentOutcome> outcomes = new ArrayList<>();
        try {
            double groundY = 82.0D;
            int pillarX = 3;
            int pillarZ = 0;
            Vec3 pillarTop = new Vec3(pillarX + 0.5D, groundY + 2.00001D, pillarZ + 0.5D);
            Vec3 groundPosition = new Vec3(0.5D, groundY, 0.5D);
            long baseTick = 100L;

            // Technique 1: Direct impossible single-frame jump to pillar top
            outcomes.add(attemptPillarAscent(
                    "direct-impossible-jump",
                    () -> {
                        CultPlayer p = offlinePlayer();
                        buildWorldAndSeed(p, pillarX, pillarZ, groundY, groundPosition);
                        bootstrapSetbackAnchor(p, p.getSetbackTeleportUtil().lastKnownGoodPosition);
                        BedrockAuthInputFrame frame = syntheticPillarFrame(baseTick, pillarTop, false, true, false);
                        p.bedrockState.offerAuthInputFrame(frame);
                        return captureAscentResult(p, frame, pillarTop, groundY);
                    },
                    pillarTop, groundY));

            // Technique 2: Two-frame split ascent
            outcomes.add(attemptPillarAscent(
                    "two-frame-split",
                    () -> {
                        CultPlayer p = offlinePlayer();
                        buildWorldAndSeed(p, pillarX, pillarZ, groundY, groundPosition);
                        bootstrapSetbackAnchor(p, p.getSetbackTeleportUtil().lastKnownGoodPosition);
                        Vec3 pillarBottomBlock = new Vec3(pillarX + 0.5D, groundY + 1.00001D, pillarZ + 0.5D);
                        BedrockAuthInputFrame frame1 = syntheticPillarFrame(baseTick, pillarBottomBlock, false, true, false);
                        p.bedrockState.offerAuthInputFrame(frame1);
                        captureAscentResult(p, frame1, pillarTop, groundY);
                        BedrockAuthInputFrame frame2 = syntheticPillarFrame(baseTick + 1, pillarTop, false, true, false);
                        p.bedrockState.offerAuthInputFrame(frame2);
                        return captureAscentResult(p, frame2, pillarTop, groundY);
                    },
                    pillarTop, groundY));

            // Technique 3: Gradual micro-step ascent (4 frames)
            outcomes.add(attemptPillarAscent(
                    "gradual-micro-step",
                    () -> {
                        CultPlayer p = offlinePlayer();
                        buildWorldAndSeed(p, pillarX, pillarZ, groundY, groundPosition);
                        bootstrapSetbackAnchor(p, p.getSetbackTeleportUtil().lastKnownGoodPosition);
                        PillarAscentOutcome last = null;
                        for (int step = 1; step <= 4; step++) {
                            double fraction = step / 4.0D;
                            Vec3 mid = new Vec3(
                                    0.5D + fraction * (pillarTop.x - 0.5D),
                                    groundY + fraction * 2.0D,
                                    0.5D + fraction * (pillarTop.z - 0.5D));
                            BedrockAuthInputFrame frame = syntheticPillarFrame(baseTick + step - 1, mid, false, step == 1, false);
                            p.bedrockState.offerAuthInputFrame(frame);
                            last = captureAscentResult(p, frame, pillarTop, groundY);
                        }
                        return last;
                    },
                    pillarTop, groundY));

            // Technique 4: Sprint approach then jump
            outcomes.add(attemptPillarAscent(
                    "sprint-approach-jump",
                    () -> {
                        CultPlayer p = offlinePlayer();
                        buildWorldAndSeed(p, pillarX, pillarZ, groundY, new Vec3(1.5D, groundY, 0.5D));
                        bootstrapSetbackAnchor(p, p.getSetbackTeleportUtil().lastKnownGoodPosition);
                        BedrockAuthInputFrame approach = syntheticPillarFrame(baseTick, new Vec3(2.5D, groundY, 0.5D), false, false, true);
                        p.bedrockState.offerAuthInputFrame(approach);
                        captureAscentResult(p, approach, pillarTop, groundY);
                        BedrockAuthInputFrame launch = syntheticPillarFrame(baseTick + 1, pillarTop, false, true, true);
                        p.bedrockState.offerAuthInputFrame(launch);
                        return captureAscentResult(p, launch, pillarTop, groundY);
                    },
                    pillarTop, groundY));

            // Technique 5: Diagonal slip into pillar then top
            outcomes.add(attemptPillarAscent(
                    "diagonal-slip-through-pillar",
                    () -> {
                        CultPlayer p = offlinePlayer();
                        buildWorldAndSeed(p, pillarX, pillarZ, groundY, groundPosition);
                        bootstrapSetbackAnchor(p, p.getSetbackTeleportUtil().lastKnownGoodPosition);
                        Vec3 insidePillarBottom = new Vec3(pillarX + 0.5D, groundY, pillarZ + 0.5D);
                        BedrockAuthInputFrame slipIn = syntheticPillarFrame(baseTick, insidePillarBottom, false, false, false);
                        p.bedrockState.offerAuthInputFrame(slipIn);
                        captureAscentResult(p, slipIn, pillarTop, groundY);
                        BedrockAuthInputFrame top = syntheticPillarFrame(baseTick + 1, pillarTop, false, true, false);
                        p.bedrockState.offerAuthInputFrame(top);
                        return captureAscentResult(p, top, pillarTop, groundY);
                    },
                    pillarTop, groundY));

            // Technique 6: Jump with projectedOnGround=true at top
            outcomes.add(attemptPillarAscent(
                    "projected-on-ground-at-top",
                    () -> {
                        CultPlayer p = offlinePlayer();
                        buildWorldAndSeed(p, pillarX, pillarZ, groundY, groundPosition);
                        bootstrapSetbackAnchor(p, p.getSetbackTeleportUtil().lastKnownGoodPosition);
                        BedrockAuthInputFrame frame = syntheticPillarFrameBuilder(baseTick, pillarTop, false, true, false)
                                .projectedOnGround(true)
                                .build();
                        p.bedrockState.offerAuthInputFrame(frame);
                        return captureAscentResult(p, frame, pillarTop, groundY);
                    },
                    pillarTop, groundY));

            // Technique 7: Sneak + step up attempt
            outcomes.add(attemptPillarAscent(
                    "sneak-step-up",
                    () -> {
                        CultPlayer p = offlinePlayer();
                        buildWorldAndSeed(p, pillarX, pillarZ, groundY, groundPosition);
                        bootstrapSetbackAnchor(p, p.getSetbackTeleportUtil().lastKnownGoodPosition);
                        Vec3 adjacentToPillar = new Vec3(pillarX - 1.0D + 0.5D, groundY, pillarZ + 0.5D);
                        BedrockAuthInputFrame sneak = syntheticPillarFrameBuilder(baseTick, adjacentToPillar, true, false, false).build();
                        p.bedrockState.offerAuthInputFrame(sneak);
                        captureAscentResult(p, sneak, pillarTop, groundY);
                        BedrockAuthInputFrame stepUp = syntheticPillarFrameBuilder(baseTick + 1, pillarTop, true, true, false).build();
                        p.bedrockState.offerAuthInputFrame(stepUp);
                        return captureAscentResult(p, stepUp, pillarTop, groundY);
                    },
                    pillarTop, groundY));

            // Technique 8: Swim/crawl through pillar
            outcomes.add(attemptPillarAscent(
                    "swim-crawl-through",
                    () -> {
                        CultPlayer p = offlinePlayer();
                        buildWorldAndSeed(p, pillarX, pillarZ, groundY, groundPosition);
                        bootstrapSetbackAnchor(p, p.getSetbackTeleportUtil().lastKnownGoodPosition);
                        BedrockAuthInputFrame swim = syntheticPillarFrameBuilder(baseTick, pillarTop, false, true, false)
                                .startSwimming(true)
                                .swimming(true)
                                .build();
                        p.bedrockState.offerAuthInputFrame(swim);
                        return captureAscentResult(p, swim, pillarTop, groundY);
                    },
                    pillarTop, groundY));

            // Technique 9: High-delta velocity claim
            outcomes.add(attemptPillarAscent(
                    "high-delta-velocity",
                    () -> {
                        CultPlayer p = offlinePlayer();
                        buildWorldAndSeed(p, pillarX, pillarZ, groundY, groundPosition);
                        bootstrapSetbackAnchor(p, p.getSetbackTeleportUtil().lastKnownGoodPosition);
                        Vec3 deltaToTop = new Vec3(pillarTop.x - groundPosition.x, 2.0D, 0.0D);
                        BedrockAuthInputFrame frame = BedrockAuthInputFrame.builder(PLAYER_UUID)
                                .protocolVersion(0).clientTick(baseTick)
                                .inputMode(1).playMode(2).deviceId(3)
                                .position(pillarTop)
                                .packetPosition(new Vec3(pillarTop.x, pillarTop.y + 1.62D, pillarTop.z))
                                .delta(deltaToTop).rotation(45.0F, 10.0F, 50.0F)
                                .moveVector(0.0F, 0.0F)
                                .jumping(true).jumpStarted(true).jumpCurrentRaw(true).jumpPressedRaw(true).wantUp(true)
                                .authorityMode("client-auth-input")
                                .build();
                        p.bedrockState.offerAuthInputFrame(frame);
                        return captureAscentResult(p, frame, pillarTop, groundY);
                    },
                    pillarTop, groundY));

            // Technique 10: Glide-in from above
            outcomes.add(attemptPillarAscent(
                    "glide-from-above",
                    () -> {
                        CultPlayer p = offlinePlayer();
                        buildWorldAndSeed(p, pillarX, pillarZ, groundY, groundPosition);
                        bootstrapSetbackAnchor(p, p.getSetbackTeleportUtil().lastKnownGoodPosition);
                        Vec3 abovePillar = new Vec3(pillarX + 0.5D, groundY + 3.0D, pillarZ + 0.5D);
                        BedrockAuthInputFrame glideDescend = syntheticPillarFrameBuilder(baseTick, abovePillar, false, false, false)
                                .startGliding(true)
                                .build();
                        p.bedrockState.offerAuthInputFrame(glideDescend);
                        return captureAscentResult(p, glideDescend, pillarTop, groundY);
                    },
                    pillarTop, groundY));

        } finally {
            OfflineCultTestBootstrap.clearDoubleConfigOverride("Simulation.immediate-setback-threshold");
        }

        // Analyze results
        StringBuilder summary = new StringBuilder();
        List<PillarAscentOutcome> bypasses = new ArrayList<>();
        List<PillarAscentOutcome> correctlyBlocked = new ArrayList<>();
        List<PillarAscentOutcome> unexpected = new ArrayList<>();
        for (PillarAscentOutcome o : outcomes) {
            if (o.isBypass()) {
                bypasses.add(o);
            } else if (o.reachedPillarTop() && o.flagged()) {
                correctlyBlocked.add(o);
            } else {
                unexpected.add(o);
            }
        }

        summary.append("Adversarial pillar-ascent battery results:\n");
        summary.append("- ").append(correctlyBlocked.size()).append("/").append(outcomes.size())
                .append(" techniques detected and setback\n");
        summary.append("- ").append(bypasses.size()).append("/").append(outcomes.size())
                .append(" techniques bypassing detection\n");
        summary.append("- ").append(unexpected.size()).append("/").append(outcomes.size())
                .append(" techniques with unexpected results\n");

        if (!bypasses.isEmpty()) {
            summary.append("!!! POTENTIAL BYPASSES FOUND !!!\n");
            for (PillarAscentOutcome b : bypasses) {
                summary.append("- ").append(b.techniqueName()).append("\n");
            }
            fail(summary.toString());
        }

        if (!unexpected.isEmpty()) {
            for (PillarAscentOutcome u : unexpected) {
                summary.append("unexpected: ").append(u.techniqueName())
                        .append(" pos=").append(u.postPredictionPosition())
                        .append(" severity=").append(u.severity()).append("\n");
            }
        }
        System.err.println(summary);
    }

    /** Describes the outcome of a single adversarial ascent attempt. */
    private record PillarAscentOutcome(
            String techniqueName,
            Vec3 attemptedPillarTop,
            Vec3 postPredictionPosition,
            Double severity,
            boolean flagged,
            boolean setbackPending,
            boolean reachedPillarTop,
            String detail
    ) {
        boolean isBypass() {
            return reachedPillarTop && !flagged && !setbackPending;
        }
    }

    /** Runs a single adversarial ascent attempt and records the outcome. */
    private static PillarAscentOutcome attemptPillarAscent(
            String name,
            AscentSupplier supplier,
            Vec3 pillarTop,
            double groundY
    ) {
        CultPlayer player = null;
        try {
            PillarAscentOutcome result = supplier.run();
            // Ensure the technique name is set on the result
            if (result.techniqueName() == null) {
                return new PillarAscentOutcome(
                        name, result.attemptedPillarTop(),
                        result.postPredictionPosition(), result.severity(),
                        result.flagged(), result.setbackPending(),
                        result.reachedPillarTop(), result.detail());
            }
            return result;
        } catch (Exception e) {
            return new PillarAscentOutcome(
                    name, pillarTop,
                    player == null ? Vec3.ZERO : new Vec3(player.x, player.y, player.z),
                    null, false, false, false,
                    "error: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface AscentSupplier {
        PillarAscentOutcome run() throws Exception;
    }

    /** Captures the result of processing a single frame. */
    private static PillarAscentOutcome captureAscentResult(
            CultPlayer p,
            BedrockAuthInputFrame frame,
            Vec3 pillarTop,
            double groundY
    ) {
        PredictionResult result = p.checkManager.getSimulationProcessor()
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.AUTH_INPUT_PLUGIN_MESSAGE);

        Vec3 pos = new Vec3(p.x, p.y, p.z);
        PredictionResult.Flag movementFlag = result == null ? null : result.getFlag(BedrockMovement.class);
        double severity = movementFlag == null ? 0.0D : movementFlag.getSeverity();
        boolean flagged = movementFlag != null && severity > 0.001D;
        boolean setbackPending = p.getSetbackTeleportUtil().isPendingSetback();
        double horizDist = Math.sqrt(Math.pow(pos.x - pillarTop.x, 2) + Math.pow(pos.z - pillarTop.z, 2));
        boolean reachedTop = horizDist < 0.3D && pos.y >= groundY + 1.9D;
        String detail = result == null ? "null" :
                (movementFlag == null ? "noFlag" : "severity=" + severity);

        return new PillarAscentOutcome(
                null, pillarTop, pos, severity, flagged, setbackPending, reachedTop, detail);
    }

    /** Builds the flat-ground + pillar world and seeds the player. */
    private static void buildWorldAndSeed(
            CultPlayer player,
            int pillarX,
            int pillarZ,
            double groundY,
            Vec3 groundPosition
    ) {
        player.gamemode = GameMode.SURVIVAL;
        player.canFly = false;
        player.isFlying = false;
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.getSetbackTeleportUtil().hasFullyJoined = true;
        buildFlatGroundWithTwoBlockPillar(player, groundPosition, (int) groundY, pillarX, pillarZ);
        seedPlayerAt(player, syntheticPillarFrame(0, groundPosition, false, false, false));
    }

    /** Creates a synthetic BedrockAuthInputFrame at the given position. */
    private static BedrockAuthInputFrame syntheticPillarFrame(
            long tick,
            Vec3 position,
            boolean sneaking,
            boolean jumping,
            boolean sprinting
    ) {
        return syntheticPillarFrameBuilder(tick, position, sneaking, jumping, sprinting).build();
    }

    private static BedrockAuthInputFrame.Builder syntheticPillarFrameBuilder(
            long tick,
            Vec3 position,
            boolean sneaking,
            boolean jumping,
            boolean sprinting
    ) {
        long flags = 0;
        if (jumping) {
            flags |= 1L << PlayerAuthInputData.JUMPING.ordinal();
            flags |= 1L << PlayerAuthInputData.START_JUMPING.ordinal();
            flags |= 1L << PlayerAuthInputData.JUMP_CURRENT_RAW.ordinal();
            flags |= 1L << PlayerAuthInputData.JUMP_PRESSED_RAW.ordinal();
            flags |= 1L << PlayerAuthInputData.WANT_UP.ordinal();
        }
        if (sneaking) {
            flags |= 1L << PlayerAuthInputData.SNEAKING.ordinal();
            flags |= 1L << PlayerAuthInputData.START_SNEAKING.ordinal();
            flags |= 1L << PlayerAuthInputData.SNEAK_CURRENT_RAW.ordinal();
        }
        if (sprinting) {
            flags |= 1L << PlayerAuthInputData.SPRINTING.ordinal();
        }
        return BedrockAuthInputFrame.builder(PLAYER_UUID)
                .protocolVersion(0).clientTick(tick)
                .inputMode(1).playMode(2).deviceId(3)
                .position(position)
                .packetPosition(new Vec3(position.x, position.y + 1.62D, position.z))
                .delta(Vec3.ZERO)
                .rotation(45.0F, 10.0F, 50.0F)
                .moveVector(0.0F, 0.0F)
                .rawInputFlags(flags)
                .jumping(jumping).jumpStarted(jumping).jumpPressedRaw(jumping).jumpCurrentRaw(jumping).wantUp(jumping)
                .sneaking(sneaking).startSneaking(sneaking)
                .sprinting(sprinting)
                .authorityMode("client-auth-input");
    }

        @Test
    public void duplicateTickBypassesBedrockMovementCheck() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        OfflineCultTestBootstrap.setDoubleConfigOverride("Simulation.immediate-setback-threshold", 0.01D);

        CultPlayer player = null;
        try {
            double groundY = 82.0D;
            int pillarX = 3;
            int pillarZ = 0;
            Vec3 pillarTop = new Vec3(pillarX + 0.5D, groundY + 2.0D, pillarZ + 0.5D);
            Vec3 groundPos = new Vec3(0.5D, groundY, 0.5D);

            player = offlinePlayer();
            player.gamemode = GameMode.SURVIVAL;
            player.canFly = false;
            player.isFlying = false;
            player.getSetbackTeleportUtil().hasFullyLoaded = true;
            player.getSetbackTeleportUtil().hasFullyJoined = true;
            buildFlatGroundWithTwoBlockPillar(player, groundPos, (int) groundY, pillarX, pillarZ);

            // Seed player at ground
            player.x = groundPos.x; player.y = groundPos.y; player.z = groundPos.z;
            player.lastX = groundPos.x; player.lastY = groundPos.y; player.lastZ = groundPos.z;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, groundPos.x, groundPos.y, groundPos.z);
            boolean onGround = Collisions.collide(player, 0, -SimpleCollisionBox.COLLISION_EPSILON, 0).y == 0;
            player.onGround = onGround;
            player.lastOnGround = onGround;

            // Frame 1: Accept legitimate frame at tick=100
            BedrockAuthInputFrame acceptedFrame = syntheticPillarFrame(100, groundPos, false, false, false);
            player.bedrockState.offerAuthInputFrame(acceptedFrame);
            PredictionResult accepted = player.checkManager.getSimulationProcessor()
                    .processBedrockAuthInputFrame(acceptedFrame, BedrockPredictionTrigger.AUTH_INPUT_PLUGIN_MESSAGE);
            assertNotNull(accepted);
            // The first frame may or may not flag, depending on ground state.
            // Accept it even if flagged for the exploit test.
            System.err.println("Frame 1 (legitimate): flagged=" + accepted.hasFlag(BedrockMovement.class)
                    + " severity=" + (accepted.hasFlag(BedrockMovement.class) ? accepted.getFlag(BedrockMovement.class).getSeverity() : 0)
                    + " setback=" + player.getSetbackTeleportUtil().isPendingSetback()
                    + " pos=" + new Vec3(player.x, player.y, player.z));

            SetbackPosWithVector safe = player.getSetbackTeleportUtil().lastKnownGoodPosition;
            assertNotNull(safe);
            bootstrapSetbackAnchor(player, safe);

            // FRAME 2: SAME tick (100) but IMPOSSIBLE position at pillar top!
            // The BedrockMovement check skips because predictionTick(100) == lastProcessedPredictionTick(100)
            Vec3 preExploitPos = new Vec3(player.x, player.y, player.z);
            boolean preExploitSetback = player.getSetbackTeleportUtil().isPendingSetback();
            System.err.println("Before exploit frame: pos=" + preExploitPos + " setback=" + preExploitSetback);

            BedrockAuthInputFrame exploitFrame = syntheticPillarFrame(100, pillarTop, false, true, true);
            player.bedrockState.offerAuthInputFrame(exploitFrame);
            PredictionResult exploitResult = player.checkManager.getSimulationProcessor()
                    .processBedrockAuthInputFrame(exploitFrame, BedrockPredictionTrigger.AUTH_INPUT_PLUGIN_MESSAGE);
            assertNotNull(exploitResult);

            Vec3 postExploitPos = new Vec3(player.x, player.y, player.z);
            boolean postExploitSetback = player.getSetbackTeleportUtil().isPendingSetback();
            boolean exploitFlagged = exploitResult.hasFlag(BedrockMovement.class);
            double exploitSeverity = exploitFlagged ? exploitResult.getFlag(BedrockMovement.class).getSeverity() : 0.0;

            System.err.println("Exploit frame: flagged=" + exploitFlagged + " severity=" + exploitSeverity);
            System.err.println("After exploit: pos=" + postExploitPos + " setback=" + postExploitSetback);

            // The exploit: the engine flags the movement, but the BedrockMovement check
            // SKIPS because the tick is a duplicate of the first frame.
            // Result: player position IS updated to pillar top, but NO setback is triggered.

            double horizDist = Math.sqrt(Math.pow(postExploitPos.x - pillarTop.x, 2) + Math.pow(postExploitPos.z - pillarTop.z, 2));
            boolean atTop = horizDist < 0.3D && Math.abs(postExploitPos.y - pillarTop.y) < 0.3D;

            if (atTop && !postExploitSetback) {
                System.err.println("=== EXPLOIT SUCCESSFUL ===");
                System.err.println("Duplicate tick bypass allowed player to reach pillar top without setback!");
                System.err.println("The BedrockMovement check skipped because lastProcessedPredictionTick matched.");
                fail("FLAW FOUND: Duplicate tick at " + acceptedFrame.getClientTick()
                        + " bypasses BedrockMovement check. Player reached pillar top "
                        + postExploitPos + " without setback.");
            } else {
                System.err.println("Exploit partially effective: atTop=" + atTop
                        + " setback=" + postExploitSetback + " pos=" + postExploitPos);
            }
        } finally {
            if (player != null) {
                closeOfflinePlayer(player);
            }
            OfflineCultTestBootstrap.clearDoubleConfigOverride("Simulation.immediate-setback-threshold");
        }
    }









    @Test
    public void initialBedrockPositionBoundaryAnchorsFirstMovementSetback() throws Exception {
        OfflineCultTestBootstrap.installConfig();

        CultPlayer player = null;
        try {
            double groundY = 82.0D;
            int pillarX = 3;
            int pillarZ = 0;
            Vec3 pillarTop = new Vec3(pillarX + 0.5D, groundY + 2.00001D, pillarZ + 0.5D);
            Vec3 groundPos = new Vec3(0.5D, groundY, 0.5D);

            player = offlinePlayer();
            player.gamemode = GameMode.SURVIVAL;
            player.canFly = false;
            player.isFlying = false;
            buildFlatGroundWithTwoBlockPillar(player, groundPos, (int) groundY, pillarX, pillarZ);

            player.x = groundPos.x; player.y = groundPos.y; player.z = groundPos.z;
            player.lastX = groundPos.x; player.lastY = groundPos.y; player.lastZ = groundPos.z;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, groundPos.x, groundPos.y, groundPos.z);
            boolean onGround = Collisions.collide(player, 0, -SimpleCollisionBox.COLLISION_EPSILON, 0).y == 0;
            player.onGround = onGround;
            player.lastOnGround = onGround;

            // Login position is authoritative because it originates from the
            // Java position packet. The following Geyser packet binds the exact
            // Bedrock wire target to that existing rollback owner.
            player.getSetbackTeleportUtil().addSentTeleport(
                    groundPos,
                    player.lastTransactionSent.get(),
                    new RelativeFlag(0),
                    true,
                    1);
            long revision = player.getSetbackTeleportUtil()
                    .addImmediateBedrockTransportTeleport(groundPos, onGround);
            assertNotNull(player.getSetbackTeleportUtil().getRequiredSetBack());
            assertEquals(groundPos, player.getSetbackTeleportUtil()
                    .getRequiredSetBack().getTeleportData().getLocation());
            assertEquals(groundPos, player.getSetbackTeleportUtil().lastKnownGoodPosition.getPos());

            TeleportAcceptData accepted = player.getSetbackTeleportUtil()
                    .acknowledgeBedrockTeleportFrame(groundPos);
            assertTrue(accepted.isTeleport());
            assertTrue(accepted.isInitialSpawnTeleport());
            player.checkManager.getSimulationProcessor().applyAcceptedBedrockTeleport(accepted);
            assertTrue(player.getSetbackTeleportUtil().hasFullyLoaded);
            assertTrue(player.getSetbackTeleportUtil().hasFullyJoined);
            assertTrue(player.getSetbackTeleportUtil().getRequiredSetBack().isComplete());

            // The first movement after the acknowledged spawn boundary is an
            // impossible pillar teleport and must be corrected to that boundary.
            BedrockAuthInputFrame exploitFrame = syntheticPillarFrame(100, pillarTop, false, true, true);
            player.bedrockState.offerAuthInputFrame(exploitFrame);
            PredictionResult exploitResult = player.checkManager.getSimulationProcessor()
                    .processBedrockAuthInputFrame(exploitFrame, BedrockPredictionTrigger.AUTH_INPUT_PLUGIN_MESSAGE);
            assertNotNull(exploitResult);

            Vec3 postExploitPos = new Vec3(player.x, player.y, player.z);
            assertTrue(exploitResult.hasFlag(BedrockMovement.class));
            assertTrue(player.getSetbackTeleportUtil().isPendingSetback());
            assertFalse(player.getSetbackTeleportUtil().getRequiredSetBack().isPlugin());
            assertEquals(groundPos, player.getSetbackTeleportUtil()
                    .getRequiredSetBack().getTeleportData().getLocation());
            assertEquals(groundPos, postExploitPos);
        } finally {
            if (player != null) {
                closeOfflinePlayer(player);
            }
        }
    }


    @Test
    public void blankAuthorityModeDoesNotBypassBedrockMovement() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        OfflineCultTestBootstrap.setDoubleConfigOverride("Simulation.immediate-setback-threshold", 0.01D);
        OfflineCultTestBootstrap.setDoubleConfigOverride("bedrock-movement.position-flag-threshold", 0.001D);

        CultPlayer player = null;
        try {
            double groundY = 82.0D;
            int pillarX = 3;
            int pillarZ = 0;
            Vec3 pillarTop = new Vec3(pillarX + 0.5D, groundY + 2.00001D, pillarZ + 0.5D);
            Vec3 groundPos = new Vec3(0.5D, groundY, 0.5D);

            player = offlinePlayer();
            player.gamemode = GameMode.SURVIVAL;
            player.canFly = false;
            player.isFlying = false;
            buildFlatGroundWithTwoBlockPillar(player, groundPos, (int) groundY, pillarX, pillarZ);

            player.x = groundPos.x; player.y = groundPos.y; player.z = groundPos.z;
            player.lastX = groundPos.x; player.lastY = groundPos.y; player.lastZ = groundPos.z;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, groundPos.x, groundPos.y, groundPos.z);
            boolean onGround = Collisions.collide(player, 0, -SimpleCollisionBox.COLLISION_EPSILON, 0).y == 0;
            player.onGround = onGround;
            player.lastOnGround = onGround;
            player.getSetbackTeleportUtil().lastKnownGoodPosition =
                    new SetbackPosWithVector(groundPos, Vec3.ZERO, 0);

            // Establish a valid initial carry
            BedrockAuthInputFrame seedFrame = syntheticPillarFrame(0, groundPos, false, false, false);
            player.bedrockState.offerAuthInputFrame(seedFrame);
            player.checkManager.getSimulationProcessor()
                    .processBedrockAuthInputFrame(seedFrame, BedrockPredictionTrigger.AUTH_INPUT_PLUGIN_MESSAGE);

            // authorityMode is bridge metadata, not simulation input. Even a
            // missing label must run the same movement prediction.
            BedrockAuthInputFrame exploitFrame = syntheticPillarFrameBuilder(100, pillarTop, false, true, true)
                    .authorityMode("")
                    .build();

            player.bedrockState.offerAuthInputFrame(exploitFrame);
            PredictionResult exploitResult = player.checkManager.getSimulationProcessor()
                    .processBedrockAuthInputFrame(exploitFrame, BedrockPredictionTrigger.AUTH_INPUT_PLUGIN_MESSAGE);
            assertNotNull(exploitResult);
            assertTrue(exploitResult.hasFlag(BedrockMovement.class));
            assertTrue(player.getSetbackTeleportUtil().isPendingSetback());
            assertFalse(player.getSetbackTeleportUtil().getRequiredSetBack().isPlugin());
            assertEquals(groundPos, player.getSetbackTeleportUtil()
                    .getRequiredSetBack().getTeleportData().getLocation());
            assertEquals(groundPos, new Vec3(player.x, player.y, player.z));
        } finally {
            if (player != null) {
                closeOfflinePlayer(player);
            }
            OfflineCultTestBootstrap.clearDoubleConfigOverride("Simulation.immediate-setback-threshold");
            OfflineCultTestBootstrap.clearDoubleConfigOverride("bedrock-movement.position-flag-threshold");
        }
    }

}
