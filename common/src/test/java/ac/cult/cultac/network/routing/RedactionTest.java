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
    void userInfoWithWhitespaceIsHidden() {
        assertEquals("nats://***@host:4222", Redaction.url("nats://user:two words@host:4222"));
        assertEquals("nats://***@host:4222", Redaction.text("nats://user:two words@host:4222"));
        assertEquals(
                "Bad server URL: nats://***@host:4222 (rejected)",
                Redaction.text("Bad server URL: nats://user:two words@host:4222 (rejected)"));
    }

    @Test
    void anAtInThePasswordHidesEverythingUpToTheLastAt() {
        assertEquals("rediss://***@cache:6380", Redaction.url("rediss://default:p@ss@cache:6380"));
        assertEquals("redis://***@cache", Redaction.url("redis://:pa://ss@cache"));
        assertEquals("redis://***@cache", Redaction.text("redis://:pa://ss@cache"));
        assertEquals("***@cache:6379", Redaction.url("user:p@ss@cache:6379"));
    }

    @Test
    void aUrlWithoutAHostKeepsOnlyItsSchemeAndWhatFollowsItsLastAt() {
        assertEquals("nats://***@", Redaction.url("nats://user:pass@"));
        assertEquals("cannot reach nats://***@", Redaction.text("cannot reach nats://user:pass@"));
        assertEquals("redis://***@:6379", Redaction.url("redis://:pw@:6379"));
    }

    @Test
    void aUrlThatDoesNotParseWithoutAnAtBecomesThePlaceholder() {
        assertEquals(Redaction.HIDDEN, Redaction.url("nats://user:pass"));
        assertEquals(Redaction.HIDDEN, Redaction.url("nats://user:pass host:4222"));
        assertEquals(Redaction.HIDDEN, Redaction.url("user:pass host:4222"));
        assertEquals(
                "Bad server URL " + Redaction.HIDDEN + " host:4222",
                Redaction.text("Bad server URL nats://user:pass host:4222"));
        assertEquals(Redaction.HIDDEN + ", nats://b:4222", Redaction.text("nats://u:p, nats://b:4222"));
    }

    @Test
    void textWithoutUserInfoIsLeftAlone() {
        assertEquals("nats://host:4222", Redaction.text("nats://host:4222"));
        assertEquals("Connection refused", Redaction.text("Connection refused"));
        assertEquals("staff@example.com reported it", Redaction.text("staff@example.com reported it"));
        assertEquals("", Redaction.text(""));
        assertEquals("null", Redaction.text(null));
        assertEquals(
                "cannot reach nats://host:4222: refused", Redaction.text("cannot reach nats://host:4222: refused"));
        assertEquals("rediss://cache:6380/0", Redaction.url("rediss://cache:6380/0"));
        assertEquals("nats://[::1]:4222", Redaction.url("nats://[::1]:4222"));
        assertEquals("localhost:4222", Redaction.url("localhost:4222"));
        assertEquals("", Redaction.url(""));
    }
}
