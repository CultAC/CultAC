package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.data.packetentity.PacketEntityHorse;
import ac.grim.grimac.utils.data.packetentity.PacketEntitySizeable;
import ac.grim.grimac.utils.data.packetentity.PacketEntityStrider;
import ac.grim.grimac.utils.data.packetentity.PacketEntityTrackXRot;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.Vec3;

/**
 * Yeah, I know this is a bad class
 * I just can't figure out how to PR it to PacketEvents due to babies, slimes, and other irregularities
 * <p>
 * I could PR a ton of classes in order to accomplish it but then no one would use it
 * (And even if they did they would likely be breaking my license...)
 */
public class BoundingBoxSize {

    public static float getWidth(PacketEntity packetEntity) {
        // Turtles are the only baby animal that don't follow the * 0.5 rule
        if (packetEntity.type == EntityTypesCompat.TURTLE && packetEntity.isBaby) return 0.36f;
        return getWidthMinusBaby(packetEntity) * (packetEntity.isBaby ? 0.5f : 1f) * packetEntity.scale;
    }

    public static float getWidth(GrimPlayer player, PacketEntity packetEntity) {
        return getWidth(packetEntity);
    }

    public static float getReachWidth(GrimPlayer player, PacketEntity packetEntity) {
        return getWidth(player, packetEntity);
    }


    private static float getWidthMinusBaby(PacketEntity packetEntity) {
        if (packetEntity instanceof PacketEntitySizeable sizeable) {
            if (EntityTypesCompat.MAGMA_CUBE.equals(packetEntity.type) || EntityTypesCompat.SLIME.equals(packetEntity.type)) {
                return 2.04f * (0.255f * (float) sizeable.size);
            }
            if (EntityTypesCompat.PHANTOM.equals(packetEntity.type)) {
                return 0.9f + sizeable.size * 0.2f;
            }
        }

        float exactWidth = baseDimensions(packetEntity).width();
        return Float.isNaN(exactWidth) ? 0.6f : exactWidth;
    }

    public static Vec3 getRidingOffsetFromVehicle(PacketEntity entity, GrimPlayer player) {
        return getRidingOffsetFromVehicle(entity, player.compensatedEntities.getSelf(), player);
    }

    public static Vec3 getRidingOffsetFromVehicle(PacketEntity entity, PacketEntity passenger, GrimPlayer player) {
        SimpleCollisionBox box = entity.getPossibleMovementCollisionBoxes();
        return getRidingOffsetFromVehicle(entity, passenger, player, box);
    }

    public static Vec3 getRidingOffsetFromVehicle(PacketEntity entity, PacketEntity passenger, GrimPlayer player, SimpleCollisionBox box) {
        return getRidingOffsetFromVehicle(entity, passenger, player, box, entity.passengers.indexOf(passenger), entity.passengers.size());
    }

    public static Vec3 getRidingOffsetFromVehicle(PacketEntity entity, PacketEntity passenger, GrimPlayer player, SimpleCollisionBox box, int passengerIndex) {
        return getRidingOffsetFromVehicle(entity, passenger, player, box, passengerIndex, entity.passengers.size());
    }

    public static Vec3 getRidingOffsetFromVehicle(PacketEntity entity, PacketEntity passenger, GrimPlayer player, SimpleCollisionBox box, int passengerIndex, int passengerCount) {
        double x = (box.maxX + box.minX) / 2.0;
        double y = box.minY;
        double z = (box.maxZ + box.minZ) / 2.0;

        if (entity instanceof PacketEntityTrackXRot) {
            PacketEntityTrackXRot xRotEntity = (PacketEntityTrackXRot) entity;

            if (EntityTypeUtil.isBoat(entity.type)) {
                Vec3 offset = getBoatPassengerAttachmentOffset(entity, passenger, xRotEntity.interpYaw, passengerIndex, passengerCount);
                return new Vec3(x + offset.x, y + offset.y, z + offset.z);
            }
        }

        Vec3 offset = getDefaultPassengerAttachmentOffset(entity, passenger, passengerIndex);
        return new Vec3(x + offset.x, y + offset.y, z + offset.z);
    }

    private static Vec3 getDefaultPassengerAttachmentOffset(PacketEntity vehicle, PacketEntity passenger, int passengerIndex) {
        int index = Math.max(passengerIndex, 0);
        float vehicleYaw = vehicle instanceof PacketEntityTrackXRot xRotEntity ? xRotEntity.interpYaw : 0.0F;
        float passengerYaw = passenger instanceof PacketEntityTrackXRot xRotEntity ? xRotEntity.interpYaw : 0.0F;
        Vec3 passengerAttachment = baseDimensions(vehicle).attachments().getClamped(EntityAttachment.PASSENGER, index, vehicleYaw);
        if (vehicle instanceof PacketEntityHorse horse) {
            // MCP-Reborn AbstractHorse#getPassengerAttachmentPoint adds this rearing offset to the default point.
            passengerAttachment = passengerAttachment.add(new Vec3(0.0D, 0.15D * horse.standAnimO, -0.7D * horse.standAnimO)
                    .yRot(-vehicleYaw * ((float) Math.PI / 180F)));
        } else if (vehicle instanceof PacketEntityStrider strider) {
            // MCP-Reborn Strider#getPassengerAttachmentPoint adds a client-only
            // walk-animation Y offset before passenger positioning.
            passengerAttachment = passengerAttachment.add(0.0D, strider.getPassengerAttachmentYOffset(), 0.0D);
        }
        Vec3 vehicleAttachment = baseDimensions(passenger).attachments().get(EntityAttachment.VEHICLE, 0, passengerYaw);
        return passengerAttachment.subtract(vehicleAttachment);
    }

    private static Vec3 getBoatPassengerAttachmentOffset(PacketEntity vehicle, PacketEntity passenger, float vehicleYaw, int passengerIndex, int passengerCount) {
        int index = Math.max(passengerIndex, 0);
        float zOffset = getSingleBoatPassengerOffset(vehicle);

        if (passengerCount > 1) {
            zOffset = index == 0 ? 0.2F : -0.6F;
            if (passenger.isAnimal()) {
                zOffset += 0.2F;
            }
        }

        double rideHeight = isRaft(vehicle) ? getHeight(vehicle) * 0.8888889F : getHeight(vehicle) / 3.0F;
        Vec3 passengerAttachment = new Vec3(0.0D, rideHeight, zOffset).yRot(-vehicleYaw * ((float) Math.PI / 180F));
        float passengerYaw = passenger instanceof PacketEntityTrackXRot xRotEntity ? xRotEntity.interpYaw : 0.0F;
        Vec3 vehicleAttachment = baseDimensions(passenger).attachments().get(EntityAttachment.VEHICLE, 0, passengerYaw);
        return passengerAttachment.subtract(vehicleAttachment);
    }

    private static float getSingleBoatPassengerOffset(PacketEntity vehicle) {
        String path = EntityTypeUtil.getKey(vehicle.type).getPath();
        return path.endsWith("_chest_boat") || path.endsWith("_chest_raft") ? 0.15F : 0.0F;
    }

    private static boolean isRaft(PacketEntity vehicle) {
        String path = EntityTypeUtil.getKey(vehicle.type).getPath();
        return path.endsWith("_raft") || path.endsWith("_chest_raft");
    }

    public static float getHeight(GrimPlayer player, PacketEntity packetEntity) {
        return getHeight(packetEntity);
    }

    public static float getReachHeight(GrimPlayer player, PacketEntity packetEntity) {
        return getHeight(player, packetEntity);
    }

    public static float getHeight(PacketEntity packetEntity) {
        // Turtles are the only baby animal that don't follow the * 0.5 rule
        if (packetEntity.type == EntityTypesCompat.TURTLE && packetEntity.isBaby) return 0.12f;
        return getHeightMinusBaby(packetEntity) * (packetEntity.isBaby ? 0.5f : 1f) * packetEntity.scale;
    }

    public static double getMyRidingOffset(PacketEntity packetEntity) {
        // Attachment points require virtual passenger pairs to preserve animated offsets.
        if (EntityTypesCompat.PIGLIN.equals(packetEntity.type) || EntityTypesCompat.ZOMBIFIED_PIGLIN.equals(packetEntity.type) || EntityTypesCompat.ZOMBIE.equals(packetEntity.type)) {
            return packetEntity.isBaby ? -0.05 : -0.45;
        } else if (EntityTypesCompat.SKELETON.equals(packetEntity.type)) {
            return -0.6;
        } else if (EntityTypesCompat.ENDERMITE.equals(packetEntity.type) || EntityTypesCompat.SILVERFISH.equals(packetEntity.type)) {
            return 0.1;
        } else if (EntityTypesCompat.EVOKER.equals(packetEntity.type) || EntityTypesCompat.ILLUSIONER.equals(packetEntity.type) || EntityTypesCompat.PILLAGER.equals(packetEntity.type) || EntityTypesCompat.RAVAGER.equals(packetEntity.type) || EntityTypesCompat.VINDICATOR.equals(packetEntity.type) || EntityTypesCompat.WITCH.equals(packetEntity.type)) {
            return -0.45;
        } else if (EntityTypesCompat.PLAYER.equals(packetEntity.type)) {
            return -0.35;
        }

        if (EntityTypeUtil.isAnimal(packetEntity.type)) {
            return 0.14;
        }

        return 0;
    }

    public static double getPassengerRidingOffset(GrimPlayer player, PacketEntity packetEntity) {
        if (packetEntity instanceof PacketEntityHorse)
            return (getHeight(player, packetEntity) * 0.75) - 0.25;

        if (EntityTypeUtil.isMinecart(packetEntity.type)) {
            return 0;
        } else if (EntityTypeUtil.isBoat(packetEntity.type)) {
            return -0.1;
        } else if (EntityTypesCompat.HOGLIN.equals(packetEntity.type) || EntityTypesCompat.ZOGLIN.equals(packetEntity.type)) {
            return getHeight(player, packetEntity) - (packetEntity.isBaby ? 0.2 : 0.15);
        } else if (EntityTypesCompat.LLAMA.equals(packetEntity.type)) {
            return getHeight(player, packetEntity) * 0.67;
        } else if (EntityTypesCompat.PIGLIN.equals(packetEntity.type)) {
            return getHeight(player, packetEntity) * 0.92;
        } else if (EntityTypesCompat.RAVAGER.equals(packetEntity.type)) {
            return 2.1;
        } else if (EntityTypesCompat.SKELETON.equals(packetEntity.type)) {
            return (getHeight(player, packetEntity) * 0.75) - 0.1875;
        } else if (EntityTypesCompat.SPIDER.equals(packetEntity.type)) {
            return getHeight(player, packetEntity) * 0.5;
        } else if (EntityTypesCompat.STRIDER.equals(packetEntity.type)) {// depends on animation position, good luck getting it exactly, this is the best you can do though
            return getHeight(player, packetEntity) - 0.19;
        }
        return getHeight(player, packetEntity) * 0.75;
    }

    private static float getHeightMinusBaby(PacketEntity packetEntity) {
        if (packetEntity instanceof PacketEntitySizeable sizeable) {
            if (EntityTypesCompat.MAGMA_CUBE.equals(packetEntity.type) || EntityTypesCompat.SLIME.equals(packetEntity.type)) {
                return 2.04f * (0.255f * (float) sizeable.size);
            }
        }

        float exactHeight = baseDimensions(packetEntity).height();
        return Float.isNaN(exactHeight) ? 1.95f : exactHeight;
    }

    private static EntityDimensions baseDimensions(PacketEntity packetEntity) {
        return packetEntity.type.getDimensions();
    }
}
