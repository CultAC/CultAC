package ac.cult.cultac.cloud.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.platform.api.PlatformPlugin;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.platform.api.ServerFacts;
import ac.cult.cultac.platform.api.manager.PlatformPluginManager;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PredictionDiagnosticsHeaderTest {
    private static final String SERVER_FIELDS =
            "server Paper 1.21.11-123-main@abc1234 (MC: 1.21.11), plugins ViaVersion 5.5.1, GrimAC 2.3.70";

    private static String header(String serverFields) {
        return PredictionDiagnostics.header("0.1.0-HEAD-5611f5f", "1.21.11", "vanilla", 114, serverFields);
    }

    @Test
    void withoutServerFieldsTheHeaderIsUnchanged() {
        assertEquals("agent 0.1.0-HEAD-5611f5f, client 1.21.11, brand vanilla, ping 114 ms", header(null));
    }

    @Test
    void serverFieldsGoAfterPing() {
        String header = header(SERVER_FIELDS);
        assertEquals("agent 0.1.0-HEAD-5611f5f, client 1.21.11, brand vanilla, ping 114 ms, " + SERVER_FIELDS, header);
        assertEquals(
                List.of(
                        "agent 0.1.0-HEAD-5611f5f",
                        "client 1.21.11",
                        "brand vanilla",
                        "ping 114 ms",
                        "server Paper 1.21.11-123-main@abc1234 (MC: 1.21.11)",
                        "plugins ViaVersion 5.5.1",
                        "GrimAC 2.3.70"),
                Arrays.asList(header.split(", ")));
    }

    @Test
    void serverFieldsListTheEnabledKnownPluginsInFixedOrder() {
        PlatformPluginManager plugins = Mockito.mock(PlatformPluginManager.class);
        Mockito.when(plugins.getPlugins()).thenReturn(new PlatformPlugin[] {
            plugin("GrimAC", "2.3.70", true),
            plugin("Essentials", "2.21", true),
            plugin("viaversion", "5.5.1", true),
            plugin("ProtocolLib", "5.3.0", false),
            plugin("packetevents", "2.9.0,dev", true)
        });
        assertEquals(
                "server Paper 1.21.11-123-main@abc1234 (MC: 1.21.11), plugins viaversion 5.5.1, GrimAC 2.3.70,"
                        + " packetevents 2.9.0;dev",
                PredictionDiagnostics.serverFields(server("Paper 1.21.11-123-main@abc1234 (MC: 1.21.11)"), plugins));
    }

    @Test
    void noKnownPluginsReadsNone() {
        PlatformPluginManager plugins = Mockito.mock(PlatformPluginManager.class);
        Mockito.when(plugins.getPlugins()).thenReturn(new PlatformPlugin[] {plugin("Essentials", "2.21", true)});
        assertEquals(
                "server Paper 1.21.11; build 7, plugins none",
                PredictionDiagnostics.serverFields(server("Paper 1.21.11, build 7"), plugins));
    }

    @Test
    void withoutCloudFactsTheSoftwareIsThePlatformString() {
        PlatformServer server = Mockito.mock(PlatformServer.class);
        Mockito.when(server.cloudFacts()).thenReturn(ServerFacts.NONE);
        Mockito.when(server.getPlatformImplementationString()).thenReturn("Velocity 3.4.0");
        PlatformPluginManager plugins = Mockito.mock(PlatformPluginManager.class);
        Mockito.when(plugins.getPlugins()).thenReturn(new PlatformPlugin[0]);
        assertEquals("server Velocity 3.4.0, plugins none", PredictionDiagnostics.serverFields(server, plugins));
    }

    private static PlatformServer server(String software) {
        PlatformServer server = Mockito.mock(PlatformServer.class);
        Mockito.when(server.cloudFacts()).thenReturn(new ServerFacts(50_000, software, Map.of()));
        return server;
    }

    private static PlatformPlugin plugin(String name, String version, boolean enabled) {
        return new PlatformPlugin() {
            @Override
            public boolean isEnabled() {
                return enabled;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getVersion() {
                return version;
            }
        };
    }
}
