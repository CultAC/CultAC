package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAnimate;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.SprintingState;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PacketSelfMetadataListener {

    // HIGH

    @CultPacketHandler
    public void onSetEntityData(PacketSendEvent<EntityMetadata> event, CultPlayer player, EntityMetadata packet) {
        handleSetEntityData(event, player, packet);
    }

    @CultPacketHandler
    public void onAnimate(PacketSendEvent<ClientboundAnimate> event, CultPlayer player, ClientboundAnimate packet) {
        handleAnimate(event, player, packet);
    }

    private void handleSetEntityData(
            PacketSendEvent<EntityMetadata> event, CultPlayer player, EntityMetadata entityMetadataPacket) {
        // The packet's list is immutable; it is copied only to be changed.
        List<EntityMetadata.Entry> packedItems = entityMetadataPacket.packedItems();
        final boolean spoofHealth = player.spoofHealth;
        final int observedEntityId = entityMetadataPacket.id();

        if (observedEntityId == player.entityID) {
            // If we send multiple transactions, we are very likely to split them
            boolean hasSendTransaction = false;
            boolean changed = false;
            Runnable bundledSharedFlags = null;

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
            if (WatchableIndexUtil.getIndex(packedItems, WatchableIndexUtil.ENTITY_POSE) != null) {
                packedItems = new ArrayList<>(packedItems);
                packedItems.removeIf(element -> element.id() == WatchableIndexUtil.ENTITY_POSE);
                changed = true;
            }
            List<EntityMetadata.Entry> entityMetadata = packedItems;

            EntityMetadata.Entry watchable =
                    WatchableIndexUtil.getIndex(entityMetadata, WatchableIndexUtil.ENTITY_SHARED_FLAGS);
            if (watchable != null) {
                // The newest flags the server sent; resyncPose resends them as server truth.
                player.serverSentSelfSharedFlags = watchable;
                Object zeroBitField = watchable.value();
                if (zeroBitField instanceof Byte fieldByte) {
                    final byte field = fieldByte;
                    boolean isGliding = (field & 0x80) == 0x80;
                    boolean isSwimming = (field & 0x10) == 0x10;
                    boolean isSprinting = (field & 0x8) == 0x8;

                    final Runnable applySharedFlags = () -> {
                        player.isSwimming = isSwimming;
                        player.sprintState.onServerFlag(isSprinting);
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
                    if (player.supportsBundles()) {
                        bundledSharedFlags = applySharedFlags;
                    } else {
                        if (!hasSendTransaction) player.sendTransaction();
                        hasSendTransaction = true;
                        player.latencyUtils.addRealTimeTaskNow(applySharedFlags);
                    }
                }
            }

            EntityMetadata.Entry frozen =
                    WatchableIndexUtil.getIndex(entityMetadata, WatchableIndexUtil.ENTITY_TICKS_FROZEN);
            if (frozen != null) {
                if (!hasSendTransaction) player.sendTransaction();
                hasSendTransaction = true;
                player.latencyUtils.addRealTimeTaskNow(() -> player.powderSnowFrozenTicks = (int) frozen.value());
            }

            EntityMetadata.Entry bedObject =
                    WatchableIndexUtil.getIndex(entityMetadata, WatchableIndexUtil.LIVING_SLEEPING_POS);
            if (bedObject != null) {
                Optional<BlockPos> bed = (Optional<BlockPos>) bedObject.value();
                // The Bedrock actor's sleep flag is delivered separately by
                // the bridge after the client acknowledges its own metadata.
                Runnable applyBedMetadata = () -> {
                    if (bed.isPresent()) {
                        player.isInBed = true;
                        BlockPos bedPos = bed.get();
                        player.bedPosition = new Vec3(
                                bedPos.getX() + 0.5,
                                bedPos.getY()
                                        + (player.getClientVersion()
                                                                .isNewerThanOrEquals(
                                                                        ac.cult.cultac.network.protocol.ClientVersion
                                                                                .V_26_3)
                                                        && ac.cult.blocksim.data.BlockIds.is(
                                                                player.compensatedWorld.getBlockStateIdAt(bedPos),
                                                                ac.cult.blocksim.data.BlockIds.STRAW_BED)
                                                ? 0.375
                                                : 0.6875),
                                bedPos.getZ() + 0.5);
                    } else {
                        player.isInBed = false;
                    }
                    player.refreshPlayerPose();
                };
                addTaskAfterPacketProof(event, player, applyBedMetadata);
            }

            EntityMetadata.Entry handState =
                    WatchableIndexUtil.getIndex(entityMetadata, WatchableIndexUtil.LIVING_ENTITY_FLAGS);

            // This one only present if it changed
            if (handState != null && handState.value() instanceof Byte) {
                Object handValue = handState.value();
                boolean isRiptiding = (((byte) handValue) & 0x04) > 0;

                if (player.usesModernJavaItemUse()) {
                    var proof = player.appendTrailingProofTransaction(event);
                    player.itemUseBurst.serverFlags((byte) handValue, proof);
                    if (proof != null) {
                        if (player.supportsBundles() && !event.isInsideBundle()) event.bundle();
                        player.latencyUtils.addRealTimeTask(proof.transaction(), () -> {
                            player.isRiptidePose = isRiptiding;
                            player.refreshPlayerPose();
                            ac.cult.cultac.utils.data.ItemUseInventory.metadata(player, (byte) handValue);
                        });
                    }
                } else {
                    if (!hasSendTransaction) player.sendTransaction();
                    player.latencyUtils.addRealTimeTaskNow(() -> {
                        player.isRiptidePose = isRiptiding;
                        player.refreshPlayerPose();
                    });
                }
            }

            if (bundledSharedFlags != null) {
                bundleSharedFlagsWithProof(event, player, bundledSharedFlags);
            }

            if (changed) {
                event.replace(new EntityMetadata(entityMetadataPacket.id(), packedItems));
            }
        } else if (spoofHealth) {
            boolean changed = false;
            for (int i = 0; i < packedItems.size(); i++) {
                EntityMetadata.Entry packedItem = packedItems.get(i);
                if (packedItem.id() == WatchableIndexUtil.LIVING_HEALTH && packedItem.value() instanceof Float health) {
                    if (health <= 0.0f) {
                        break;
                    } // don't spoof dead entities
                    TrackerData tracked = player.compensatedEntities.getTrackedEntity(observedEntityId);
                    // don't spoof health of rideable entities if they aren't tracked
                    if (tracked != null) {
                        tracked.setHealth(health);
                        if (PacketEntityUtil.isRideable(tracked.getEntityType())
                                && player.lastTransactionSent.get() <= tracked.getLastTransactionHung()) {
                            break;
                        }
                    }
                    // show true health for players with health permission
                    if (player.healthPermission) {
                        break;
                    }
                    // don't spoof health of entities we're riding
                    if (player.getRidingVehicleId() == observedEntityId) break;
                    if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) break;

                    if (!changed) packedItems = new ArrayList<>(packedItems);
                    packedItems.set(i, EntityMetadata.Entry.health(packedItem.id(), event.getDecodedProtocol(), 1.0f));
                    changed = true;
                }
            }
            if (changed) {
                event.replace(new EntityMetadata(entityMetadataPacket.id(), packedItems));
            }
        }
    }

    private void handleAnimate(
            PacketSendEvent<ClientboundAnimate> event, CultPlayer player, ClientboundAnimate animation) {
        int action = animation.action();
        // ClientboundAnimatePacket moved WAKE_UP from 2 to 0 in 26.3 when
        // swings became a separate packet. Use the format decoded at this hook.
        int wakeUp = event.getDecodedProtocol().atLeast(ProtocolVersion.V26_3) ? 0 : 2;
        if (player.entityID == animation.entityId() && action == wakeUp) {
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

    private static void bundleSharedFlagsWithProof(PacketSendEvent<?> event, CultPlayer player, Runnable task) {
        CultPlayer.TrackedTransaction proof = player.appendTrailingProofTransaction(event);
        if (proof == null) {
            player.sendTransaction();
            player.latencyUtils.addRealTimeTaskNow(task);
            return;
        }
        player.latencyUtils.addRealTimeTask(proof.transaction(), task);
    }

    private static void addTaskAfterPacketProof(PacketSendEvent<?> event, CultPlayer player, Runnable task) {
        // Register against the next transaction at after-send time. Metadata
        // handling later in this listener may synchronously send its own
        // pre-packet transactions, so registering earlier could bind the task
        // to a proof that precedes the packet whose state it applies.
        event.getTasksAfterSend().add(() -> {
            player.latencyUtils.addRealTimeTaskNext(task);
            player.sendTrailingTransaction();
        });
    }
}
