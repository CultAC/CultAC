package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsM;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.utils.data.ShulkerData;
import ac.cult.cultac.utils.data.packetentity.*;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@link CompensatedEntities#updateEntityMetadata(int, List, int)} and the helpers it calls, as revision b0b47d509
 * wrote them: each lookup through {@link WatchableIndexUtil#getIndex}.
 */
public final class BaseEntityMetadataApply {
    private final CultPlayer player;
    private final CompensatedVehicleState vehicles;

    public BaseEntityMetadataApply(CultPlayer player) {
        this.player = player;
        this.vehicles = player.compensatedEntities.vehicles;
    }

    public void updateEntityMetadata(int entityID, List<EntityMetadata.Entry> watchableObjects, int provenAt) {
        PacketEntity entity = player.compensatedEntities.getEntity(entityID);
        if (entity == null) return;
        entity.onData(provenAt);
        if (entity instanceof PacketEntityHanging hanging)
            hanging.updateActionMetadata(
                    watchableObjects, player.user.getCultConnection().getObservedProtocol());
        if (entity instanceof PacketEntityCat cat)
            cat.updateActionMetadata(
                    watchableObjects, player.user.getCultConnection().getObservedProtocol());
        if (entity instanceof PacketEntityArmorStand stand) stand.updateActionMetadata(watchableObjects);
        var flags = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.ENTITY_SHARED_FLAGS);
        if (flags != null && flags.value() instanceof Byte value) entity.actionSharedFlags = value;
        if (entity.isDisplay()) {
            var duration = WatchableIndexUtil.getIndex(
                    watchableObjects, WatchableIndexUtil.DISPLAY_POS_ROT_INTERPOLATION_DURATION);
            if (duration != null && duration.value() instanceof Integer value) entity.onDisplayTeleportDuration(value);
        }
        if (entity.isLivingEntity()) {
            var pose = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.ENTITY_POSE);
            if (pose != null && pose.value() instanceof EntityPose value) {
                entity.onPose(value, provenAt);
            }
            var health = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.LIVING_HEALTH);
            if (health != null && health.value() instanceof Float value) {
                if (entity instanceof PacketEntitySelf self) {
                    self.setHealth(value);
                    // Gui#tick opens the death screen once DATA_HEALTH_ID makes LivingEntity#isDeadOrDying true.
                    if (value <= 0.0F && !player.isBedrockMovement()) {
                        player.checkManager.getListener(BadPacketsM.class).onDeath();
                    }
                } else entity.actionAlive = value > 0.0F;
            }
        }
        if (entity instanceof PacketEntityCreaking creaking) {
            var canMove = WatchableIndexUtil.getIndex(watchableObjects, 16);
            if (canMove != null && canMove.value() instanceof Boolean value) creaking.actionCanMove = value;
        }
        if (entity instanceof PacketEntitySpider spider) {
            var climbing = WatchableIndexUtil.getIndex(watchableObjects, 16);
            if (climbing != null && climbing.value() instanceof Byte value) spider.actionClimbing = (value & 1) != 0;
        }
        if (entity instanceof PacketEntityInteraction interaction) {
            var width = WatchableIndexUtil.getIndex(watchableObjects, 8);
            var height = WatchableIndexUtil.getIndex(watchableObjects, 9);
            if (width != null && width.value() instanceof Float value) interaction.actionWidth = value;
            if (height != null && height.value() instanceof Float value) interaction.actionHeight = value;
            // Interaction#onSyncedDataUpdated refreshes its dimensions on either field.
            if (width != null || height != null) interaction.refreshDimensions(player);
        }
        var modelType = EntityTypeUtil.modelType(entity.type);
        if (modelType != null && modelType.has(ac.cult.blocksim.entity.EntityTypes.ARROW)) {
            var inGround = WatchableIndexUtil.getIndex(watchableObjects, 10);
            if (inGround != null && inGround.value() instanceof Boolean value) entity.actionArrowInGround = value;
        }

        WatchableIndexUtil.Layout layout = WatchableIndexUtil.forModel(player.user.getCultConnection());
        applyAgeableMetadata(entity, watchableObjects);
        applySizeMetadata(entity, watchableObjects, layout);
        if (entity instanceof PacketEntityShulker) {
            applyShulkerMetadata(entity, watchableObjects, provenAt);
        }
        if (entity instanceof PacketEntityPolarBear bear) {
            var standing = WatchableIndexUtil.getIndex(
                    watchableObjects,
                    WatchableIndexUtil.polarBearStanding(
                            player.user.getCultConnection().getObservedProtocol()));
            if (standing != null && standing.value() instanceof Boolean value) bear.onStanding(value, provenAt);
        }
        if (entity instanceof PacketEntityRideable) {
            applyRideableMetadata(entity, watchableObjects, layout);
        }
        if (entity instanceof PacketEntityHorse) {
            applyHorseMetadata(entity, watchableObjects, layout);
        }
        if (entity instanceof PacketEntityHappyGhast happyGhast) {
            applyHappyGhastMetadata(happyGhast, watchableObjects, layout);
        }
        if (entity instanceof PacketEntityNautilus nautilus) {
            EntityMetadata.Entry dashData = WatchableIndexUtil.getIndex(watchableObjects, layout.nautilusDash());
            if (dashData != null && dashData.value() instanceof Boolean dashing) {
                nautilus.setDashingFromMetadata(dashing);
            }
            EntityMetadata.Entry tameData = WatchableIndexUtil.getIndex(
                    watchableObjects,
                    WatchableIndexUtil.tameableFlags(
                            player.user.getCultConnection().getObservedProtocol()));
            if (tameData != null && tameData.value() instanceof Byte tameFlags) {
                nautilus.isTame = (tameFlags & 0x04) != 0;
            }
        }
        applyGravityMetadata(entity, watchableObjects);

        if (entity.type == EntityTypeIds.FIREWORK_ROCKET) {
            EntityMetadata.Entry fireworkData =
                    WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.FIREWORK_ATTACHED_TO_TARGET);
            if (fireworkData == null) return;
            trackFireworkAttachment(entityID, fireworkData.value(), provenAt);
        }

        if (entity instanceof PacketEntityHook) {
            EntityMetadata.Entry hookedData =
                    WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.FISHING_HOOKED_ENTITY);
            if (!(hookedData != null && hookedData.value() instanceof Integer hooked)) return;
            ((PacketEntityHook) entity).attached = hooked - 1; // the server adds 1 to the ID
        }

        if (entity instanceof PacketEntityRideable) {
            applyNoAiMetadata(entity, watchableObjects);
        }
    }

    private void applyAgeableMetadata(PacketEntity entity, List<EntityMetadata.Entry> watchableObjects) {
        if (!entity.isAgeable()) return;

        EntityMetadata.Entry babyData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.AGEABLE_BABY);
        if (babyData == null) return;

        Object value = babyData.value();
        // Required because bukkit Ageable doesn't align with minecraft's ageable
        if (value instanceof Boolean) {
            entity.isBaby = (boolean) value;
        } else if (value instanceof Byte) {
            entity.isBaby = ((Byte) value) < 0;
        }
    }

    private void applySizeMetadata(
            PacketEntity entity, List<EntityMetadata.Entry> watchableObjects, WatchableIndexUtil.Layout layout) {
        if (!entity.isSize()) return;

        int sizeIndex = entity.type == EntityTypeIds.PHANTOM ? WatchableIndexUtil.PHANTOM_SIZE : layout.slimeSize();
        EntityMetadata.Entry sizeData = WatchableIndexUtil.getIndex(watchableObjects, sizeIndex);
        if (sizeData == null) return;

        Object value = sizeData.value();
        if (value instanceof Integer) {
            ((PacketEntitySizeable) entity).size = (int) value;
        } else if (value instanceof Byte) {
            ((PacketEntitySizeable) entity).size = (byte) value;
        }
        // AbstractCubeMob#onSyncedDataUpdated and Phantom#onSyncedDataUpdated refresh dimensions on ID_SIZE.
        entity.refreshDimensions(player);
    }

    private void applyShulkerMetadata(PacketEntity entity, List<EntityMetadata.Entry> watchableObjects, int provenAt) {
        EntityMetadata.Entry attachFaceData =
                WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.SHULKER_ATTACH_FACE);
        if (attachFaceData != null) {
            // This NMS -> Bukkit conversion is great and works in all 11 versions.
            ((PacketEntityShulker) entity).facing =
                    Direction.valueOf(attachFaceData.value().toString().toUpperCase());
        }

        EntityMetadata.Entry peekData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.SHULKER_PEEK);
        if (peekData == null) return;

        PacketEntityShulker shulker = (PacketEntityShulker) entity;
        shulker.onPeek((byte) peekData.value(), provenAt);
        if (shulker.isAnimating()) player.compensatedWorld.openShulkerBoxes.add(new ShulkerData(shulker));
    }

    private void applyRideableMetadata(
            PacketEntity entity, List<EntityMetadata.Entry> watchableObjects, WatchableIndexUtil.Layout layout) {
        int saddleIndex = entity.type == EntityTypeIds.PIG ? layout.pigSaddle() : layout.striderSaddle();
        EntityMetadata.Entry saddleData = WatchableIndexUtil.getIndex(watchableObjects, saddleIndex);
        if (saddleData != null) {
            ((PacketEntityRideable) entity).hasSaddle = (boolean) saddleData.value();
        }
        if (entity.type == EntityTypeIds.PIG) {
            EntityMetadata.Entry boostData = WatchableIndexUtil.getIndex(watchableObjects, layout.pigBoostTime());
            if (boostData != null && boostData.value() instanceof Integer boost) {
                ((PacketEntityRideable) entity).boost.onSynced(boost);
            }
            return;
        }

        if (entity instanceof PacketEntityStrider) {
            EntityMetadata.Entry boostData = WatchableIndexUtil.getIndex(watchableObjects, layout.striderBoostTime());
            if (boostData != null && boostData.value() instanceof Integer boost) {
                ((PacketEntityRideable) entity).boost.onSynced(boost);
            }
        }
    }

    private void applyHorseMetadata(
            PacketEntity entity, List<EntityMetadata.Entry> watchableObjects, WatchableIndexUtil.Layout layout) {
        EntityMetadata.Entry flagsData = WatchableIndexUtil.getIndex(watchableObjects, layout.horseFlags());
        if (flagsData != null && flagsData.value() instanceof Byte flags) {
            PacketEntityHorse horse = (PacketEntityHorse) entity;
            horse.isTame = (flags & 0x02) != 0;
            // Through 1.21.4, AbstractHorse#isSaddled reads flag 0x04 from
            // DATA_ID_FLAGS. Newer clients read EquipmentSlot.SADDLE instead.
            if (!layout.saddleEquipment()) {
                boolean controlled = vehicles.canClientAuthoritativelyMoveVisibleRoot(horse);
                horse.hasSaddle = (flags & 0x04) != 0;
                vehicles.markSaddleControlSwitch(horse, controlled);
            }
            horse.setRearingFromMetadata((flags & 0x20) != 0);
        }

        if (entity instanceof PacketEntityCamel camel) {
            EntityMetadata.Entry dashData = WatchableIndexUtil.getIndex(watchableObjects, layout.camelDash());
            if (dashData != null && dashData.value() instanceof Boolean dashing) {
                camel.setDashingFromMetadata(dashing);
            }
            // Camel defines LAST_POSE_CHANGE_TICK directly after DASH.
            EntityMetadata.Entry poseTickData = WatchableIndexUtil.getIndex(watchableObjects, layout.camelDash() + 1);
            if (poseTickData != null && poseTickData.value() instanceof Number poseTick) {
                camel.lastPoseChangeTick = poseTick.longValue();
            }
        }
    }

    private void applyHappyGhastMetadata(
            PacketEntityHappyGhast happyGhast,
            List<EntityMetadata.Entry> watchableObjects,
            WatchableIndexUtil.Layout layout) {
        EntityMetadata.Entry staysStillData =
                WatchableIndexUtil.getIndex(watchableObjects, layout.happyGhastStaysStill());
        if (staysStillData != null && staysStillData.value() instanceof Boolean staysStill) {
            happyGhast.staysStill = staysStill;
        }
    }

    private void applyGravityMetadata(PacketEntity entity, List<EntityMetadata.Entry> watchableObjects) {
        EntityMetadata.Entry gravityData =
                WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.ENTITY_NO_GRAVITY);
        if (gravityData != null && gravityData.value() instanceof Boolean noGravity) {
            // Vanilla uses hasNoGravity, which is a bad name IMO
            // hasGravity > hasNoGravity
            entity.hasGravity = !noGravity;
        }
    }

    private void trackFireworkAttachment(int entityID, Object rawValue, int provenAt) {
        OptionalInt target;
        if (rawValue instanceof OptionalInt optionalInt) {
            target = optionalInt;
        } else if (rawValue instanceof Optional<?> optional && optional.isEmpty()) {
            target = OptionalInt.empty();
        } else if (rawValue instanceof Optional<?> optional && optional.get() instanceof Integer referencedEntity) {
            target = OptionalInt.of(referencedEntity);
        } else {
            return;
        }
        player.compensatedFireworks.attachmentChanged(entityID, target, provenAt);
    }

    private void applyNoAiMetadata(PacketEntity entity, List<EntityMetadata.Entry> watchableObjects) {
        EntityMetadata.Entry mobFlags = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.MOB_FLAGS);
        if (mobFlags != null) {
            entity.noAI = ((byte) mobFlags.value() & 0x01) != 0;
        }
    }
}
