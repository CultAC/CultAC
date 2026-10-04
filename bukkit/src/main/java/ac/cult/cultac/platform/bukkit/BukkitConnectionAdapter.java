package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.protocol.util.FoliaCompatUtil;
import ac.cult.cultac.network.protocol.util.viaversion.ViaConnectionProtocol;
import ac.cult.cultac.network.protocol.util.viaversion.ViaVersionUtil;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/** All native account/player lookups are confined to the Paper platform. */
public final class BukkitConnectionAdapter implements PlatformConnection {
    private static final ProtocolVersion NATIVE_PROTOCOL =
            ProtocolVersion.of(net.minecraft.SharedConstants.getProtocolVersion());
    private static final Field CONFIGURATION_PROFILE = configurationProfileField();
    private static final Method PROFILE_ID = profileMethod("id", "getId");
    private static final Method PROFILE_NAME = profileMethod("name", "getName");
    private static final Method PLAYER_LEVEL = playerLevelMethod();
    private static final ac.cult.cultac.utils.minecraft.MinecraftRegistries REGISTRIES =
            new ac.cult.cultac.utils.minecraft.MinecraftRegistries(
                    () -> net.minecraft.server.MinecraftServer.getServer().registryAccess(),
                    () -> net.minecraft.server.MinecraftServer.getServer().getResourceManager());
    private final Connection connection;

    @Override
    public ac.cult.cultac.utils.minecraft.MinecraftRegistries registries() {
        return REGISTRIES;
    }

    public BukkitConnectionAdapter(Connection connection) {
        this.connection = java.util.Objects.requireNonNull(connection);
    }

    @Override
    public ProtocolVersion getObservedProtocol() {
        return NATIVE_PROTOCOL;
    }

    @Override
    public ProtocolVersion getClientProtocol() {
        if (authenticatedProfile() == null) return null;
        int protocol = ViaVersionUtil.isAvailable() ? ViaConnectionProtocol.originalProtocol(connection.channel) : -1;
        if (protocol == -1) protocol = net.minecraft.SharedConstants.getProtocolVersion();
        if (protocol < 768 || protocol > 777) return null;
        return ProtocolVersion.of(protocol);
    }

    @Override
    public User.Profile authenticatedProfile() {
        if (connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener
                && listener.player != null) {
            return new User.Profile(
                    listener.player.getUUID(), listener.player.getBukkitEntity().getName());
        }
        if (connection.getPacketListener() instanceof ServerConfigurationPacketListenerImpl listener) {
            try {
                Object profile = CONFIGURATION_PROFILE.get(listener);
                return profile == null
                        ? null
                        : new User.Profile((UUID) PROFILE_ID.invoke(profile), (String) PROFILE_NAME.invoke(profile));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot read authenticated Paper profile", failure);
            }
        }
        return null;
    }

    @Override
    public PlayerBinding playerBinding() {
        if (!(connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener)
                || listener.player == null) return null;
        ServerPlayer handle = listener.player;
        Player player = handle.getBukkitEntity();
        return new PlayerBinding() {
            @Override
            public PlatformPlayer player() {
                return CultAPI.INSTANCE.getPlatformPlayerFactory().getFromNativePlayerType(player);
            }

            @Override
            public boolean isCurrent() {
                return connection(player) == connection;
            }

            @Override
            public boolean matches(Object candidate) {
                return candidate == player;
            }

            @Override
            public void initialize(CultPlayer target) {
                initializePlayer(target, player, handle);
            }
        };
    }

    @Override
    public void disconnect(Component reason) {
        if (connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener
                && listener.player != null) {
            Player player = listener.player.getBukkitEntity();
            FoliaCompatUtil.runTaskForEntity(
                    player,
                    CultACBukkitLoaderPlugin.LOADER,
                    () -> {
                        if (connection(player) == connection) player.kick(reason);
                    },
                    null,
                    0);
        } else {
            connection.disconnect(net.minecraft.network.chat.Component.literal(
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(reason)));
        }
    }

    public static Connection connection(Player player) {
        if (!(player instanceof CraftPlayer craftPlayer) || craftPlayer.getHandle().connection == null) return null;
        return craftPlayer.getHandle().connection.connection;
    }

    @Override
    public void sendMessage(Component message) {
        if (connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener
                && listener.player != null) {
            Player player = listener.player.getBukkitEntity();
            if (connection(player) == connection) player.sendMessage(message);
        }
    }

    private static void initializePlayer(CultPlayer target, Player player, ServerPlayer handle) {
        target.entityID = player.getEntityId();
        final ServerLevel level;
        try {
            level = (ServerLevel) PLAYER_LEVEL.invoke(handle);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read Paper player level", failure);
        }
        target.gamemode =
                GameMode.valueOf(handle.gameMode.getGameModeForPlayer().name());
        target.dimension = level.dimension();
        target.world = NmsIdentifierUtil.resourceKey(target.dimension);
        target.compensatedWorld.setLastClientboundDimension(target.world, level.dimensionType());
        target.compensatedWorld.setDimension(target.world, level.dimensionType());
        target.lastJoinedWorld = System.currentTimeMillis();
    }

    private static Field configurationProfileField() {
        try {
            Field field = ServerConfigurationPacketListenerImpl.class.getDeclaredField("gameProfile");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot resolve Paper configuration profile", failure);
        }
    }

    private static Method profileMethod(String... names) {
        for (String name : names) {
            try {
                return com.mojang.authlib.GameProfile.class.getMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new IllegalStateException("Cannot resolve Authlib profile accessor");
    }

    private static Method playerLevelMethod() {
        for (String name : new String[] {"level", "serverLevel"}) {
            try {
                return ServerPlayer.class.getMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new IllegalStateException("Cannot resolve Paper player level accessor");
    }
}
