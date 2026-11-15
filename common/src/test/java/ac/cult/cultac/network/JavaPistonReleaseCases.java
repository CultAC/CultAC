package ac.cult.cultac.network;

import ac.cult.cultac.network.protocol.ClientVersion;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/** Release names remain distinct even where the handshake protocol number is shared. */
final class JavaPistonReleaseCases {
    private JavaPistonReleaseCases() {}

    static Stream<Arguments> releases() {
        return Stream.of(
                Arguments.of("1.21.2", ClientVersion.V_1_21_2),
                Arguments.of("1.21.3", ClientVersion.V_1_21_2),
                Arguments.of("1.21.4", ClientVersion.V_1_21_4),
                Arguments.of("1.21.5", ClientVersion.V_1_21_5),
                Arguments.of("1.21.6", ClientVersion.V_1_21_6),
                Arguments.of("1.21.7", ClientVersion.V_1_21_7),
                Arguments.of("1.21.8", ClientVersion.V_1_21_7),
                Arguments.of("1.21.9", ClientVersion.V_1_21_9),
                Arguments.of("1.21.10", ClientVersion.V_1_21_9),
                Arguments.of("1.21.11", ClientVersion.V_1_21_11),
                Arguments.of("26.1", ClientVersion.V_26_1),
                Arguments.of("26.1.1", ClientVersion.V_26_1),
                Arguments.of("26.1.2", ClientVersion.V_26_1),
                Arguments.of("26.2", ClientVersion.V_26_2),
                Arguments.of("26.3", ClientVersion.V_26_3));
    }
}
