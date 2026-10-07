package ac.cult.cultac.events.packets.listeners;

import static org.junit.Assert.assertEquals;

import ac.cult.cultac.network.PacketHandlerScanner;
import ac.cult.cultac.network.TestProtocolRuntime;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class CheckManagerListenerReceiveCatalogTest {
    @Test
    public void decodedPlayCatalogIncludesEveryModelServerboundWireId() {
        var version = ProtocolVersion.V26_3;
        var runtime = TestProtocolRuntime.create(ProtocolData.load(version));
        var scanner = new PacketHandlerScanner(runtime);
        var families = CheckManagerListener.receiveDispatchPacketTypes(scanner);
        Set<String> expected = new HashSet<>();
        var packets = runtime.data().packets(ConnectionPhase.PLAY, ac.cult.cultac.protocol.PacketDirection.SERVERBOUND);
        for (int id = 0; id < packets.size(); id++) expected.add(packets.name(id));
        Set<String> actual = new HashSet<>();
        for (var family : families) {
            if (family.phases().contains(ConnectionPhase.PLAY)) actual.addAll(family.wireNames(runtime.data()));
        }
        assertEquals(expected, actual);
        assertEquals(families.size(), new HashSet<>(families).size());
    }
}
