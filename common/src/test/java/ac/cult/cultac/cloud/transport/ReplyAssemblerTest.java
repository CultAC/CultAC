package ac.cult.cultac.cloud.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/** A reply gathered from fragments is bounded, so a service that never ends a message cannot fill the heap. */
class ReplyAssemblerTest {
    @Test
    void gathersFragmentsIntoOneMessage() {
        var assembler = new CloudConnection.ReplyAssembler(8);
        assertTrue(assembler.append(ByteBuffer.wrap(new byte[] {1, 2, 3})));
        assertTrue(assembler.append(ByteBuffer.wrap(new byte[] {4, 5})));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, assembler.finish());
        assertEquals(0, assembler.finish().length);
    }

    @Test
    void refusesAMessagePastTheLimitAndStartsOver() {
        var assembler = new CloudConnection.ReplyAssembler(8);
        assertTrue(assembler.append(ByteBuffer.wrap(new byte[6])));
        assertFalse(assembler.append(ByteBuffer.wrap(new byte[3])));
        assertTrue(assembler.append(ByteBuffer.wrap(new byte[] {7})));
        assertArrayEquals(new byte[] {7}, assembler.finish());
    }
}
