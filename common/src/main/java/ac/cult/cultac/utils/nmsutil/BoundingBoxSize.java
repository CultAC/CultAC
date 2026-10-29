package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties;
import ac.cult.cultac.bedrock.prediction.state.BedrockHorseProperties;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityInteraction;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySizeable;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.data.packetentity.PacketEntityTrackXRot;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;

public class BoundingBoxSize {

    public static float getWidth(CultPlayer player, PacketEntity packetEntity) {
        return getWidth(packetEntity, player.getClientVersion(), player.isBedrockMovement());
    }

    public static float getWidth(PacketEntity packetEntity, ClientVersion version, boolean bedrock) {
        if (bedrock && packetEntity.isBoat()) {
            return (float)
                    (packetEntity.bedrockBoat == null
                            ? BedrockBoatProperties.DIMENSIONS.width()
                            : packetEntity.bedrockBoat.dimensions().width());
        }
        if (bedrock && BedrockHorseProperties.supports(packetEntity.type)) {
            return 1.3965F * ((packetEntity.isBaby ? 0.5F : 1.0F) * packetEntity.scale);
        }
        return width(packetEntity, version, bedrock);
    }

    public static float getReachWidth(CultPlayer player, PacketEntity packetEntity) {
        return getWidth(player, packetEntity);
    }

    private static float getWidthMinusBaby(PacketEntity packetEntity, ClientVersion version, boolean bedrock) {
        // Interaction#getDimensions: EntityDimensions.scalable(DATA_WIDTH_ID, DATA_HEIGHT_ID).
        if (packetEntity instanceof PacketEntityInteraction interaction) return interaction.actionWidth;
        if (packetEntity instanceof PacketEntitySizeable sizeable) {
            int size = size(sizeable, bedrock);
            if ((packetEntity.type == EntityTypeIds.MAGMA_CUBE) || (packetEntity.type == EntityTypeIds.SLIME)) {
                return scalesCubeMobType(version, bedrock)
                        ? baseDimensions(packetEntity).width() * size
                        : 2.04f * (0.255f * (float) size);
            }
            if ((packetEntity.type == EntityTypeIds.PHANTOM)) {
                // Phantom#getDefaultDimensions scales by 1 + 0.15 * size from 1.20.2; Bedrock keeps the older width.
                return !bedrock && version.isNewerThanOrEquals(ClientVersion.V_1_20_2)
                        ? 0.9f * (1.0f + 0.15f * size)
                        : 0.9f + size * 0.2f;
            }
        }

        String path = EntityTypeUtil.getKey(packetEntity.type).getPath();
        if (version.isOlderThan(ClientVersion.V_26_1) && path.equals("rabbit")) {
            return 0.4F;
        }
        float exactWidth = baseDimensions(packetEntity).width();
        if (packetEntity instanceof ac.cult.cultac.utils.data.packetentity.PacketEntityPufferfish fish)
            exactWidth *= fish.puffScale();
        return Float.isNaN(exactWidth) ? 0.6f : exactWidth;
    }

    public static float getWidth(PacketEntity entity, ClientVersion version) {
        return width(entity, version, false);
    }

    private static float width(PacketEntity entity, ClientVersion version, boolean bedrock) {
        float width = getWidthMinusBaby(entity, version, bedrock);
        if (entity.isBaby) {
            Float independent = independentBabyWidth(entity, version);
            width = independent == null ? width * getBabyScale(entity, version) : independent;
        }
        // AABB orders the corners, so a negative width spans the same box.
        return Math.abs(width) * entity.scale;
    }

    public static Vec3 getRidingOffsetFromVehicle(PacketEntity entity, CultPlayer player) {
        return getRidingOffsetFromVehicle(entity, player.compensatedEntities.getSelf(), player);
    }

    public static Vec3 getRidingOffsetFromVehicle(PacketEntity entity, PacketEntity passenger, CultPlayer player) {
        SimpleCollisionBox box = entity.getPossibleMovementCollisionBoxes();
        return getRidingOffsetFromVehicle(entity, passenger, player, box);
    }

    public static Vec3 getRidingOffsetFromVehicle(
            PacketEntity entity, PacketEntity passenger, CultPlayer player, SimpleCollisionBox box) {
        return getRidingOffsetFromVehicle(
                entity, passenger, player, box, entity.passengers.indexOf(passenger), entity.passengers.size());
    }

    public static Vec3 getRidingOffsetFromVehicle(
            PacketEntity entity,
            PacketEntity passenger,
            CultPlayer player,
            SimpleCollisionBox box,
            int passengerIndex,
            int passengerCount) {
        SimpleCollisionBox point = ridingPosition(
                entity, passenger, player, box, passengerIndex, passengerCount, new SimpleCollisionBox());
        return new Vec3(point.minX, point.minY, point.minZ);
    }

    /** Entity#positionRider's position in out.minX/minY/minZ; no temporary vectors or boxes. */
    public static SimpleCollisionBox ridingPosition(
            PacketEntity entity,
            PacketEntity passenger,
            CultPlayer player,
            SimpleCollisionBox box,
            int passengerIndex,
            int passengerCount,
            SimpleCollisionBox out) {
        double x = (box.maxX + box.minX) / 2.0;
        double y = box.minY;
        double z = (box.maxZ + box.minZ) / 2.0;
        if (entity instanceof PacketEntityTrackXRot rot && EntityTypeUtil.isBoat(entity.type)) {
            float offset = getSingleBoatPassengerOffset(entity);
            if (passengerCount > 1) {
                offset = Math.max(passengerIndex, 0) == 0 ? 0.2F : -0.6F;
                if (passenger.isAnimal()) offset += 0.2F;
            }
            double height = isRaft(entity) ? getHeight(entity) * 0.8888889F : getHeight(entity) / 3.0F;
            rotatePoint(out, 0.0, height, offset,
                    player.isBedrockMovement() ? rot.interpYaw : getAttachmentYaw(entity, player));
        } else if (entity instanceof PacketEntityCamel camel) {
            boolean driver = Math.max(passengerIndex, 0) == 0;
            float scale = camel.scale * (camel.isBaby ? getBabyScale(camel, player.getClientVersion()) : 1.0F);
            float offset = 0.5F;
            if (passengerCount > 1) {
                if (!driver) offset = -0.7F;
                if (passenger.isAnimal()) offset += 0.2F;
            }
            long poseTime = camel.poseTime(player.compensatedWorld.clientGameTime() - 1);
            float height = (float) getCamelBodyAnchorYOffset(camel, player.getClientVersion(), driver, scale, poseTime);
            rotatePoint(out, 0.0, height, offset * scale, getAttachmentYaw(camel, player));
        } else {
            EntityTypes.Type type = baseDimensions(entity);
            var points = type.passengers();
            var point = points.get(Math.clamp(passengerIndex, 0, points.size() - 1));
            double px = point.x(), py = point.y(), pz = point.z();
            float dimensions = defaultDimensionsScale(entity, player.getClientVersion(), player.isBedrockMovement());
            if (!type.fixed() && dimensions != 1.0F) {
                px *= dimensions;
                py *= dimensions;
                pz *= dimensions;
            }
            if (!type.fixed() && entity.scale != 1.0F) {
                px *= entity.scale;
                py *= entity.scale;
                pz *= entity.scale;
            }
            // EntityAttachments uses (float) (PI / 180), unlike the dynamic seat overrides.
            rotateAttachment(out, px, py, pz, getAttachmentYaw(entity, player));
            if (entity.isAvatar() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)) {
                out.minY = entity == player.compensatedEntities.getSelf()
                        ? (player.pose == ac.cult.cultac.utils.enums.Pose.SLEEPING
                                        || player.pose == ac.cult.cultac.utils.enums.Pose.DYING
                                ? .2F
                                : player.pose.height * entity.scale)
                        : PacketEntity.avatarHeight(entity.actionPose, entity.scale);
            }
            if (entity instanceof PacketEntityHorse horse) {
                double sx = out.minX, sy = out.minY, sz = out.minZ;
                // AbstractHorse#tick writes standAnimO before positionRider reads the attachment.
                float stand = attachmentAfterTick(horse, player) ? horse.standAnim : horse.standAnimO;
                rotatePoint(
                        out,
                        0.0,
                        0.15D * stand * entity.scale,
                        -0.7D * stand * entity.scale,
                        getAttachmentYaw(entity, player));
                out.minX += sx;
                out.minY += sy;
                out.minZ += sz;
            } else if (entity instanceof PacketEntityStrider strider) {
                out.minY += (float) strider.getPassengerAttachmentYOffset() * entity.scale;
            }
        }
        x += out.minX;
        y += out.minY;
        z += out.minZ;
        EntityTypes.Type riderType = baseDimensions(passenger);
        var attachment = riderType.vehicle().getFirst();
        double ax = attachment.x(), ay = attachment.y(), az = attachment.z();
        if (!riderType.fixed() && passenger.scale != 1.0F) {
            ax *= passenger.scale;
            ay *= passenger.scale;
            az *= passenger.scale;
        }
        rotateAttachment(out, ax, ay, az, getAttachmentYaw(passenger, player));
        out.minX = x - out.minX;
        out.minY = y - out.minY;
        out.minZ = z - out.minZ;
        return out;
    }

    private static void rotateAttachment(SimpleCollisionBox out, double x, double y, double z, float yaw) {
        rotateRadians(out, x, y, z, -yaw * (float) (Math.PI / 180.0));
    }

    private static void rotatePoint(SimpleCollisionBox out, double x, double y, double z, float yaw) {
        rotateRadians(out, x, y, z, -yaw * ((float) Math.PI / 180F));
    }

    private static void rotateRadians(SimpleCollisionBox out, double x, double y, double z, float radians) {
        float cos = ac.cult.blocksim.engine.VanillaMath.cos(radians);
        float sin = ac.cult.blocksim.engine.VanillaMath.sin(radians);
        out.minX = x * cos + z * sin;
        out.minY = y;
        out.minZ = z * cos - x * sin;
    }

    /**
     * The entity's yRot as the client last positioned its riders. ClientPacketListener#handleMoveEntity skips rotation
     * for an entity whose Entity#isLocalInstanceAuthoritative holds, so the vehicle the local player controls keeps the
     * yaw its own tickRidden set, which its ServerboundMoveVehicle reported; any other entity interpolates the packets'.
     */
    private static float getAttachmentYaw(PacketEntity entity, CultPlayer player) {
        if (player.compensatedEntities.vehicles.canClientEchoLocalAuthoritativeMoveVehiclePacket(entity)) {
            return entity.clientPhysicalYaw;
        }
        if (!(entity instanceof PacketEntityTrackXRot rotated)) return entity.clientPhysicalYaw;
        // A movement reader may ask after commonTick before the end-of-tick traversal has committed it.
        return rotated.steps > 0 && attachmentAfterTick(entity, player)
                ? rotated.interpYaw + CultMath.wrapDegrees(rotated.packetYaw - rotated.interpYaw) / rotated.steps
                : rotated.interpYaw;
    }

    private static boolean attachmentAfterTick(PacketEntity entity, CultPlayer player) {
        var entities = player.compensatedEntities;
        return !player.isBedrockMovement()
                && entities.readingMovementPositions()
                && entities.clientTickOrder(entity) <= entities.tickedThroughOrder()
                && !entity.tickedThisTick(player)
                && !entity.lazyRiderTickedThisTick()
                && entities.ticksInClientTick(entity);
    }

    // MCP-Reborn 26.3 Camel#getBodyAnchorAnimationYOffset at partialTicks 0.
    private static double getCamelBodyAnchorYOffset(
            PacketEntityCamel camel, ClientVersion version, boolean isFront, float scale, long poseTime) {
        double ageSitYOffset = camel.isBaby ? 0.09375 : 0.375;
        double baseSitOffset = getCamelPoseHeight(camel, version) - ageSitYOffset;
        float sittingHeightDifference = scale * 1.43F;
        float verticalDrop = sittingHeightDifference - scale * 0.2F;
        float bottomPoint = sittingHeightDifference - verticalDrop;
        boolean isSitting = camel.isCamelSitting();
        boolean isInTransition = poseTime < (isSitting ? 40 : 52);
        if (isInTransition) {
            int animationDuration = isSitting ? 40 : 52;
            int halfPoint;
            float flexPointOffset;
            if (isSitting) {
                halfPoint = 28;
                flexPointOffset = isFront ? 0.5F : 0.1F;
            } else {
                halfPoint = isFront ? 24 : 32;
                flexPointOffset = isFront ? 0.6F : 0.35F;
            }

            float time = Math.clamp((float) poseTime, 0.0F, (float) animationDuration);
            boolean isFirstPart = time < halfPoint;
            float part = isFirstPart ? time / halfPoint : (time - halfPoint) / (animationDuration - halfPoint);
            float flexPoint = sittingHeightDifference - flexPointOffset * verticalDrop;
            baseSitOffset += isSitting
                    ? CultMath.lerp(
                            part,
                            isFirstPart ? sittingHeightDifference : flexPoint,
                            isFirstPart ? flexPoint : bottomPoint)
                    : CultMath.lerp(
                            part,
                            isFirstPart ? bottomPoint - sittingHeightDifference : bottomPoint - flexPoint,
                            isFirstPart ? bottomPoint - flexPoint : 0.0F);
        }

        if (isSitting && !isInTransition) {
            baseSitOffset += bottomPoint;
        }
        return baseSitOffset;
    }

    /** The camel's getDimensions(getPose()).height(): Camel#getDefaultDimensions swaps in sitting dimensions. */
    public static float getCamelPoseHeight(PacketEntityCamel camel, ClientVersion version) {
        if (camel.actionPose != EntityPose.SITTING) {
            return getHeight(camel, version);
        }
        float adultSitting = baseDimensions(camel).height() - 1.43F;
        if (!camel.isBaby) {
            return adultSitting * camel.scale;
        }
        float babySitting = version.isNewerThanOrEquals(ClientVersion.V_26_2)
                ? 0.425F
                : adultSitting * getBabyScale(camel, version);
        return babySitting * camel.scale;
    }

    private static float getSingleBoatPassengerOffset(PacketEntity vehicle) {
        String path = EntityTypeUtil.getKey(vehicle.type).getPath();
        return path.endsWith("_chest_boat") || path.endsWith("_chest_raft") ? 0.15F : 0.0F;
    }

    private static boolean isRaft(PacketEntity vehicle) {
        String path = EntityTypeUtil.getKey(vehicle.type).getPath();
        return path.endsWith("_raft") || path.endsWith("_chest_raft");
    }

    public static float getHeight(CultPlayer player, PacketEntity packetEntity) {
        return getHeight(packetEntity, player.getClientVersion(), player.isBedrockMovement());
    }

    public static float getHeight(PacketEntity packetEntity, ClientVersion version, boolean bedrock) {
        if (bedrock && packetEntity.isBoat()) {
            return (float)
                    (packetEntity.bedrockBoat == null
                            ? BedrockBoatProperties.DIMENSIONS.height()
                            : packetEntity.bedrockBoat.dimensions().height());
        }
        if (bedrock && BedrockHorseProperties.supports(packetEntity.type)) {
            return 1.6F * ((packetEntity.isBaby ? 0.5F : 1.0F) * packetEntity.scale);
        }
        return height(packetEntity, version, bedrock);
    }

    public static float getReachHeight(CultPlayer player, PacketEntity packetEntity) {
        return getHeight(player, packetEntity);
    }

    public static float getHeight(PacketEntity packetEntity) {
        return getHeight(packetEntity, ClientVersion.V_26_2);
    }

    private static float getHeightMinusBaby(PacketEntity packetEntity, ClientVersion version, boolean bedrock) {
        if (packetEntity instanceof PacketEntityInteraction interaction) return interaction.actionHeight;
        if (packetEntity instanceof PacketEntitySizeable sizeable) {
            int size = size(sizeable, bedrock);
            if ((packetEntity.type == EntityTypeIds.MAGMA_CUBE) || (packetEntity.type == EntityTypeIds.SLIME)) {
                return scalesCubeMobType(version, bedrock)
                        ? baseDimensions(packetEntity).height() * size
                        : 2.04f * (0.255f * (float) size);
            }
            if (packetEntity.type == EntityTypeIds.PHANTOM
                    && !bedrock
                    && version.isNewerThanOrEquals(ClientVersion.V_1_20_2)) {
                return 0.5f * (1.0f + 0.15f * size);
            }
        }

        String path = EntityTypeUtil.getKey(packetEntity.type).getPath();
        if (version.isOlderThan(ClientVersion.V_26_1) && path.equals("rabbit")) {
            return 0.5F;
        }
        float exactHeight = baseDimensions(packetEntity).height();
        if (packetEntity instanceof ac.cult.cultac.utils.data.packetentity.PacketEntityPufferfish fish)
            exactHeight *= fish.puffScale();
        return Float.isNaN(exactHeight) ? 1.95f : exactHeight;
    }

    public static float getHeight(PacketEntity entity, ClientVersion version) {
        return height(entity, version, false);
    }

    private static float height(PacketEntity entity, ClientVersion version, boolean bedrock) {
        float height = getHeightMinusBaby(entity, version, bedrock);
        if (entity.isBaby) {
            Float independent = independentBabyHeight(entity, version);
            height = independent == null ? height * getBabyScale(entity, version) : independent;
        }
        return Math.abs(height) * entity.scale;
    }

    /** Whether the entity's height is negative, which {@code AABB} spans below its position. */
    public static boolean spansBelowPosition(CultPlayer player, PacketEntity entity) {
        return (entity instanceof PacketEntityInteraction || entity instanceof PacketEntitySizeable)
                && getHeightMinusBaby(entity, player.getClientVersion(), player.isBedrockMovement()) < 0;
    }

    /** From 1.20.5 AbstractCubeMob#getDefaultDimensions scales the type's 0.52 by the size. */
    private static boolean scalesCubeMobType(ClientVersion version, boolean bedrock) {
        return !bedrock && version.isNewerThanOrEquals(ClientVersion.V_1_20_5);
    }

    /**
     * The factor Phantom#getDefaultDimensions and AbstractCubeMob#getDefaultDimensions scale the type's dimensions,
     * attachments included, by; 1 for every other entity and for the sizings that do not scale attachments.
     */
    private static float defaultDimensionsScale(PacketEntity entity, ClientVersion version, boolean bedrock) {
        if (bedrock || !(entity instanceof PacketEntitySizeable sizeable)) return 1.0F;
        if (entity.type == EntityTypeIds.PHANTOM && version.isNewerThanOrEquals(ClientVersion.V_1_20_2)) {
            return 1.0F + 0.15F * sizeable.size;
        }
        if ((entity.type == EntityTypeIds.MAGMA_CUBE || entity.type == EntityTypeIds.SLIME)
                && scalesCubeMobType(version, false)) {
            return sizeable.size;
        }
        return 1.0F;
    }

    /** The size {@code getDefaultDimensions} scales by. Bedrock sizing keeps a floor of 1. */
    private static int size(PacketEntitySizeable sizeable, boolean bedrock) {
        return bedrock ? Math.max(sizeable.size, 1) : sizeable.size;
    }

    private static float getBabyScale(PacketEntity entity, ClientVersion version) {
        String path = EntityTypeUtil.getKey(entity.type).getPath();
        if (path.equals("turtle")) return 0.3F;
        if (path.equals("happy_ghast")) return 0.2375F;
        if (path.equals("dolphin")) return 0.65F;
        if (path.equals("armadillo")) return 0.6F;
        if (EntityTypeUtil.isCamelFamily(entity.type))
            return version.isNewerThanOrEquals(ClientVersion.V_26_1) ? 0.6F : 0.45F;
        if (version.isNewerThanOrEquals(ClientVersion.V_26_1)) {
            if (path.equals("goat")) return 0.55F;
            if (EntityTypeUtil.isHorseFamily(entity.type)) return 0.7F;
        }
        return 0.5F;
    }

    private static Float independentBabyWidth(PacketEntity entity, ClientVersion version) {
        if (version.isOlderThan(ClientVersion.V_26_1)) return null;
        String path = EntityTypeUtil.getKey(entity.type).getPath();
        if (version.isNewerThanOrEquals(ClientVersion.V_26_2)) {
            switch (path) {
                case "axolotl":
                    return 0.375F;
                case "hoglin", "zoglin":
                    return 0.75F;
                case "fox":
                    return 0.36F;
                case "goat":
                    return 0.45F;
                case "camel", "camel_husk":
                    return 0.95F;
            }
        }
        return switch (path) {
            case "axolotl", "squid", "glow_squid" -> 0.5F;
            case "chicken" -> 0.3F;
            case "rabbit" -> 0.24F;
            case "zombie",
                    "drowned",
                    "husk",
                    "zombie_villager",
                    "zombified_piglin",
                    "villager",
                    "piglin",
                    "piglin_brute" -> 0.49F;
            default -> null;
        };
    }

    private static Float independentBabyHeight(PacketEntity entity, ClientVersion version) {
        if (version.isOlderThan(ClientVersion.V_26_1)) return null;
        String path = EntityTypeUtil.getKey(entity.type).getPath();
        if (version.isNewerThanOrEquals(ClientVersion.V_26_2)) {
            switch (path) {
                case "axolotl":
                    return 0.21F;
                case "hoglin", "zoglin":
                    return 0.85F;
                case "fox":
                    return 0.42F;
                case "goat":
                    return 0.65F;
                case "camel", "camel_husk":
                    return 1.4F;
                case "zombie",
                        "drowned",
                        "husk",
                        "zombie_villager",
                        "zombified_piglin",
                        "villager",
                        "piglin",
                        "piglin_brute":
                    return 0.98F;
            }
        }
        return switch (path) {
            case "axolotl" -> 0.25F;
            case "chicken", "rabbit" -> 0.4F;
            case "squid", "glow_squid" -> 0.63F;
            case "zombie",
                    "drowned",
                    "husk",
                    "zombie_villager",
                    "zombified_piglin",
                    "villager",
                    "piglin",
                    "piglin_brute" -> 0.99F;
            default -> null;
        };
    }

    private static EntityTypes.Type baseDimensions(PacketEntity packetEntity) {
        return EntityTypeUtil.modelType(packetEntity.type);
    }
}
