package ac.grim.grimac.bedrock.replay.offline;

import ac.grim.grimac.checks.impl.badpackets.BadPacketsJ;
import ac.grim.grimac.checks.impl.badpackets.BadPacketsX;
import ac.grim.grimac.checks.impl.elytra.ElytraC;
import ac.grim.grimac.events.packets.listeners.CheckManagerListener;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.player.User;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.latency.CompensatedCameraEntity;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.entity.Entity;
import org.junit.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CameraAwayDecodedPacketResetTest {
    @Test
    public void unrelatedDecodedPacketClearsOnlyCameraScopedState() throws Exception {
        GrimPlayer player = offlineJavaPlayer();
        try {
            BadPacketsJ badPacketsJ = player.checkManager.getListener(BadPacketsJ.class);
            BadPacketsX badPacketsX = player.checkManager.getListener(BadPacketsX.class);
            ElytraC elytraC = player.checkManager.getListener(ElytraC.class);
            setExternalCamera(player);

            setIntField(badPacketsJ, "rotations", 3);
            setBooleanField(badPacketsX, "sprint", true);
            setBooleanField(badPacketsX, "sneak", true);
            setIntField(badPacketsX, "flags", 4);
            setBooleanField(elytraC, "glideThisTick", true);
            setBooleanField(elytraC, "glideLastTick", true);
            setBooleanField(elytraC, "setback", true);
            setIntField(elytraC, "flags", 5);
            elytraC.exempt = true;

            PacketReceiveEvent event = receiveEvent(player, new ServerboundPongPacket(17));
            player.checkManager.dispatchReceiveHandlers(event);

            assertEquals(0, intField(badPacketsJ, "rotations"));
            assertFalse(booleanField(badPacketsX, "sprint"));
            assertFalse(booleanField(badPacketsX, "sneak"));
            assertEquals(4, intField(badPacketsX, "flags"));
            assertFalse(booleanField(elytraC, "glideThisTick"));
            assertFalse(booleanField(elytraC, "glideLastTick"));
            assertTrue(booleanField(elytraC, "setback"));
            assertEquals(5, intField(elytraC, "flags"));
            assertTrue(elytraC.exempt);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void decodedObserverLeavesStateIntactForSelfCamera() throws Exception {
        GrimPlayer player = offlineJavaPlayer();
        try {
            BadPacketsJ badPacketsJ = player.checkManager.getListener(BadPacketsJ.class);
            BadPacketsX badPacketsX = player.checkManager.getListener(BadPacketsX.class);
            ElytraC elytraC = player.checkManager.getListener(ElytraC.class);

            setIntField(badPacketsJ, "rotations", 3);
            setBooleanField(badPacketsX, "sprint", true);
            setBooleanField(badPacketsX, "sneak", true);
            setBooleanField(elytraC, "glideThisTick", true);
            setBooleanField(elytraC, "glideLastTick", true);

            PacketReceiveEvent event = receiveEvent(player, new ServerboundPongPacket(18));
            player.checkManager.dispatchReceiveHandlers(event);

            assertEquals(3, intField(badPacketsJ, "rotations"));
            assertTrue(booleanField(badPacketsX, "sprint"));
            assertTrue(booleanField(badPacketsX, "sneak"));
            assertTrue(booleanField(elytraC, "glideThisTick"));
            assertTrue(booleanField(elytraC, "glideLastTick"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void interveningOnlyDecodedPacketAlsoClearsCameraScopedState() throws Exception {
        GrimPlayer player = offlineJavaPlayer();
        try {
            BadPacketsJ badPacketsJ = player.checkManager.getListener(BadPacketsJ.class);
            BadPacketsX badPacketsX = player.checkManager.getListener(BadPacketsX.class);
            ElytraC elytraC = player.checkManager.getListener(ElytraC.class);
            setExternalCamera(player);

            setIntField(badPacketsJ, "rotations", 2);
            setBooleanField(badPacketsX, "sprint", true);
            setBooleanField(badPacketsX, "sneak", true);
            setBooleanField(elytraC, "glideThisTick", true);
            setBooleanField(elytraC, "glideLastTick", true);

            ServerboundSignUpdatePacket packet = new ServerboundSignUpdatePacket(
                    BlockPos.ZERO, true, "", "", "", "");
            new CheckManagerListener().onSignUpdate(receiveEvent(player, packet), player, packet);

            assertEquals(0, intField(badPacketsJ, "rotations"));
            assertFalse(booleanField(badPacketsX, "sprint"));
            assertFalse(booleanField(badPacketsX, "sneak"));
            assertFalse(booleanField(elytraC, "glideThisTick"));
            assertFalse(booleanField(elytraC, "glideLastTick"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void elytraObserverRunsBeforeTheTargetedStartGlideHandler() throws Exception {
        GrimPlayer player = offlineJavaPlayer();
        try {
            ElytraC elytraC = player.checkManager.getListener(ElytraC.class);
            setExternalCamera(player);
            setBooleanField(elytraC, "glideThisTick", true);
            setBooleanField(elytraC, "glideLastTick", true);

            ServerboundPlayerCommandPacket packet = new ServerboundPlayerCommandPacket(
                    Mockito.mock(Entity.class),
                    ServerboundPlayerCommandPacket.Action.START_FALL_FLYING,
                    0);
            PacketReceiveEvent event = receiveEvent(player, packet);

            player.checkManager.dispatchReceiveHandlers(event);

            assertTrue(booleanField(elytraC, "glideThisTick"));
            assertFalse(booleanField(elytraC, "glideLastTick"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setExternalCamera(GrimPlayer player) throws ReflectiveOperationException {
        Field entitiesField = CompensatedCameraEntity.class.getDeclaredField("entities");
        entitiesField.setAccessible(true);
        ArrayDeque<PacketEntity> entities =
                (ArrayDeque<PacketEntity>) entitiesField.get(player.cameraEntity);
        entities.clear();
        entities.add(new PacketEntity(EntityTypesCompat.ZOMBIE, 99));
        assertFalse(player.cameraEntity.isSelf());
    }

    private static PacketReceiveEvent receiveEvent(GrimPlayer player, Packet<?> packet) {
        return new PacketReceiveEvent(player.user, packet, ConnectionProtocol.PLAY);
    }

    private static boolean booleanField(Object target, String name) throws ReflectiveOperationException {
        return field(target, name).getBoolean(target);
    }

    private static void setBooleanField(Object target, String name, boolean value)
            throws ReflectiveOperationException {
        field(target, name).setBoolean(target, value);
    }

    private static int intField(Object target, String name) throws ReflectiveOperationException {
        return field(target, name).getInt(target);
    }

    private static void setIntField(Object target, String name, int value)
            throws ReflectiveOperationException {
        field(target, name).setInt(target, value);
    }

    private static Field field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static GrimPlayer offlineJavaPlayer() {
        OfflineGrimTestBootstrap.installConfig();
        UUID playerId = UUID.randomUUID();
        User user = new User(
                new User.Profile(playerId, ".Camera_Away_Reset_Test"),
                null,
                null,
                null,
                new EmbeddedChannel());
        return new GrimPlayer(user);
    }
}
