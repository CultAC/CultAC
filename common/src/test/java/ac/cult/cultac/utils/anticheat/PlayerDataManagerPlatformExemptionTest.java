package ac.cult.cultac.utils.anticheat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerDataManagerPlatformExemptionTest {
    private final PlayerDataManager manager = new PlayerDataManager();
    private final List<User> users = new ArrayList<>();

    @BeforeEach
    void setUp() {
        OfflineCultTestBootstrap.installConfig();
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        exempt(false, false);
        for (User user : users) {
            manager.onDisconnect(user);
            ((EmbeddedChannel) user.getChannel()).finishAndReleaseAll();
        }
    }

    @Test
    void bothEditionsAreCheckedByDefault() {
        User java = join(javaUuid());
        User bedrock = join(bedrockUuid());
        assertFalse(manager.getPlayer(java).isBedrockMovement());
        assertTrue(manager.getPlayer(bedrock).isBedrockMovement());
        assertTrue(manager.shouldCheck(java));
        assertTrue(manager.shouldCheck(bedrock));
    }

    @Test
    void exemptBedrockPlayersLeavesJavaChecked() throws ReflectiveOperationException {
        exempt(true, false);
        User java = join(javaUuid());
        User bedrock = join(bedrockUuid());
        assertNotNull(manager.getPlayer(java));
        assertNull(manager.getPlayer(bedrock));
        assertTrue(manager.shouldCheck(java));
        assertFalse(manager.shouldCheck(bedrock));
    }

    @Test
    void exemptJavaPlayersLeavesBedrockChecked() throws ReflectiveOperationException {
        exempt(false, true);
        User java = join(javaUuid());
        User bedrock = join(bedrockUuid());
        assertNull(manager.getPlayer(java));
        assertNotNull(manager.getPlayer(bedrock));
        assertFalse(manager.shouldCheck(java));
        assertTrue(manager.shouldCheck(bedrock));
    }

    /** A connection exempted by its edition stays unchecked until it reconnects, like a permission exemption. */
    @Test
    void exemptionTurnedOnReachesConnectedPlayersAndLastsForTheConnection() throws ReflectiveOperationException {
        User java = join(javaUuid());
        User bedrock = join(bedrockUuid());
        exempt(true, false);
        assertTrue(manager.shouldCheck(java));
        assertFalse(manager.shouldCheck(bedrock));
        exempt(false, false);
        assertFalse(manager.shouldCheck(bedrock));
        assertTrue(manager.clearExemptions(bedrock));
        assertTrue(manager.shouldCheck(bedrock));
    }

    private User join(UUID uuid) {
        User user = TestUsers.create(new User.Profile(uuid, "exempt" + users.size()), new EmbeddedChannel());
        users.add(user);
        manager.addUser(user);
        return user;
    }

    private static UUID javaUuid() {
        return UUID.randomUUID();
    }

    /** The UUID form Geyser gives a Bedrock player without Floodgate. */
    private static UUID bedrockUuid() {
        return new UUID(0L, 0x0009_0000_0000_0000L | (System.nanoTime() & 0xffff_ffffL));
    }

    private static void exempt(boolean bedrock, boolean java) throws ReflectiveOperationException {
        BaseConfigManager config = CultAPI.INSTANCE.getConfigManager();
        set(config, "exemptBedrockPlayers", bedrock);
        set(config, "exemptJavaPlayers", java);
    }

    private static void set(BaseConfigManager config, String name, boolean value) throws ReflectiveOperationException {
        Field field = BaseConfigManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(config, value);
    }
}
