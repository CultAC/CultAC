package ac.cult.cultac.events.packets.listeners;

import ac.cult.blocksim.data.HolderSets;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.environment.DimensionData;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsE;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsF;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsG;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsH;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsM;
import ac.cult.cultac.checks.impl.elytra.ElytraC;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.events.packets.RegistryGenerationAlerts;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHealth;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.data.SprintingState;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;

public class PacketPlayerRespawn {

    // HIGH
    @CultPacketHandler
    public void onSetHealth(
            PacketSendEvent<ClientboundSetHealth> event, CultPlayer player, ClientboundSetHealth packet) {
        //
        player.packetStateData.lastFood = packet.food();
        player.packetStateData.lastHealth = packet.health();
        player.packetStateData.lastSaturation = packet.saturation();

        player.sendTransaction();
        // A higher food level refuses more starts and permits more sprinting.
        player.latencyUtils.addRealTimeTaskNow(() -> player.food = Math.max(player.food, packet.food()));

        final PacketEntitySelf healthSelf = player.compensatedEntities.getSelf();
        if (packet.health() <= 0) {
            player.latencyUtils.addRealTimeTaskNow(() -> {
                healthSelf.isDead = true;
                player.checkManager.getListener(BadPacketsM.class).onDeath();
            });
        }

        // Player#canEat and sprint permission read the food level the client handled; handleBundlePacket
        // applies it and the proof in one client task.
        player.runAtTrailingProof(event, () -> {
            player.food = packet.food();
            player.clientItemUse.foodReceived();
            if (packet.health() > 0) healthSelf.isDead = false;
            player.compensatedEntities.getSelf().setHealth(packet.health());
        });
    }

    @CultPacketHandler("clientbound.start_configuration")
    public void onStartConfiguration(PacketSendEvent<Opaque> event, CultPlayer player, Opaque packet) {
        PlayerObserver observer = player.observer;
        if (observer != null) observer.configurationStarted();
        player.getSetbackTeleportUtil().levelLeft();
        player.getGhostBlockMitigator().clearLevel();
    }

    @CultPacketHandler
    public void onLogin(PacketSendEvent<ClientboundLogin> event, CultPlayer player, ClientboundLogin packet) {
        PlayerObserver observer = player.observer;
        if (observer != null) observer.loginSent(packet);
        // for the purposes of detecting mineflayer stuff, send a transaction
        // handles transaction split between JOIN_GAME and server teleport

        player.packetStateData.showsDeathScreen = packet.showDeathScreen();
        final boolean newJavaLocalPlayer =
                !player.isBedrockMovement() && !player.packetStateData.javaLocalPlayerPresent;
        event.getTasksAfterSend().add(() -> {
            if (!player.isBedrockMovement()) player.packetStateData.javaLocalPlayerPresent = true;
            // The server-view use belongs to the former player. Clear it at LOGIN send,
            // before new self metadata can start another use; receipt resets only client state.
            // reset() preserves accumulated debt.
            if (newJavaLocalPlayer) player.itemUseBurst.reset();
        });

        var spawnInfo = packet.spawnInfo();
        player.gamemode = ac.cult.cultac.protocol.value.GameMode.valueOf(
                spawnInfo.gameMode().name());
        player.canInstabuild = player.gamemode == ac.cult.cultac.protocol.value.GameMode.CREATIVE;
        player.isInvulnerable = player.gamemode == ac.cult.cultac.protocol.value.GameMode.CREATIVE
                || player.gamemode == ac.cult.cultac.protocol.value.GameMode.SPECTATOR;
        // ClientPacketListener.handleLogin sets the local player's ID from this packet.
        // A proxy may rewrite it independently of the backend's native entity ID.
        player.entityID = packet.playerId();
        if (!player.isBedrockMovement()) player.compensatedEntities.serverPositionsMap.clear();
        player.compensatedEntities.vehicles.clearServerVehicle();
        final PacketEntitySelf freshSelf = new PacketEntitySelf(player);
        player.compensatedEntities.playerEntity = freshSelf;
        // ClientPacketListener#handleLogin clears clientLoaded until ServerboundPlayerLoadedPacket.
        player.packetStateData.playerLoadedIntoLevel = false;
        player.packetStateData.lastSentTickPosition = null;
        if (!player.isBedrockMovement()) player.onLocalPlayerCreated();
        if (!player.isBedrockMovement()) {
            // Minecraft#clearClientLevel discards the LocalPlayer before a reconfiguration login.
            player.isSprinting = false;
            player.sprintState.reset();
            player.attackStrength.reset();
            player.combatAttributes.reset();
            player.checkManager.getListener(BadPacketsF.class).lastSprinting = false;
        }
        // The login replacement swaps the self-entity instance; the camera entity seeded its
        // identity-compared deque with the constructor-time instance, so re-seed it from the
        // replacement or isSelf() stays false forever after every login.
        player.cameraEntity.reset();
        // Preserve the current Bedrock jump strength across Geyser backend switches.
        // Geyser's SessionPlayerEntity.resetAttributes only sends movement speed
        // (SessionPlayerEntity.java:462-471), so the Bedrock player keeps this value.
        player.compensatedEntities.resetClientTickOrder();
        player.compensatedEntities
                .getSelf()
                .onGameModeChange(player.gamemode == ac.cult.cultac.protocol.value.GameMode.CREATIVE);
        player.compensatedEntities.selfTrackedEntity =
                new TrackerData(0, 0, 0, 0, 0, EntityTypeIds.PLAYER, player.lastTransactionSent.get());
        player.dimension = HolderSets.identifier(spawnInfo.dimension());
        player.world = spawnInfo.dimension();
        // A Java ClientLevel is replaced at LOGIN receipt, below.
        if (player.isBedrockMovement()) player.compensatedWorld.clearChunks();
        player.compensatedWorld.serverWorldChangeSent();
        // handleLogin creates a fresh ClientLevel, including same-dimension switches.
        // Its replacement player has no prior travel carry, ground state or active use.
        // Apply only when the ping after LOGIN proves that ordered client processing.
        if (!player.isBedrockMovement()) {
            player.latencyUtils.addRealTimeTaskNext(() -> {
                player.compensatedWorld.clearChunks();
                player.compensatedWorld.clearPistonLikeState();
                player.compensatedEntities.clearClientLevel();
                player.compensatedWorld.isRaining = false;
                if (newJavaLocalPlayer) {
                    player.actionManager.onJavaPlayerReplacement();
                    player.onGround = player.lastOnGround = false;
                    player.isSneaking = false;
                    player.isGliding = false;
                    player.isInBed = false;
                    player.packetStateData.packetPlayerOnGround = false;
                    player.checkManager.getSimulationProcessor().handleRespawn();
                }
            });
            event.getTasksAfterSend().add(player::sendTransaction);
        }
        player.serverSentSelfSharedFlags = null;
        player.compensatedWorld.onClientLogin();
        var dimensionType = dimensionType(player, spawnInfo.dimensionTypeId());
        if (dimensionType != null) {
            player.compensatedWorld.setLastClientboundDimension(player.world, dimensionType.dimension());
            player.compensatedWorld.setDimension(player.world, dimensionType);
        } else {
            player.compensatedWorld.setLastClientboundUnresolved(player.world);
            player.compensatedWorld.setUnresolvedDimension(player.world);
        }
        player.compensatedWorld.clientSeaLevel(spawnInfo.seaLevel());
        player.compensatedWorld.clientBiomeZoomSeed(spawnInfo.biomeZoomSeed());
        player.compensatedWorld.resetClientPredictions();
        player.checkManager.getListener(BadPacketsH.class).resetLevel();
        player.getGhostBlockMitigator().clearLevel();
        final long joinedAt = System.currentTimeMillis();
        player.lastJoinedWorld = joinedAt;
    }

    /**
     * Null, reported, when the published generation cannot name this dimension type. The level reset still
     * applies and the level stays unresolved: its chunks are left out of the model and checks are paused.
     */
    private static DimensionData.Binding dimensionType(CultPlayer player, int id) {
        try {
            return player.getWorldRegistries().readableDimension(id);
        } catch (RuntimeException failure) {
            RegistryGenerationAlerts.unresolvedDimension(player, id, failure);
            return null;
        }
    }

    @CultPacketHandler
    public void onRespawn(PacketSendEvent<ClientboundRespawn> event, CultPlayer player, ClientboundRespawn packet) {
        PlayerObserver observer = player.observer;
        if (observer != null) observer.respawnSent(packet);
        var spawnInfo = packet.spawnInfo();
        String dimension = HolderSets.identifier(spawnInfo.dimension());
        String worldName = spawnInfo.dimension();
        final List<Runnable> afterSend = event.getTasksAfterSend();
        afterSend.add(player::sendTrailingTransaction);
        final GameMode respawnMode = GameMode.valueOf(spawnInfo.gameMode().name());
        final boolean instabuild = respawnMode == GameMode.CREATIVE;
        final boolean invulnerable = respawnMode == GameMode.CREATIVE || respawnMode == GameMode.SPECTATOR;
        player.latencyUtils.addRealTimeTaskNow(() -> {
            player.abilitiesSent(instabuild, invulnerable);
            player.food = 20;
        });
        boolean worldChange = player.compensatedWorld.isLastClientboundDimensionChange(worldName);
        String previousClientboundDimension =
                player.compensatedWorld.getLastClientboundDimension().dimension();
        var dimensionType = dimensionType(player, spawnInfo.dimensionTypeId());
        if (dimensionType != null)
            player.compensatedWorld.setLastClientboundDimension(worldName, dimensionType.dimension());
        else player.compensatedWorld.setLastClientboundUnresolved(worldName);
        // The server's new player entity starts from default shared flags.
        player.serverSentSelfSharedFlags = null;

        // Force the player to accept a teleport before respawning
        // (We won't process movements until they accept a teleport, we won't let movements though either)
        // Also invalidate previous positions
        final ac.cult.cultac.manager.player.SetbackTeleportUtil setbacks = player.getSetbackTeleportUtil();
        setbacks.hasFullyLoaded = false;
        setbacks.lastKnownGoodPosition = null;

        if (worldChange) {
            player.compensatedEntities.serverPositionsMap.clear();
            player.compensatedWorld.serverWorldChangeSent();
        }
        final BadPacketsH sequences = player.checkManager.getListener(BadPacketsH.class);
        final int sequenceLevel = worldChange ? sequences.beginLevel() : -1;

        final boolean keepEntityData = packet.shouldKeep(ClientboundRespawn.KEEP_ENTITY_DATA);
        final boolean keepAttributeModifiers = packet.shouldKeep(ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS);
        // TODO: What does keep all metadata do?
        // A later login replaces this level; its respawn must not touch the new one.
        int level = player.compensatedWorld.level();
        final Runnable applyRespawnState = () -> {
            if (level != player.compensatedWorld.level()) return;
            player.isSneaking = false;
            final BadPacketsF badPacketsF = player.checkManager.getListener(BadPacketsF.class);
            badPacketsF.exemptNext = true;
            player.lastOnGround = false;
            player.isGliding = false;
            player.isInBed = false;
            // Respawn permits one glide-state re-entry.
            ElytraC elytraC = player.checkManager.getListener(ElytraC.class);
            if (elytraC != null) {
                elytraC.exempt = true;
            }
            player.packetStateData.packetPlayerOnGround =
                    false; // If somewhere else pulls last ground to fix other issues
            player.packetStateData.clientSidePosition = Vec3.ZERO;
            player.packetStateData.lastClientTickEndTransaction = Integer.MIN_VALUE;
            player.packetStateData.clearPendingVehicleMoveAfterPassengerRotation();
            final ac.cult.cultac.manager.player.ActionManager actions = player.actionManager;
            actions.onRespawn();
            player.checkManager.getListener(BadPacketsM.class).onRespawn();

            // TODO: Perhaps make this an event, make this more OOP
            final SimulationProcessor simulation = player.checkManager.getListener(SimulationProcessor.class);
            simulation.handleRespawn();
            final BadPacketsE badPacketsE = player.checkManager.getListener(BadPacketsE.class);
            badPacketsE.handleRespawn(); // Reminder ticks reset
            player.checkManager.getListener(BadPacketsG.class).handleRespawn();
            player.checkManager.getKnockbackHandler().exempt();
            player.checkManager.getExplosionHandler().exempt();

            if (setbacks.isDebug()) {
                LogUtil.info(player.getName() + " respawned! World=" + worldName + " DimensionName=" + dimension);
            }
            // Keep latency-visible entity/player state ordered behind Cult's respawn transaction.
            if (worldChange) {
                if (setbacks.isDebug()) {
                    LogUtil.info(player.getName() + " moved to a new world!");
                }
                player.compensatedEntities.entityMap.clear();
                player.compensatedWorld.clearPistonLikeState();
                player.compensatedWorld.clearChunksForDimension(previousClientboundDimension);
                player.compensatedWorld.resetClientPredictions();
                player.getGhostBlockMitigator().clearLevel();
                player.compensatedWorld.isRaining = false;
                sequences.levelProven(sequenceLevel);
                final long worldJoinTime = System.currentTimeMillis();
                player.lastJoinedWorld = worldJoinTime;
            }
            player.dimension = dimension;
            player.world = worldName;

            player.compensatedEntities.vehicles.clearServerVehicle(); // All entities get removed on respawn
            final PacketEntitySelf respawnedSelf =
                    new PacketEntitySelf(player, player.compensatedEntities.playerEntity);
            if (player.isBedrockMovement()) {
                // Java replaces its player here; the Bedrock actor keeps attributes until wire updates replace them.
                respawnedSelf.bedrockRuntimeId = player.compensatedEntities.playerEntity.bedrockRuntimeId;
                respawnedSelf.bedrockAttributes = player.compensatedEntities.playerEntity.bedrockAttributes;
            }
            player.compensatedEntities.playerEntity = respawnedSelf;
            // Same self-instance replacement as login: re-seed the identity-compared camera
            // deque from the new self or isSelf() is permanently false after respawn.
            player.cameraEntity.reset();
            if (!player.isBedrockMovement()) {
                player.openWindow.closeFromRespawn();
                player.checkManager
                        .getListener(ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaAutoJump.class)
                        .reset();
                player.checkManager
                        .getCheck(ac.cult.cultac.checks.impl.badpackets.BadPacketsV.class)
                        .onNewLocalPlayer();
            }
            if (worldChange || player.isBedrockMovement()) player.compensatedEntities.resetClientTickOrder();
            else player.compensatedEntities.appendSelfToClientTickOrder();
            player.compensatedEntities.selfTrackedEntity =
                    new TrackerData(0, 0, 0, 0, 0, EntityTypeIds.PLAYER, player.lastTransactionSent.get());

            // ClientPacketListener#handleRespawn gives the new LocalPlayer the old sprint flag as
            // wasSprinting, and copies the old shared flags, only for KEEP_ENTITY_DATA.
            // ClientPacketListener#handleRespawn clears clientLoaded until ServerboundPlayerLoadedPacket.
            player.packetStateData.playerLoadedIntoLevel = false;
            player.packetStateData.lastSentTickPosition = null;
            if (!player.isBedrockMovement()) player.onLocalPlayerCreated();
            player.sprintState.onRespawn(keepEntityData, keepAttributeModifiers);
            player.attackStrength.reset();
            player.combatAttributes.onRespawn(keepAttributeModifiers);
            player.isSprinting = !player.isBedrockMovement() && player.sprintState.flag();
            player.vehicleData.camelSprintingState = SprintingState.STOPPED;
            badPacketsF.lastSprinting = player.isSprinting;
            player.refreshPlayerPose();
            player.gamemode = ac.cult.cultac.protocol.value.GameMode.valueOf(
                    spawnInfo.gameMode().name());
            player.canInstabuild = player.gamemode == ac.cult.cultac.protocol.value.GameMode.CREATIVE;
            player.isInvulnerable = player.gamemode == ac.cult.cultac.protocol.value.GameMode.CREATIVE
                    || player.gamemode == ac.cult.cultac.protocol.value.GameMode.SPECTATOR;
            respawnedSelf.onRespawn(
                    keepAttributeModifiers, player.gamemode == ac.cult.cultac.protocol.value.GameMode.CREATIVE);
            if (dimensionType != null) player.compensatedWorld.setDimension(worldName, dimensionType);
            else player.compensatedWorld.setUnresolvedDimension(worldName);
            // ClientPacketListener creates a new ClientLevel only when the dimension key changes.
            if (worldChange) {
                player.compensatedWorld.clientSeaLevel(spawnInfo.seaLevel());
                player.compensatedWorld.clientBiomeZoomSeed(spawnInfo.biomeZoomSeed());
            }
        };
        player.latencyUtils.addRealTimeTaskNext(applyRespawnState);
    }
}
