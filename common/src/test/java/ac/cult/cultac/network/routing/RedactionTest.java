package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RedactionTest {

    @Test
    void theUserInfoOfEachUrlIsHidden() {
        assertEquals("nats://***@host:4222", Redaction.text("nats://user:pass@host:4222"));
        assertEquals("redis://***@cache:6379/0", Redaction.text("redis://:pw@cache:6379/0"));
        assertEquals("rediss://***@cache:6380", Redaction.text("rediss://default:p@ss@cache:6380"));
        assertEquals("tls://***@host:4443/x?y=1", Redaction.text("tls://token@host:4443/x?y=1"));
    }

    @Test
    void urlsInsideAnExceptionMessageAreHiddenToo() {
        assertEquals(
                "Unable to connect to NATS servers: [nats://***@a:4222, nats://b:4222, nats://***@c:4222]",
                Redaction.text("Unable to connect to NATS servers: [nats://u:secret@a:4222, nats://b:4222,"
                        + " nats://v:other@c:4222]"));
        assertEquals(
                "Failed connecting to redis://***@cache:6379 (timed out)",
                Redaction.text("Failed connecting to redis://:hunter2@cache:6379 (timed out)"));
    }

    @Test
    void textWithoutUserInfoIsLeftAlone() {
        assertEquals("nats://host:4222", Redaction.text("nats://host:4222"));
        assertEquals("Connection refused", Redaction.text("Connection refused"));
        assertEquals("staff@example.com reported it", Redaction.text("staff@example.com reported it"));
        assertEquals("", Redaction.text(""));
        assertEquals("null", Redaction.text(null));
    }
}
