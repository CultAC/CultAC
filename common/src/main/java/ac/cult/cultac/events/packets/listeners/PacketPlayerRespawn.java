package ac.cult.cultac.events.packets.listeners;

import ac.cult.blocksim.data.HolderSets;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsE;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsF;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsG;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsH;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsM;
import ac.cult.cultac.checks.impl.elytra.ElytraC;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHealth;
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

        if (packet.food() == 20) { // Split so transaction before packet
            player.latencyUtils.addRealTimeTask(player.lastTransactionReceived.get(), () -> player.food = 20);
        } else { // Split so transaction after packet
            player.latencyUtils.addRealTimeTask(
                    player.lastTransactionReceived.get() + 1, () -> player.food = packet.food());
        }

        final PacketEntitySelf healthSelf = player.compensatedEntities.getSelf();
        if (packet.health() <= 0) {
            player.latencyUtils.addRealTimeTaskNow(() -> {
                healthSelf.isDead = true;
                player.checkManager.getListener(BadPacketsM.class).onDeath();
            });
        } else {
            player.latencyUtils.addRealTimeTaskNext(() -> healthSelf.isDead = false);
        }

        player.latencyUtils.addRealTimeTaskNext(
                () -> player.compensatedEntities.getSelf().setHealth(packet.health()));

        event.getTasksAfterSend().add(player::sendTransaction);
    }

    @CultPacketHandler
    public void onLogin(PacketSendEvent<ClientboundLogin> event, CultPlayer player, ClientboundLogin packet) {
        // for the purposes of detecting mineflayer stuff, send a transaction
        // handles transaction split between JOIN_GAME and server teleport

        player.packetStateData.showsDeathScreen = packet.showDeathScreen();

        var spawnInfo = packet.spawnInfo();
        var dimensionType = player.getWorldRegistries().dimension(spawnInfo.dimensionTypeId());
        player.gamemode = ac.cult.cultac.protocol.value.GameMode.valueOf(
                spawnInfo.gameMode().name());
        player.isInvulnerable = player.gamemode == ac.cult.cultac.protocol.value.GameMode.CREATIVE
                || player.gamemode == ac.cult.cultac.protocol.value.GameMode.SPECTATOR;
        // ClientPacketListener.handleLogin sets the local player's ID from this packet.
        // A proxy may rewrite it independently of the backend's native entity ID.
        player.entityID = packet.playerId();
        player.compensatedEntities.vehicles.clearServerVehicle();
        final PacketEntitySelf freshSelf = new PacketEntitySelf(player);
        player.compensatedEntities.playerEntity = freshSelf;
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
                .setDefaultBlockInteractionRange(player.gamemode == ac.cult.cultac.protocol.value.GameMode.CREATIVE);
        player.compensatedEntities.selfTrackedEntity =
                new TrackerData(0, 0, 0, 0, 0, EntityTypeIds.PLAYER, player.lastTransactionSent.get());
        player.dimension = HolderSets.identifier(spawnInfo.dimension());
        player.world = spawnInfo.dimension();
        player.compensatedWorld.setLastClientboundDimension(player.world, dimensionType.dimension());
        player.compensatedWorld.onClientLogin();
        player.compensatedWorld.setDimension(player.world, dimensionType);
        player.compensatedWorld.clientSeaLevel(spawnInfo.seaLevel());
        player.compensatedWorld.clientBiomeZoomSeed(spawnInfo.biomeZoomSeed());
        player.compensatedWorld.resetClientPredictions();
        final long joinedAt = System.currentTimeMillis();
        player.lastJoinedWorld = joinedAt;
    }

    @CultPacketHandler
    public void onRespawn(PacketSendEvent<ClientboundRespawn> event, CultPlayer player, ClientboundRespawn packet) {
        var spawnInfo = packet.spawnInfo();
        String dimension = HolderSets.identifier(spawnInfo.dimension());
        String worldName = spawnInfo.dimension();
        var dimensionType = player.getWorldRegistries().dimension(spawnInfo.dimensionTypeId());
        final List<Runnable> afterSend = event.getTasksAfterSend();
        afterSend.add(player::sendTransaction);
        boolean worldChange = player.compensatedWorld.isLastClientboundDimensionChange(worldName);
        String previousClientboundDimension =
                player.compensatedWorld.getLastClientboundDimension().dimension();
        player.compensatedWorld.setLastClientboundDimension(worldName, dimensionType.dimension());

        // Force the player to accept a teleport before respawning
        // (We won't process movements until they accept a teleport, we won't let movements though either)
        // Also invalidate previous positions
        final ac.cult.cultac.manager.player.SetbackTeleportUtil setbacks = player.getSetbackTeleportUtil();
        setbacks.hasFullyLoaded = false;
        setbacks.lastKnownGoodPosition = null;

        if (worldChange) {
            player.compensatedEntities.serverPositionsMap.clear();
        }

        // TODO: What does keep all metadata do?
        final Runnable applyRespawnState = () -> {
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
            player.lastSprintingForSpeed = false; // This is reverted even on 1.18 clients
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
                player.compensatedWorld.isRaining = false;
                player.checkManager.getListener(BadPacketsH.class).onWorldChange();
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
            player.compensatedEntities.resetClientTickOrder();
            player.compensatedEntities.selfTrackedEntity =
                    new TrackerData(0, 0, 0, 0, 0, EntityTypeIds.PLAYER, player.lastTransactionSent.get());

            player.isSprinting = false;
            player.vehicleData.camelSprintingState = SprintingState.STOPPED;
            badPacketsF.lastSprinting = false;
            player.compensatedEntities.hasSprintingAttributeEnabled = false;
            player.refreshPlayerPose();
            player.gamemode = ac.cult.cultac.protocol.value.GameMode.valueOf(
                    spawnInfo.gameMode().name());
            player.isInvulnerable = player.gamemode == ac.cult.cultac.protocol.value.GameMode.CREATIVE
                    || player.gamemode == ac.cult.cultac.protocol.value.GameMode.SPECTATOR;
            player.compensatedEntities
                    .getSelf()
                    .setDefaultBlockInteractionRange(
                            player.gamemode == ac.cult.cultac.protocol.value.GameMode.CREATIVE);
            player.compensatedWorld.setDimension(worldName, dimensionType);
            // ClientPacketListener creates a new ClientLevel only when the dimension key changes.
            if (worldChange) {
                player.compensatedWorld.clientSeaLevel(spawnInfo.seaLevel());
                player.compensatedWorld.clientBiomeZoomSeed(spawnInfo.biomeZoomSeed());
            }
        };
        player.latencyUtils.addRealTimeTaskNext(applyRespawnState);
    }
}
