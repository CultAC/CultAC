package ac.grim.grimac.events.packets.listeners;

import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.checks.impl.badpackets.BadPacketsE;
import ac.grim.grimac.checks.impl.badpackets.BadPacketsF;
import ac.grim.grimac.checks.impl.badpackets.BadPacketsG;
import ac.grim.grimac.checks.impl.badpackets.BadPacketsH;
import ac.grim.grimac.checks.impl.badpackets.BadPacketsM;
import ac.grim.grimac.checks.impl.elytra.ElytraC;
import ac.grim.grimac.checks.impl.prediction.runner.SimulationProcessor;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.data.TrackerData;
import ac.grim.grimac.utils.data.packetentity.PacketEntitySelf;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import ac.grim.grimac.utils.nmsutil.NmsIdentifierUtil;
import ac.grim.grimac.network.event.PacketSendEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;

public class PacketPlayerRespawn {

    //HIGH
    @GrimPacketHandler
    public void onSetHealth(PacketSendEvent event, GrimPlayer player, ClientboundSetHealthPacket packet) {
        //
        player.packetStateData.lastFood = packet.getFood();
        player.packetStateData.lastHealth = packet.getHealth();
        player.packetStateData.lastSaturation = packet.getSaturation();

        player.sendTransaction();

        if (packet.getFood() == 20) { // Split so transaction before packet
            player.latencyUtils.addRealTimeTask(player.lastTransactionReceived.get(), () -> player.food = 20);
        } else { // Split so transaction after packet
            player.latencyUtils.addRealTimeTask(player.lastTransactionReceived.get() + 1, () -> player.food = packet.getFood());
        }

        final PacketEntitySelf healthSelf = player.compensatedEntities.getSelf();
        if (packet.getHealth() <= 0) {
            player.latencyUtils.addRealTimeTaskNow(() -> {
                healthSelf.isDead = true;
                player.checkManager.getListener(BadPacketsM.class).onDeath();
            });
        } else {
            player.latencyUtils.addRealTimeTaskNext(() -> healthSelf.isDead = false);
        }

        player.latencyUtils.addRealTimeTaskNext(() -> player.compensatedEntities.getSelf().setHealth(packet.getHealth()));

        event.getTasksAfterSend().add(player::sendTransaction);
    }

    @GrimPacketHandler
    public void onLogin(PacketSendEvent event, GrimPlayer player, ClientboundLoginPacket packet) {
        // for the purposes of detecting mineflayer stuff, send a transaction
        // handles transaction split between JOIN_GAME and server teleport

        player.packetStateData.showsDeathScreen = packet.showDeathScreen();

        CommonPlayerSpawnInfo spawnInfo = packet.commonPlayerSpawnInfo();
        player.gamemode = switch (spawnInfo.gameType()) {
            case CREATIVE -> org.bukkit.GameMode.CREATIVE;
            case ADVENTURE -> org.bukkit.GameMode.ADVENTURE;
            case SPECTATOR -> org.bukkit.GameMode.SPECTATOR;
            default -> org.bukkit.GameMode.SURVIVAL;
        };
        player.entityID = player.bukkitPlayer == null ? packet.playerId() : player.bukkitPlayer.getEntityId();
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
        player.compensatedEntities.getSelf().setDefaultBlockInteractionRange(player.gamemode == org.bukkit.GameMode.CREATIVE);
        player.compensatedEntities.selfTrackedEntity = new TrackerData(0, 0, 0, 0, 0, EntityTypesCompat.PLAYER, player.lastTransactionSent.get());
        player.dimension = spawnInfo.dimension();
        player.world = NmsIdentifierUtil.resourceKey(spawnInfo.dimension());
        player.compensatedWorld.setLastClientboundDimension(spawnInfo);
        player.compensatedWorld.setDimension(spawnInfo);
        player.compensatedWorld.resetClientPredictions();
        final long joinedAt = System.currentTimeMillis();
        player.lastJoinedWorld = joinedAt;
    }

    @GrimPacketHandler
    public void onRespawn(PacketSendEvent event, GrimPlayer player, ClientboundRespawnPacket packet) {
        CommonPlayerSpawnInfo spawnInfo = packet.commonPlayerSpawnInfo();
        ResourceKey<Level> dimension = spawnInfo.dimension();
        String worldName = NmsIdentifierUtil.resourceKey(spawnInfo.dimension());
        final List<Runnable> afterSend = event.getTasksAfterSend();
        afterSend.add(player::sendTransaction);
        boolean worldChange = player.compensatedWorld.isLastClientboundDimensionChange(spawnInfo);
        String previousClientboundDimension = player.compensatedWorld.getLastClientboundDimension().dimension();
        player.compensatedWorld.setLastClientboundDimension(spawnInfo);

        // Force the player to accept a teleport before respawning
        // (We won't process movements until they accept a teleport, we won't let movements though either)
        // Also invalidate previous positions
        final ac.grim.grimac.manager.player.SetbackTeleportUtil setbacks = player.getSetbackTeleportUtil();
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
            player.packetStateData.packetPlayerOnGround = false; // If somewhere else pulls last ground to fix other issues
            player.packetStateData.clientSidePosition = Vec3.ZERO;
            player.packetStateData.lastClientTickEndTransaction = Integer.MIN_VALUE;
            player.packetStateData.clearPendingVehicleMoveAfterPassengerRotation();
            player.lastSprintingForSpeed = false; // This is reverted even on 1.18 clients
            final ac.grim.grimac.manager.player.ActionManager actions = player.actionManager;
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
                LogUtil.info(player.getName() + " respawned! World=" + worldName + " DimensionName=" + NmsIdentifierUtil.resourceKey(dimension));
            }
            // Keep latency-visible entity/player state ordered behind Grim's respawn transaction.
            if (worldChange) {
                if (setbacks.isDebug()) { LogUtil.info(player.getName() + " moved to a new world!"); }
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
            final PacketEntitySelf respawnedSelf = new PacketEntitySelf(player, player.compensatedEntities.playerEntity);
            player.compensatedEntities.playerEntity = respawnedSelf;
            // Same self-instance replacement as login: re-seed the identity-compared camera
            // deque from the new self or isSelf() is permanently false after respawn.
            player.cameraEntity.reset();
            player.compensatedEntities.resetClientTickOrder();
            player.compensatedEntities.selfTrackedEntity = new TrackerData(0, 0, 0, 0, 0, EntityTypesCompat.PLAYER, player.lastTransactionSent.get());

            player.isSprinting = false;
            badPacketsF.lastSprinting = false;
            player.compensatedEntities.hasSprintingAttributeEnabled = false;
            player.compensatedEntities.resetBedrockMovementSpeedAttribute();
            // Preserve the last translated jump-strength value. The Java client copies
            // attribute base values into its replacement player on respawn
            // (ClientPacketListener.java:1342-1346), and Geyser resetAttributes
            // only sends MOVEMENT_SPEED (SessionPlayerEntity.java:462-471), not
            // a replacement Bedrock jump-strength attribute.
            player.refreshPlayerPose();
            player.gamemode = switch (spawnInfo.gameType()) {
                case CREATIVE -> org.bukkit.GameMode.CREATIVE;
                case ADVENTURE -> org.bukkit.GameMode.ADVENTURE;
                case SPECTATOR -> org.bukkit.GameMode.SPECTATOR;
                default -> org.bukkit.GameMode.SURVIVAL;
            };
            player.compensatedEntities.getSelf().setDefaultBlockInteractionRange(player.gamemode == org.bukkit.GameMode.CREATIVE);
            player.compensatedWorld.setDimension(spawnInfo);
        };
        player.latencyUtils.addRealTimeTaskNext(applyRespawnState);
    }

}
