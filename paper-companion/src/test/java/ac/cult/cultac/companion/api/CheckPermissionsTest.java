package ac.cult.cultac.companion.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bridge.protocol.BridgeMessage.CheckInfo;
import ac.cult.cultac.companion.Stubs;
import ac.grim.grimac.api.feature.FeatureState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class CheckPermissionsTest {
    private static CheckInfo check(int idx, String configName) {
        return new CheckInfo(idx, configName, configName, configName, "", configName, 5, 0.05, false, true);
    }

    @Test
    void eachCheckNameRegistersItsThreeNodesOnce() {
        List<Runnable> main = new ArrayList<>();
        List<String> registered = new ArrayList<>();
        var permissions = new CheckPermissions(main::add, registered::add);

        permissions.register(List.of(check(0, "Reach"), check(1, ""), check(2, "Timer")));
        permissions.register(List.of(check(0, "reach")));
        assertTrue(registered.isEmpty());
        assertEquals(1, main.size());

        main.getFirst().run();
        assertEquals(
                List.of(
                        "cult.exempt.reach",
                        "cult.nosetback.reach",
                        "cult.nomodifypacket.reach",
                        "cult.exempt.timer",
                        "cult.nosetback.timer",
                        "cult.nomodifypacket.timer"),
                registered);
    }

    @Test
    void anOperatorWithoutTheNodeSetDoesNotHoldAPerCheckNode() {
        Player op = Stubs.stub(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> UUID.randomUUID();
            case "isPermissionSet" -> args[0].equals("cult.exempt.timer");
            case "hasPermission" -> true;
            default -> null;
        });
        var user =
                new CompanionUser(op, Runnable::run, new CompanionFeatureManager(new WarnOnce(Logger.getLogger("t"))));

        assertFalse(user.hasPermission("cult.exempt.reach"));
        assertFalse(user.hasPermission("cult.nosetback.reach"));
        assertTrue(user.hasPermission("cult.exempt.timer"));
        assertTrue(user.hasPermission("cult.alerts"));
    }

    @Test
    void settingAFeatureHasNoEffect() {
        var features = new CompanionFeatureManager(new WarnOnce(Logger.getLogger("t")));
        assertFalse(features.setFeatureState("ExemptElytra", FeatureState.ENABLED));
        assertFalse(features.isFeatureEnabled("ExemptElytra"));
    }
}
