package ac.cult.cultac.platform.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OnlineModeTest {
    @Test
    void aServerThatVerifiesLoginsItselfIsAuthenticated() {
        assertTrue(OnlineMode.authenticated(true, false, false, false, false));
    }

    @Test
    void aCrackedServerWithNoProxyIsNot() {
        assertFalse(OnlineMode.authenticated(false, false, true, false, true));
    }

    @Test
    void velocityForwardingCountsOnlyWhileTheProxyIsInOnlineMode() {
        assertTrue(OnlineMode.authenticated(false, false, false, true, true));
        assertFalse(OnlineMode.authenticated(false, false, false, true, false));
    }

    @Test
    void bungeeForwardingCountsOnlyWhileTheProxyIsInOnlineMode() {
        assertTrue(OnlineMode.authenticated(false, true, true, false, false));
        assertFalse(OnlineMode.authenticated(false, true, false, false, false));
    }
}
