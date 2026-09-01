package ac.grim.grimac.bedrock.replay.offline;

import ac.grim.grimac.checks.impl.badpackets.BadPacketsG;
import ac.grim.grimac.checks.impl.badpackets.BadPacketsM;
import ac.grim.grimac.events.packets.listeners.PacketPlayerRespawn;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.network.protocol.player.User;
import ac.grim.grimac.player.GrimPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PacketPlayerRespawnLifecycleTest {
    @Test
    public void loginSeedsClientVisibleDeathScreenOption() throws Exception {
        OfflineGrimTestBootstrap.installConfig();
        GrimPlayer player = offlineJavaPlayer();
        try {
            PacketPlayerRespawn listener = new PacketPlayerRespawn();
            BadPacketsM badPacketsM = player.checkManager.getListener(BadPacketsM.class);

            ClientboundLoginPacket hiddenDeathScreen = loginPacket(false);
            listener.onLogin(sendEvent(player, hiddenDeathScreen), player, hiddenDeathScreen);
            assertFalse(player.packetStateData.showsDeathScreen);
            badPacketsM.onDeath();
            assertFalse(booleanField(badPacketsM, "menu"));

            ClientboundLoginPacket visibleDeathScreen = loginPacket(true);
            listener.onLogin(sendEvent(player, visibleDeathScreen), player, visibleDeathScreen);
            assertTrue(player.packetStateData.showsDeathScreen);
            badPacketsM.onDeath();
            assertTrue(booleanField(badPacketsM, "menu"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void repeatedLethalHealthPacketsReopenBadPacketsMDeathState() throws Exception {
        OfflineGrimTestBootstrap.installConfig();
        GrimPlayer player = offlineJavaPlayer();
        try {
            PacketPlayerRespawn listener = new PacketPlayerRespawn();
            ClientboundSetHealthPacket lethal = new ClientboundSetHealthPacket(0.0F, 20, 5.0F);

            listener.onSetHealth(sendEvent(player, lethal), player, lethal);
            assertTrue(player.compensatedEntities.getSelf().isDead);
            assertTrue(booleanField(player.checkManager.getListener(BadPacketsM.class), "menu"));

            player.checkManager.getListener(BadPacketsM.class).onRespawn();
            assertFalse(booleanField(player.checkManager.getListener(BadPacketsM.class), "menu"));

            // The native server is newer than 1.9, so the old PacketEvents listener did not
            // suppress an identical health packet. It must reapply death-screen state.
            listener.onSetHealth(sendEvent(player, lethal), player, lethal);
            assertTrue(booleanField(player.checkManager.getListener(BadPacketsM.class), "menu"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void respawnCallbacksRunOnlyWhenTheRespawnTransactionIsAcknowledged() throws Exception {
        OfflineGrimTestBootstrap.installConfig();
        GrimPlayer player = offlineJavaPlayer();
        try {
            PacketPlayerRespawn listener = new PacketPlayerRespawn();
            BadPacketsM badPacketsM = player.checkManager.getListener(BadPacketsM.class);
            BadPacketsG badPacketsG = player.checkManager.getListener(BadPacketsG.class);
            badPacketsM.onDeath();

            ClientboundRespawnPacket respawn = respawnPacket();
            listener.onRespawn(sendEvent(player, respawn), player, respawn);

            assertTrue(booleanField(badPacketsM, "menu"));
            assertFalse(booleanField(badPacketsG, "respawn"));

            player.lastTransactionReceived.set(1);
            player.latencyUtils.handleNettySyncTransaction(1);

            assertFalse(booleanField(badPacketsM, "menu"));
            assertTrue(booleanField(badPacketsG, "respawn"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static ClientboundRespawnPacket respawnPacket() {
        DimensionType dimensionType = dimensionType();
        CommonPlayerSpawnInfo spawnInfo = new CommonPlayerSpawnInfo(
                Holder.direct(dimensionType),
                Level.OVERWORLD,
                0L,
                GameType.SURVIVAL,
                GameType.SURVIVAL,
                false,
                false,
                Optional.empty(),
                0,
                63);
        return new ClientboundRespawnPacket(spawnInfo, ClientboundRespawnPacket.KEEP_ALL_DATA);
    }

    private static ClientboundLoginPacket loginPacket(boolean showDeathScreen) {
        return new ClientboundLoginPacket(
                1,
                false,
                Set.of(Level.OVERWORLD),
                20,
                10,
                10,
                false,
                showDeathScreen,
                false,
                respawnPacket().commonPlayerSpawnInfo(),
                false,
                false);
    }

    private static DimensionType dimensionType() {
        DimensionType.MonsterSettings monsterSettings =
                new DimensionType.MonsterSettings(ConstantInt.of(0), 0);
        for (java.lang.reflect.Constructor<?> constructor : DimensionType.class.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            try {
                if (parameterTypes.length == 14 && parameterTypes[0] == boolean.class
                        && parameterTypes[11].isEnum()) {
                    return (DimensionType) constructor.newInstance(
                            false, true, false, 1.0D,
                            -64, 384, 384,
                            BlockTags.INFINIBURN_OVERWORLD, 0.0F, monsterSettings,
                            DimensionType.Skybox.OVERWORLD, enumConstant(parameterTypes[11], "DEFAULT"),
                            EnvironmentAttributeMap.EMPTY, HolderSet.empty());
                }
                if (parameterTypes.length == 16 && parameterTypes[0] == boolean.class
                        && parameterTypes[12].isEnum() && parameterTypes[15] == Optional.class) {
                    Object infiniburn = TagKey.class.isAssignableFrom(parameterTypes[8])
                            ? BlockTags.INFINIBURN_OVERWORLD
                            : HolderSet.empty();
                    return (DimensionType) constructor.newInstance(
                            false, true, false, false, 1.0D,
                            -64, 384, 384,
                            infiniburn, 0.0F, monsterSettings,
                            DimensionType.Skybox.OVERWORLD, enumConstant(parameterTypes[12], "DEFAULT"),
                            EnvironmentAttributeMap.EMPTY, HolderSet.empty(), Optional.empty());
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("failed to create test dimension type", exception);
            }
        }
        throw new IllegalStateException("unsupported DimensionType ABI in lifecycle test");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumConstant(Class<?> enumType, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumType.asSubclass(Enum.class), name);
    }

    private static PacketSendEvent sendEvent(GrimPlayer player, net.minecraft.network.protocol.Packet<?> packet) {
        return new PacketSendEvent(player.user, packet, ConnectionProtocol.PLAY);
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static GrimPlayer offlineJavaPlayer() {
        UUID playerId = UUID.fromString("9c5e440b-265d-435f-98f1-1f539659c002");
        User user = new User(
                new User.Profile(playerId, ".Respawn_Test"),
                null,
                null,
                null,
                new EmbeddedChannel());
        return new GrimPlayer(user);
    }
}
