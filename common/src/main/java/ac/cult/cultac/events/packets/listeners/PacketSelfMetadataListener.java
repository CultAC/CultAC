package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.checks.impl.prediction.checks.NoSlow;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.packet.PacketCodecUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityUtil;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import ac.cult.cultac.utils.data.SprintingState;
import ac.cult.cultac.network.event.PacketSendEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PacketSelfMetadataListener {


    //HIGH

    @CultPacketHandler
    public void onSetEntityData(PacketSendEvent event, CultPlayer player, ClientboundSetEntityDataPacket packet) {
        handleSetEntityData(event, player, packet);
    }

    @CultPacketHandler
    public void onAnimate(PacketSendEvent event, CultPlayer player, ClientboundAnimatePacket packet) {
        handleAnimate(event, player, packet);
    }

    private void handleSetEntityData(PacketSendEvent event, CultPlayer player, ClientboundSetEntityDataPacket entityMetadataPacket) {
        List<SynchedEntityData.DataValue<?>> packedItems = new ArrayList<>(entityMetadataPacket.packedItems());
        List<SynchedEntityData.DataValue<?>> entityMetadata = packedItems;
        final boolean spoofHealth = player.spoofHealth;

        if (entityMetadataPacket.id() == player.entityID) {
            // If we send multiple transactions, we are very likely to split them
            boolean hasSendTransaction = false;
            boolean changed = false;

                // 1.14+ poses:
                // - Client: I am sneaking
                // - Client: I am no longer sneaking
                // - Server: You are now sneaking
                // - Client: Okay, I am now sneaking.
                // - Server: You are no longer sneaking
                // - Client: Okay, I am no longer sneaking
                //
                // 1.13- poses:
                // - Client: I am sneaking
                // - Client: I am no longer sneaking
                // - Server: Okay, got it.
                //
                // Why mojang, why. Why are you so incompetent at netcode.
                //
                // Also, mojang. This system makes movement ping dependent!
                // A player using or exiting an elytra, or using or exiting sneaking will have different movement
                // to a player because of sending poses! ViaVersion works fine without sending these poses
                // to the player on old servers... because the player just overrides this pose the very next tick
                //
                // It makes no sense to me why mojang is doing this, it has to be a bug.
                changed |= packedItems.removeIf(element -> element.id() == WatchableIndexUtil.ENTITY_POSE.id());
                entityMetadata.removeIf(element -> element.id() == WatchableIndexUtil.ENTITY_POSE.id());

                SynchedEntityData.DataValue<?> watchable = WatchableIndexUtil.getIndex(entityMetadata, WatchableIndexUtil.ENTITY_SHARED_FLAGS);
                if (watchable != null) { Object zeroBitField = watchable.value();
                    if (zeroBitField instanceof Byte fieldByte) {
                        final byte field = fieldByte;
                        boolean isGliding = (field & 0x80) == 0x80;
                        boolean isSwimming = (field & 0x10) == 0x10;
                        boolean isSprinting = (field & 0x8) == 0x8;

                        if (!hasSendTransaction) player.sendTransaction();
                        hasSendTransaction = true;

                        final Runnable applySharedFlags = () -> {
                            player.isSwimming = isSwimming;
                            player.lastSprinting = isSprinting;
                            if (!isSprinting) {
                                // The local client can retain its Camel sprint input for
                                // the movement tick in which the replicated flag stops.
                                player.vehicleData.camelSprintingState = SprintingState.STOPPING;
                            }
                            // Protect this due to players being able to get the server to spam this packet a lot
                            if (player.isGliding != isGliding) {
                                // no-op
                            }
                            player.isGliding = isGliding;
                            player.refreshPlayerPose();
                        };
                        player.latencyUtils.addRealTimeTaskNow(applySharedFlags);
                    }
                }

                SynchedEntityData.DataValue<?> frozen = WatchableIndexUtil.getIndex(entityMetadata, WatchableIndexUtil.ENTITY_TICKS_FROZEN);
                if (frozen != null) {
                    if (!hasSendTransaction) player.sendTransaction();
                    hasSendTransaction = true;
                    player.latencyUtils.addRealTimeTaskNow(() -> player.powderSnowFrozenTicks = (int) frozen.value());
                }

                SynchedEntityData.DataValue<?> bedObject = WatchableIndexUtil.getIndex(entityMetadata, WatchableIndexUtil.LIVING_SLEEPING_POS);
                if (bedObject != null) { Optional<BlockPos> bed = (Optional<BlockPos>) bedObject.value();
                    // Geyser updates its bed position from this ordered Java
                    // metadata packet (AvatarEntity.java:189-201), and
                    // BedrockMovePlayer gates movement on that state
                    // (BedrockMovePlayer.java:74-79). Reuse Cult's existing
                    // transaction proof to release the gate only after Geyser
                    // has processed wake metadata. Entering sleep is applied
                    // immediately and therefore fails closed at the boundary.
                    if (bed.isPresent()) { player.checkManager.getSimulationProcessor().handleBedrockSleepingStateChange(true); }
                    Runnable applyBedMetadata = () -> {
                        if (bed.isPresent()) { player.isInBed = true;
                            BlockPos bedPos = bed.get();
                            player.bedPosition = new Vec3(
                                    bedPos.getX() + 0.5,
                                    bedPos.getY() + 0.6875,
                                    bedPos.getZ() + 0.5);
                        } else {
                            player.checkManager.getSimulationProcessor()
                                    .handleBedrockSleepingStateChange(false);
                            player.isInBed = false;
                        }
                        player.refreshPlayerPose();
                    };
                    addTaskAfterPacketProof(event, player, applyBedMetadata);
                }

                SynchedEntityData.DataValue<?> handState = WatchableIndexUtil.getIndex(entityMetadata, WatchableIndexUtil.LIVING_ENTITY_FLAGS);

                // This one only present if it changed
                if (handState != null && handState.value() instanceof Byte) {
                    Object handValue = handState.value();
                    boolean isRiptiding = (((byte) handValue) & 0x04) > 0;

                    if (!hasSendTransaction) player.sendTransaction();

                    player.latencyUtils.addRealTimeTaskNow(() -> { player.isRiptidePose = isRiptiding;
                        player.refreshPlayerPose();
                    });

                    boolean isActive = (((byte) handValue) & 0x01) > 0;

                    if (isActive) {
                        player.sendTransaction();

                        // Player might have gotten this packet
                        final Runnable markSettingMetadata = () -> player.settingMetaData = true;
                        player.latencyUtils.addRealTimeTaskNow(markSettingMetadata);

                        // Player has gotten this packet
                        final Runnable finishMetadata = () -> {
                            final NoSlow noSlowCheck = player.checkManager.getListener(NoSlow.class);
                            noSlowCheck.setDoneSettingMetadata(true);
                        };
                        player.latencyUtils.addRealTimeTaskNext(finishMetadata);

                        // Yes, we do have to use a transaction for eating as otherwise it can desync much easier
                        event.getTasksAfterSend().add(player::sendTransaction);
                    }
                }

                if (changed) {
                    event.setNmsPacket(new ClientboundSetEntityDataPacket(entityMetadataPacket.id(), packedItems));
                }
            } else if (spoofHealth) {
                boolean changed = false;
                for (int i = 0; i < packedItems.size(); i++) {
                    SynchedEntityData.DataValue<?> packedItem = packedItems.get(i);
                    if (packedItem.id() == WatchableIndexUtil.LIVING_HEALTH.id() && packedItem.value() instanceof Float health) {
                        if (health <= 0.0f) { break; } // don't spoof dead entities
                        TrackerData tracked = player.compensatedEntities.getTrackedEntity(entityMetadataPacket.id());
                        // don't spoof health of rideable entities if they aren't tracked
                        if (tracked != null) {
                            tracked.setHealth(health);
                            if (PacketEntityUtil.isRideable(tracked.getEntityType()) &&
                                    player.lastTransactionSent.get() <= tracked.getLastTransactionHung()) { break; }
                        }
                        // show true health for players with health permission
                        if (player.healthPermission) { break; }
                        // don't spoof health of entities we're riding
                        if (player.getRidingVehicleId() == entityMetadataPacket.id()) break;

                        packedItems.set(i, new SynchedEntityData.DataValue<>(WatchableIndexUtil.LIVING_HEALTH.id(), EntityDataSerializers.FLOAT, 1.0f));
                        changed = true;
                    }
                }
                if (changed) {
                    event.setNmsPacket(new ClientboundSetEntityDataPacket(entityMetadataPacket.id(), packedItems));
                }
            }
    }

    private void handleAnimate(PacketSendEvent event, CultPlayer player, ClientboundAnimatePacket animation) {
        int action = PacketCodecUtil.decodeUnsignedByte(animation.getAction());
        if (player.entityID == animation.getId() && action == ClientboundAnimatePacket.WAKE_UP) {
            // Geyser's LEAVE_BED animation changes only the upstream pose. Its
            // movement translator continues dropping auth movement while the
            // entity bed position is non-null, so Bedrock release is owned by
            // the later sleeping-position metadata boundary.
            // Geyser: JavaAnimateTranslator.java:106-110;
            // BedrockMovePlayer.java:74-79.
            if (player.isBedrockMovement()) {
                return;
            }
            addTaskAfterPacketProof(event, player, () -> {
                player.isInBed = false;
                player.refreshPlayerPose();
            });
        }
    }

    private static void addTaskAfterPacketProof(
            PacketSendEvent event,
            CultPlayer player,
            Runnable task
    ) {
        // Register against the next transaction at after-send time. Metadata
        // handling later in this listener may synchronously send its own
        // pre-packet transactions, so registering earlier could bind the task
        // to a proof that precedes the packet whose state it applies.
        event.getTasksAfterSend().add(() -> {
            player.latencyUtils.addRealTimeTaskNext(task);
            player.sendTransaction();
        });
    }
}
