package ac.cult.cultac.network.packet;

import ac.cult.cultac.network.event.PacketEvent;
import org.bukkit.inventory.ItemStack;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import org.bukkit.block.BlockFace;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import ac.cult.cultac.player.CultPlayer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.StatusOnly;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class NmsPacketUtil {
    private static final Field CLIENTBOUND_ENTITY_EVENT_ENTITY_ID = resolveIntField(ClientboundEntityEventPacket.class, "entityId");
    private static final Field CLIENTBOUND_MOVE_ENTITY_ID = resolveIntField(ClientboundMoveEntityPacket.class, "entityId");

    private NmsPacketUtil() {
    }

    public static boolean isSwingOrPunch(Packet<?> packet) {
        String type = packet.getClass().getName();
        return type.equals("net.minecraft.network.protocol.game.ServerboundSwingPacket")
                || type.equals("net.minecraft.network.protocol.game.ServerboundPunchPacket");
    }

    public static InteractionHand swingHand(Packet<?> packet) {
        if (packet.getClass().getName().equals("net.minecraft.network.protocol.game.ServerboundPunchPacket")) {
            return InteractionHand.MAIN_HAND;
        }
        return (InteractionHand) invokeNoArg(packet, "hand", "getHand");
    }

    public static TeleportAcknowledgement readTeleportAcknowledgement(Packet<?> packet) {
        Object x = invokeNoArgOrNull(packet, "x");
        return new TeleportAcknowledgement(intValue(packet, "id", "getId"),
                x instanceof Number number ? new Vec3(number.doubleValue(), doubleValue(packet, "y"), doubleValue(packet, "z")) : null,
                x != null ? floatValue(packet, "yRot") : 0.0F,
                x != null ? floatValue(packet, "xRot") : 0.0F);
    }

    public record TeleportAcknowledgement(int id, @Nullable Vec3 position, float yaw, float pitch) {
    }

    public static MovePlayerData readMovePlayer(ServerboundMovePlayerPacket packet, CultPlayer player) {
        return readMovePlayer(packet, player.x, player.y, player.z, player.xRot, player.yRot);
    }

    public static MoveVehicleData readMoveVehicle(ServerboundMoveVehiclePacket packet) {
        Object movingTo = invokeNoArgOrNull(packet, "movingTo");
        if (movingTo != null) {
            return new MoveVehicleData((Vec3) invokeNoArg(movingTo, "position"),
                    floatValue(movingTo, "yRot"), floatValue(movingTo, "xRot"),
                    (Boolean) invokeNoArg(packet, "onGround"), true);
        }
        Object position = invokeNoArgOrNull(packet, "position");
        Vec3 coordinates = position instanceof Vec3 vec3
                ? vec3
                : new Vec3(
                        doubleValue(packet, "getX"),
                        doubleValue(packet, "getY"),
                        doubleValue(packet, "getZ")
                );
        Object onGround = invokeNoArgOrNull(packet, "onGround");
        return new MoveVehicleData(
                coordinates,
                floatValue(packet, "yRot", "getYRot"),
                floatValue(packet, "xRot", "getXRot"),
                onGround instanceof Boolean value && value,
                onGround instanceof Boolean
        );
    }

    public static MoveVehicleData readMoveVehicle(ClientboundMoveVehiclePacket packet) {
        Object movingTo = invokeNoArgOrNull(packet, "movingTo");
        if (movingTo != null) {
            return new MoveVehicleData((Vec3) invokeNoArg(movingTo, "position"),
                    floatValue(movingTo, "yRot"), floatValue(movingTo, "xRot"), false, false);
        }
        Object position = invokeNoArgOrNull(packet, "position");
        Vec3 coordinates = position instanceof Vec3 vec3
                ? vec3
                : new Vec3(
                        doubleValue(packet, "getX"),
                        doubleValue(packet, "getY"),
                        doubleValue(packet, "getZ")
                );
        return new MoveVehicleData(
                coordinates,
                floatValue(packet, "yRot", "getYRot"),
                floatValue(packet, "xRot", "getXRot"),
                false,
                false
        );
    }

    public static ClientboundMoveVehiclePacket clientboundMoveVehiclePacket(Vec3 position, float yaw, float pitch) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(32));
        try {
            buffer.writeDouble(position.x);
            buffer.writeDouble(position.y);
            buffer.writeDouble(position.z);
            buffer.writeFloat(yaw);
            buffer.writeFloat(pitch);
            return ClientboundMoveVehiclePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    public static MovePlayerData readMovePlayer(ServerboundMovePlayerPacket packet, double defaultX, double defaultY, double defaultZ, float defaultYaw, float defaultPitch) {
        return new MovePlayerData(
                packet.getX(defaultX),
                packet.getY(defaultY),
                packet.getZ(defaultZ),
                packet.getYRot(defaultYaw),
                packet.getXRot(defaultPitch),
                packet.hasPosition(),
                packet.hasRotation(),
                packet.isOnGround()
        );
    }

    public static ServerboundMovePlayerPacket withOnGround(ServerboundMovePlayerPacket packet, CultPlayer player, boolean onGround) {
        return withOnGround(packet, onGround, player.x, player.y, player.z, player.xRot, player.yRot);
    }

    public static ServerboundMovePlayerPacket withOnGround(ServerboundMovePlayerPacket packet, boolean onGround, double defaultX, double defaultY, double defaultZ, float defaultYaw, float defaultPitch) {
        boolean horizontalCollision = booleanValueOrDefault(packet, false, "horizontalCollision");
        if (packet.hasPosition() && packet.hasRotation()) {
            return constructMovePacket(PosRot.class,
                    new Class<?>[]{double.class, double.class, double.class, float.class, float.class, boolean.class, boolean.class},
                    new Object[]{packet.getX(defaultX), packet.getY(defaultY), packet.getZ(defaultZ), packet.getYRot(defaultYaw), packet.getXRot(defaultPitch), onGround, horizontalCollision},
                    new Class<?>[]{double.class, double.class, double.class, float.class, float.class, boolean.class});
        }
        if (packet.hasPosition()) {
            return constructMovePacket(Pos.class,
                    new Class<?>[]{double.class, double.class, double.class, boolean.class, boolean.class},
                    new Object[]{packet.getX(defaultX), packet.getY(defaultY), packet.getZ(defaultZ), onGround, horizontalCollision},
                    new Class<?>[]{double.class, double.class, double.class, boolean.class});
        }
        if (packet.hasRotation()) {
            return constructMovePacket(Rot.class,
                    new Class<?>[]{float.class, float.class, boolean.class, boolean.class},
                    new Object[]{packet.getYRot(defaultYaw), packet.getXRot(defaultPitch), onGround, horizontalCollision},
                    new Class<?>[]{float.class, float.class, boolean.class});
        }
        return constructMovePacket(StatusOnly.class,
                new Class<?>[]{boolean.class, boolean.class},
                new Object[]{onGround, horizontalCollision},
                new Class<?>[]{boolean.class});
    }

    public static ServerboundMovePlayerPacket positionPacket(
            double x, double y, double z, boolean onGround, boolean horizontalCollision
    ) {
        return constructMovePacket(Pos.class,
                new Class<?>[]{double.class, double.class, double.class, boolean.class, boolean.class},
                new Object[]{x, y, z, onGround, horizontalCollision},
                new Class<?>[]{double.class, double.class, double.class, boolean.class});
    }

    public static Packet<?> playerPositionPacket(
            int teleportId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            int relativeMask
    ) {
        try {
            Class<?> changeType = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
            Object change = changeType
                    .getConstructor(Vec3.class, Vec3.class, float.class, float.class)
                    .newInstance(new Vec3(x, y, z), Vec3.ZERO, yaw, pitch);
            Class<?> relativeType = Class.forName("net.minecraft.world.entity.Relative");
            Object relatives = relativeType.getMethod("unpack", int.class).invoke(null, relativeMask);
            return (Packet<?>) net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket.class
                    .getMethod("of", int.class, changeType, Set.class)
                    .invoke(null, teleportId, change, relatives);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            try {
                Class<?> relativeType = Class.forName("net.minecraft.world.entity.RelativeMovement");
                Set<Object> relatives = new HashSet<>();
                addRelativeMovement(relatives, relativeType, relativeMask, RelativeFlag.X.getMask(), "X");
                addRelativeMovement(relatives, relativeType, relativeMask, RelativeFlag.Y.getMask(), "Y");
                addRelativeMovement(relatives, relativeType, relativeMask, RelativeFlag.Z.getMask(), "Z");
                addRelativeMovement(relatives, relativeType, relativeMask, RelativeFlag.Y_ROT.getMask(), "Y_ROT");
                addRelativeMovement(relatives, relativeType, relativeMask, RelativeFlag.X_ROT.getMask(), "X_ROT");
                return net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket.class
                        .getConstructor(double.class, double.class, double.class, float.class, float.class, Set.class, int.class)
                        .newInstance(x, y, z, yaw, pitch, relatives, teleportId);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to create legacy player position packet", exception);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to create player position packet", exception);
        }
    }

    public static @Nullable Packet<?> modernEntityTeleportPacket(
            int entityId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            int relativeMask,
            boolean onGround
    ) {
        try {
            Class<?> changeType = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
            Object change = changeType
                    .getConstructor(Vec3.class, Vec3.class, float.class, float.class)
                    .newInstance(new Vec3(x, y, z), Vec3.ZERO, yaw, pitch);
            Class<?> relativeType = Class.forName("net.minecraft.world.entity.Relative");
            Object relatives = relativeType.getMethod("unpack", int.class).invoke(null, relativeMask);
            return (Packet<?>) net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket.class
                    .getMethod("teleport", int.class, changeType, Set.class, boolean.class)
                    .invoke(null, entityId, change, relatives, onGround);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Legacy protocols synchronize the local player with the player-position packet alone.
            return null;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to create entity teleport packet", exception);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addRelativeMovement(Set<Object> relatives, Class<?> relativeType, int mask, int flag, String name) {
        if ((mask & flag) != 0) {
            relatives.add(Enum.valueOf((Class<? extends Enum>) relativeType.asSubclass(Enum.class), name));
        }
    }

    private static ServerboundMovePlayerPacket constructMovePacket(
            Class<? extends ServerboundMovePlayerPacket> type,
            Class<?>[] modernParameterTypes,
            Object[] modernArguments,
            Class<?>[] legacyParameterTypes
    ) {
        try {
            Constructor<? extends ServerboundMovePlayerPacket> constructor = type.getConstructor(modernParameterTypes);
            return constructor.newInstance(modernArguments);
        } catch (NoSuchMethodException ignored) {
            try {
                Constructor<? extends ServerboundMovePlayerPacket> constructor = type.getConstructor(legacyParameterTypes);
                Object[] legacyArguments = new Object[modernArguments.length - 1];
                System.arraycopy(modernArguments, 0, legacyArguments, 0, legacyArguments.length);
                return constructor.newInstance(legacyArguments);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to construct legacy movement packet " + type.getName(), exception);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to construct movement packet " + type.getName(), exception);
        }
    }

    public static InteractData readInteract(ServerboundInteractPacket packet) {
        int entityId = intValue(packet, "entityId", "getEntityId");
        boolean usingSecondaryAction = booleanValue(packet, "usingSecondaryAction", "isUsingSecondaryAction");
        if (hasNoArgMethod(packet, "hand") && hasNoArgMethod(packet, "location")) {
            InteractionHand hand = (InteractionHand) invokeNoArg(packet, "hand");
            Vec3 location = (Vec3) invokeNoArg(packet, "location");
            return new InteractData(
                    entityId,
                    InteractAction.INTERACT_AT,
                    hand,
                    Optional.of(location),
                    usingSecondaryAction
            );
        }
        if (booleanValueOrDefault(packet, false, "isAttack")) {
            return new InteractData(
                    entityId,
                    InteractAction.ATTACK,
                    InteractionHand.MAIN_HAND,
                    Optional.empty(),
                    usingSecondaryAction
            );
        }

        Object actionPayload = fieldValue(packet, "action");
        InteractionHand hand = fieldValueByType(actionPayload, InteractionHand.class);
        Vec3 location = fieldValueByType(actionPayload, Vec3.class);
        InteractAction action;
        InteractionHand interactHand = InteractionHand.MAIN_HAND;
        Optional<Vec3> target = Optional.empty();

        if (location != null) {
            action = InteractAction.INTERACT_AT;
            if (hand != null) interactHand = hand;
            target = Optional.of(location);
        } else if (hand != null) {
            action = InteractAction.INTERACT;
            interactHand = hand;
        } else {
            action = InteractAction.ATTACK;
        }

        return new InteractData(entityId, action, interactHand, target, usingSecondaryAction);
    }

    public static int readSpectatorEntityId(Packet<?> packet) {
        // PacketEvents exposed the optional 26.2 target through an integer getter
        // whose absent-value default was zero.
        return spectatorEntityId(packet).orElse(0);
    }

    public static OptionalInt spectatorEntityId(Packet<?> packet) {
        if (packet.getClass().getSimpleName().equals("ServerboundSpectateEntityPacket")) {
            return OptionalInt.of(intValue(packet, "entityId"));
        }
        return (OptionalInt) invokeNoArg(packet, "spectateEntityId");
    }

    public static String gameProfileName(Object profile) {
        return (String) invokeNoArg(profile, "name", "getName");
    }

    private static final Field TELEPORT_TO_ENTITY_UUID = resolveTeleportUuidField();

    // 26.2 carries only the target UUID on the spectate-teleport wire; the field is
    // private with no accessor, so resolve it once like CompensatedCameraEntity#cameraId.
    public static UUID readTeleportToEntityUuid(ServerboundTeleportToEntityPacket packet) {
        try {
            return (UUID) TELEPORT_TO_ENTITY_UUID.get(packet);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to read ServerboundTeleportToEntityPacket#uuid", exception);
        }
    }

    private static Field resolveTeleportUuidField() {
        try {
            Field field = ServerboundTeleportToEntityPacket.class.getDeclaredField("uuid");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("Unable to find ServerboundTeleportToEntityPacket#uuid", exception);
        }
    }

    public static EntityMotionData readEntityMotion(ClientboundSetEntityMotionPacket packet) {
        Object movement = invokeNoArgOrNull(packet, "movement", "getMovement");
        Vec3 velocity = movement instanceof Vec3 vec3
                ? vec3
                : new Vec3(
                        doubleValue(packet, "getXa"),
                        doubleValue(packet, "getYa"),
                        doubleValue(packet, "getZa")
                );
        return new EntityMotionData(intValue(packet, "id", "getId"), velocity);
    }

    public static Vec3 readExplosionKnockback(ClientboundExplodePacket packet) {
        Object knockback = invokeNoArgOrNull(packet, "playerKnockback");
        if (knockback instanceof Optional<?> optional) {
            return optional.filter(Vec3.class::isInstance).map(Vec3.class::cast).orElse(Vec3.ZERO);
        }
        return new Vec3(
                doubleValue(packet, "getKnockbackX"),
                doubleValue(packet, "getKnockbackY"),
                doubleValue(packet, "getKnockbackZ")
        );
    }

    public static InteractData readInteract(ServerboundAttackPacket packet) {
        return new InteractData(
                packet.entityId(),
                InteractAction.ATTACK,
                InteractionHand.MAIN_HAND,
                Optional.empty(),
                false
        );
    }

    public static InteractData readAttack(Packet<?> packet) {
        if (!(packet instanceof ServerboundAttackPacket attackPacket)) {
            throw new IllegalArgumentException("Not a modern attack packet: " + packet.getClass().getName());
        }
        return readInteract(attackPacket);
    }

    public static MountScreenOpenData readMountScreenOpen(Packet<?> packet) {
        // HorseScreenOpen (1.21.3) and MountScreenOpen carry the same inventory fields.
        return new MountScreenOpenData(
                intValue(packet, "getContainerId"),
                intValue(packet, "getInventoryColumns"),
                intValue(packet, "getEntityId")
        );
    }

    public static PlayerActionData readPlayerAction(ServerboundPlayerActionPacket packet) {
        BlockPos pos = packet.getPos();
        return new PlayerActionData(
                packet.getAction(),
                pos,
                switch (packet.getDirection()) {
                    case DOWN -> BlockFace.DOWN;
                    case UP -> BlockFace.UP;
                    case NORTH -> BlockFace.NORTH;
                    case SOUTH -> BlockFace.SOUTH;
                    case WEST -> BlockFace.WEST;
                    case EAST -> BlockFace.EAST;
                },
                packet.getSequence()
        );
    }

    public static UseItemData readUseItem(ServerboundUseItemPacket packet) {
        return new UseItemData((InteractionHand) invokeNoArg(packet, "hand", "getHand"),
                intValue(packet, "sequence", "getSequence"), floatValue(packet, "yRot", "getYRot"),
                floatValue(packet, "xRot", "getXRot"));
    }

    public static UseItemOnData readUseItemOn(ServerboundUseItemOnPacket packet) {
        net.minecraft.world.phys.BlockHitResult hit = (net.minecraft.world.phys.BlockHitResult)
                invokeNoArg(packet, "hitResult", "getHitResult");
        BlockPos pos = hit.getBlockPos();
        Vec3 location = hit.getLocation();
        return new UseItemOnData((InteractionHand) invokeNoArg(packet, "hand", "getHand"), pos,
                BlockFace.valueOf(hit.getDirection().name()),
                new Vec3(location.x - pos.getX(), location.y - pos.getY(), location.z - pos.getZ()),
                hit.isInside(), intValue(packet, "sequence", "getSequence"));
    }

    public static int[] removedEntityIds(Packet<?> packet) {
        return ((it.unimi.dsi.fastutil.ints.IntList) invokeNoArg(packet, "entityIds", "getEntityIds")).toIntArray();
    }

    public static net.minecraft.world.item.component.BundleContents.Mutable mutableBundle(
            net.minecraft.world.item.component.BundleContents contents) {
        try {
            try {
                return (net.minecraft.world.item.component.BundleContents.Mutable)
                        contents.getClass().getMethod("asMutable").invoke(contents);
            } catch (NoSuchMethodException legacy) {
                return net.minecraft.world.item.component.BundleContents.Mutable.class
                        .getConstructor(net.minecraft.world.item.component.BundleContents.class).newInstance(contents);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to copy bundle contents", failure);
        }
    }

    public static PlayerCommandData readPlayerCommand(ServerboundPlayerCommandPacket packet) {
        return new PlayerCommandData(
                fromPlayerCommandAction(packet.getAction()),
                packet.getId(),
                packet.getData()
        );
    }

    public static ContainerClickData readContainerClick(ServerboundContainerClickPacket packet) {
        Map<Integer, ItemStack> changedSlots = new HashMap<>();
        Map<?, ?> slots = (Map<?, ?>) invokeNoArg(packet, "changedSlots", "getChangedSlots");
        slots.forEach((slot, stack) -> changedSlots.put(((Number) slot).intValue(), containerItem(stack)));
        // 1.21.3 sends full ItemStacks with getter accessors; later packets carry
        // HashedStacks. ClickType was renamed ContainerInput without changing its values.
        Enum<?> input = (Enum<?>) invokeNoArg(packet, "containerInput", "clickType", "getClickType");
        return new ContainerClickData(
                intValue(packet, "containerId", "getContainerId"),
                intValue(packet, "stateId", "getStateId"),
                intValue(packet, "slotNum", "getSlotNum"),
                intValue(packet, "buttonNum", "getButtonNum"),
                WindowClickType.VALUES[input.ordinal()],
                Collections.unmodifiableMap(changedSlots),
                containerItem(invokeNoArg(packet, "carriedItem", "getCarriedItem"))
        );
    }

    private static ItemStack containerItem(Object item) {
        if (item instanceof net.minecraft.world.item.ItemStack stack) {
            return SpigotConversionUtil.fromNmsItemStack(stack);
        }
        return SpigotConversionUtil.fromHashedStack((HashedStack) item);
    }

    public static EntityEventData readEntityEvent(ClientboundEntityEventPacket packet) {
        return new EntityEventData(readInt(packet, CLIENTBOUND_ENTITY_EVENT_ENTITY_ID), packet.getEventId());
    }

    public static int readMoveEntityId(ClientboundMoveEntityPacket packet) {
        return readInt(packet, CLIENTBOUND_MOVE_ENTITY_ID);
    }

    public static ContainerSetContentData readContainerContents(ClientboundContainerSetContentPacket packet) {
        Object rawItems = invokeNoArg(packet, "items", "getItems");
        if (!(rawItems instanceof List<?> packetItems)) {
            throw new IllegalStateException("Container contents accessor did not return a list");
        }
        List<ItemStack> items = new ArrayList<>(packetItems.size());
        for (Object item : packetItems) {
            items.add(SpigotConversionUtil.fromNmsItemStack((net.minecraft.world.item.ItemStack) item));
        }
        net.minecraft.world.item.ItemStack carried = (net.minecraft.world.item.ItemStack) invokeNoArg(
                packet, "carriedItem", "getCarriedItem");
        return new ContainerSetContentData(
                intValue(packet, "containerId", "getContainerId"),
                intValue(packet, "stateId", "getStateId"),
                items,
                SpigotConversionUtil.fromNmsItemStack(carried)
        );
    }

    public static ContainerSetSlotData readContainerSetSlot(ClientboundContainerSetSlotPacket packet) {
        return new ContainerSetSlotData(packet.getContainerId(), packet.getStateId(), PacketCodecUtil.decodeSignedShort(packet.getSlot()), SpigotConversionUtil.fromNmsItemStack(packet.getItem()));
    }

    public static PlayerCommandAction fromPlayerCommandAction(ServerboundPlayerCommandPacket.Action action) {
        return switch (action.name()) {
            case "PRESS_SHIFT_KEY" -> PlayerCommandAction.PRESS_SHIFT_KEY;
            case "RELEASE_SHIFT_KEY" -> PlayerCommandAction.RELEASE_SHIFT_KEY;
            case "STOP_SLEEPING" -> PlayerCommandAction.STOP_SLEEPING;
            case "START_SPRINTING" -> PlayerCommandAction.START_SPRINTING;
            case "STOP_SPRINTING" -> PlayerCommandAction.STOP_SPRINTING;
            case "START_RIDING_JUMP" -> PlayerCommandAction.START_JUMPING_WITH_HORSE;
            case "STOP_RIDING_JUMP" -> PlayerCommandAction.STOP_JUMPING_WITH_HORSE;
            case "OPEN_INVENTORY" -> PlayerCommandAction.OPEN_INVENTORY;
            case "START_FALL_FLYING" -> PlayerCommandAction.START_FLYING_WITH_ELYTRA;
            default -> throw new IllegalArgumentException("Unsupported player command action " + action.name());
        };
    }

    public static CustomPacketPayload payload(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket clientbound) {
            return clientbound.payload();
        }
        if (packet instanceof ServerboundCustomPayloadPacket serverbound) {
            return serverbound.payload();
        }
        return null;
    }

    public static @Nullable String payloadChannel(CustomPacketPayload payload) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof BrandPayload) {
            return "minecraft:brand";
        }
        if (payload instanceof DiscardedPayload discardedPayload) {
            return NmsIdentifierUtil.payloadId(discardedPayload);
        }
        return NmsIdentifierUtil.payloadId(payload.type());
    }

    public static byte[] payloadData(CustomPacketPayload payload) {
        if (payload == null) {
            return new byte[0];
        }
        if (payload instanceof BrandPayload brandPayload) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            buffer.writeUtf(brandPayload.brand());
            byte[] data = new byte[buffer.readableBytes()];
            buffer.readBytes(data);
            buffer.release();
            return data;
        }
        if (payload instanceof DiscardedPayload discardedPayload) {
            Object discardedData = invokeNoArg(discardedPayload, "data");
            if (discardedData instanceof byte[] bytes) {
                return bytes.clone();
            }
            if (discardedData instanceof ByteBuf buffer) {
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.getBytes(buffer.readerIndex(), bytes);
                return bytes;
            }
            throw new IllegalStateException("Unsupported discarded payload storage: " + discardedData.getClass().getName());
        }
        return new byte[0];
    }

    public static byte[] payloadData(PacketEvent event) {
        CustomPacketPayload payload = payload(event.getNmsPacket());
        byte[] data = payloadData(payload);
        if (data.length != 0 || payload == null || event.getByteBuf() == null) {
            return data;
        }

        return payloadDataFromRawPacket(event.getByteBuf(), payloadChannel(payload));
    }

    private static byte[] payloadDataFromRawPacket(ByteBuf rawPacket, @Nullable String expectedChannel) {
        FriendlyByteBuf packetData = new FriendlyByteBuf(rawPacket.retainedDuplicate());
        try {
            if (!packetData.isReadable()) {
                return new byte[0];
            }

            packetData.readVarInt(); // packet id
            String channel = packetData.readUtf();
            if (expectedChannel != null && !expectedChannel.equals(channel)) {
                return new byte[0];
            }

            byte[] data = new byte[packetData.readableBytes()];
            packetData.readBytes(data);
            return data;
        } catch (RuntimeException ignored) {
            return new byte[0];
        } finally {
            packetData.release();
        }
    }

    public record InteractData(int entityId, InteractAction action, InteractionHand hand, Optional<Vec3> target, boolean sneaking) {
    }

    public record PlayerActionData(Action action, BlockPos blockPosition, BlockFace blockFace, int sequence) {
    }

    public record UseItemData(InteractionHand hand, int sequence, float yaw, float pitch) {
    }

    public record UseItemOnData(InteractionHand hand, BlockPos blockPosition, BlockFace blockFace, Vec3 cursor, boolean insideBlock, int sequence) {
    }

    public record PlayerCommandData(PlayerCommandAction action, int entityId, int data) {
    }

    public record MovePlayerData(double x, double y, double z, float yaw, float pitch, boolean hasPositionChanged, boolean hasRotationChanged, boolean onGround) {
        public Vec3 position() {
            return new Vec3(x, y, z);
        }
    }

    public record MoveVehicleData(Vec3 position, float yaw, float pitch, boolean onGround, boolean hasOnGround) {
    }

    public record ContainerClickData(
            int windowId,
            int stateId,
            int slot,
            int button,
            WindowClickType clickType,
            Map<Integer, ItemStack> changedSlots,
            ItemStack carriedItem
    ) {
    }

    public record ContainerSetContentData(int windowId, int stateId, List<ItemStack> items, ItemStack carriedItem) {
    }

    public record ContainerSetSlotData(int windowId, int stateId, int slot, ItemStack item) {
    }

    public record EntityEventData(int entityId, byte status) {
    }

    public record EntityMotionData(int entityId, Vec3 movement) {
    }

    public record MountScreenOpenData(int containerId, int inventoryColumns, int entityId) {
    }

    public enum InteractAction {
        ATTACK,
        INTERACT,
        INTERACT_AT
    }

    public enum PlayerCommandAction {
        PRESS_SHIFT_KEY,
        RELEASE_SHIFT_KEY,
        STOP_SLEEPING,
        START_SPRINTING,
        STOP_SPRINTING,
        START_JUMPING_WITH_HORSE,
        STOP_JUMPING_WITH_HORSE,
        OPEN_INVENTORY,
        START_FLYING_WITH_ELYTRA
    }

    public static ServerboundContainerClickPacket createContainerClick(int windowId, int stateId, int slot, int button, WindowClickType clickType) {
        return new ServerboundContainerClickPacket(
                windowId,
                stateId,
                (short) slot,
                (byte) button,
                clickType.toNms(),
                new Int2ObjectOpenHashMap<>(),
                HashedStack.EMPTY
        );
    }

    private static Field resolveIntField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            if (field.getType() != int.class) {
                throw new IllegalArgumentException(owner.getName() + "#" + name + " is not an int field");
            }
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException exception) {
            throw new IllegalArgumentException("Unable to find field " + owner.getName() + "#" + name, exception);
        }
    }

    private static int readInt(Object instance, Field field) {
        try {
            return field.getInt(instance);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to access " + field.getName(), exception);
        }
    }

    public static net.minecraft.world.scores.Team.CollisionRule teamCollisionRule(Object parameters) {
        Object value = invokeNoArg(parameters, "collisionRule", "getCollisionRule");
        if (value instanceof net.minecraft.world.scores.Team.CollisionRule rule) return rule;
        // ClientPacketListener#handleSetPlayerTeamPacket used CollisionRule.byName
        // for the older string field, retaining the previous rule for unknown names.
        for (net.minecraft.world.scores.Team.CollisionRule rule : net.minecraft.world.scores.Team.CollisionRule.values()) {
            if (rule.name.equals(value)) return rule;
        }
        return null;
    }

    public static Packet<?> withPlayerRotation(Packet<?> packet, float yaw, float pitch) {
        try {
            try {
                Constructor<?> constructor = packet.getClass().getConstructor(float.class, boolean.class, float.class, boolean.class);
                return (Packet<?>) constructor.newInstance(yaw, booleanValue(packet, "relativeY"),
                        pitch, booleanValue(packet, "relativeX"));
            } catch (NoSuchMethodException legacy) {
                // The 1.21.3 record carries two absolute angles and no relative flags.
                return (Packet<?>) packet.getClass().getConstructor(float.class, float.class).newInstance(yaw, pitch);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to construct native player rotation", exception);
        }
    }

    public static Object invokeNoArg(Object target, String... methodNames) {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // Try the accessor name used by another supported server version.
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Unable to access " + target.getClass().getName() + "#" + methodName, exception);
            } catch (InvocationTargetException exception) {
                throw new IllegalStateException("Packet accessor failed", exception.getCause());
            }
        }
        throw new IllegalStateException("No supported packet accessor on " + target.getClass().getName());
    }

    private static @Nullable Object invokeNoArgOrNull(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // Try the accessor name used by another supported server version.
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Unable to access " + target.getClass().getName() + "#" + methodName, exception);
            } catch (InvocationTargetException exception) {
                throw new IllegalStateException("Packet accessor failed", exception.getCause());
            }
        }
        return null;
    }

    public static boolean hasNoArgMethod(Object target, String methodName) {
        try {
            target.getClass().getMethod(methodName);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static Object fieldValue(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (NoSuchFieldException | IllegalAccessException exception) {
            throw new IllegalStateException("Unable to access " + target.getClass().getName() + "#" + fieldName, exception);
        }
    }

    private static <T> @Nullable T fieldValueByType(Object target, Class<T> fieldType) {
        if (target == null) {
            return null;
        }
        for (Field field : target.getClass().getDeclaredFields()) {
            if (!fieldType.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                return fieldType.cast(field.get(target));
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Unable to access " + target.getClass().getName() + "#" + field.getName(), exception);
            }
        }
        return null;
    }

    public static int intValue(Object target, String... methodNames) {
        return ((Number) invokeNoArg(target, methodNames)).intValue();
    }

    public static int intMethodOrFieldValue(Object target, String name) {
        Object value = invokeNoArgOrNull(target, name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            Field field = target.getClass().getField(name);
            return field.getInt(target);
        } catch (NoSuchFieldException | IllegalAccessException exception) {
            throw new IllegalStateException("Unable to access integer member " + target.getClass().getName() + "#" + name, exception);
        }
    }

    public static float floatValue(Object target, String... methodNames) {
        return ((Number) invokeNoArg(target, methodNames)).floatValue();
    }

    public static double doubleValue(Object target, String... methodNames) {
        return ((Number) invokeNoArg(target, methodNames)).doubleValue();
    }

    public static boolean booleanValue(Object target, String... methodNames) {
        return (boolean) invokeNoArg(target, methodNames);
    }

    public static boolean booleanValueOrDefault(Object target, boolean defaultValue, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                return (boolean) method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // Try the accessor name used by another supported server version.
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Unable to access " + target.getClass().getName() + "#" + methodName, exception);
            } catch (InvocationTargetException exception) {
                throw new IllegalStateException("Packet accessor failed", exception.getCause());
            }
        }
        return defaultValue;
    }

}
