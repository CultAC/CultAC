package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityArmorStand;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySizeable;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;

/** LivingEntity#getDimensions and the concrete entity's getDefaultDimensions. */
final class CameraEyeHeight {
    private CameraEyeHeight() {}

    static float of(PacketEntity entity, ClientVersion version) {
        if (entity.actionPose == EntityPose.SLEEPING && EntityTypeUtil.isLiving(entity.type)) return .2F;
        String type = EntityTypeUtil.getKey(entity.type).getPath();
        float height = EntityTypeUtil.modelType(entity.type).eyeHeight();
        if (type.equals("player")) {
            if (entity.actionPose == EntityPose.DYING) return 1.62F;
            height = switch (entity.actionPose) {
                case CROUCHING -> 1.27F;
                case FALL_FLYING, SWIMMING, SPIN_ATTACK -> .4F;
                default -> 1.62F;
            };
        } else if (entity instanceof PacketEntityArmorStand stand) {
            var shape = stand.actionBounds(new SimpleCollisionBox(0, 0, 0, 0, 0, 0));
            if (stand.actionMarker()) height = 0;
            else if (shape.maxY() < EntityTypeUtil.modelType(entity.type).height() * entity.scale) height = .9875F;
        } else if (entity instanceof PacketEntitySizeable sizeable) {
            if (type.equals("slime") || type.equals("magma_cube")) height *= sizeable.size;
            else if (type.equals("phantom")) height *= 1.0F + .15F * sizeable.size;
        } else if (entity.isBaby) {
            height = baby(type, height, entity.actionPose, version);
        } else if ((type.equals("camel") || type.equals("camel_husk")) && entity.actionPose == EntityPose.SITTING) {
            height = .845F;
        }
        // EntityDimensions.scale multiplies floats, not doubles.
        return height * entity.scale;
    }

    private static float baby(String type, float adult, EntityPose pose, ClientVersion version) {
        if (version.isOlderThan(ClientVersion.V_26_1)) {
            return adult * (type.equals("turtle") ? .3F : .5F);
        }
        // 26.2 concrete BABY_DIMENSIONS carry independent eye heights; using half
        // the adult eye height is incorrect even where body dimensions halve.
        return switch (type) {
            case "cow", "mooshroom" -> version.isNewerThanOrEquals(ClientVersion.V_26_2) ? .69F : .665F;
            case "strider" -> .4375F;
            case "cat", "ocelot", "wolf", "fox", "polar_bear" -> .34375F;
            case "dolphin", "axolotl" -> .09375F;
            case "armadillo" -> .21875F;
            case "chicken", "panda" -> .28125F;
            case "squid", "glow_squid" -> .37F;
            case "sheep" -> .65625F;
            case "rabbit" -> .39F;
            case "pig" -> .40625F;
            case "goat" -> .59375F;
            case "happy_ghast" -> .46875F;
            case "villager" -> .63F;
            case "husk" -> .825F;
            case "zombie", "drowned" -> .775F;
            case "piglin", "zombified_piglin" -> .78F;
            case "zombie_villager" -> .67F;
            case "hoglin", "zoglin" -> .625F;
            case "turtle" -> adult * .3F;
            case "horse", "skeleton_horse", "zombie_horse", "donkey", "mule" -> adult * .7F;
            case "camel", "camel_husk" -> pose == EntityPose.SITTING ? .41F : 1.38F;
            default -> adult * .5F;
        };
    }
}
