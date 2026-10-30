package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RedactionTest {

    private static NetworkSettings redis(String secret, String username, String password) {
        return new NetworkSettings(
                NetworkSettings.REDIS,
                new NetworkSettings.Redis("cache:6379", username, password, false),
                null,
                secret,
                "p3",
                "");
    }

    private static NetworkSettings nats(String secret, String username, String password, String token) {
        return new NetworkSettings(
                NetworkSettings.NATS,
                null,
                new NetworkSettings.Nats("n1:4222", username, password, token, "", false),
                secret,
                "p3",
                "");
    }

    @Test
    void aFailureIsDescribedWithItsCausesAndNoUserInfo() {
        RuntimeException failure = new RuntimeException(
                "publish failed", new IllegalStateException("cannot reach redis://user:hunter22@cache:6379"));
        assertEquals(
                "RuntimeException: publish failed; IllegalStateException: cannot reach redis://***@cache:6379",
                MessageTransport.describe(failure));
        assertEquals("NullPointerException", MessageTransport.describe(new NullPointerException()));
    }

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
    void aUrlRightAfterAColonIsMasked() {
        assertEquals("Bad URL:nats://***@host:4222", Redaction.text("Bad URL:nats://user:example-password@host:4222"));
        assertEquals("1nats://***@h", Redaction.text("1nats://u:pw@h"));
    }

    @Test
    void textAfterAUrlIsNeverTouched() {
        String text = "Cannot connect nats://host:4222; contact admin@example.com for help";
        assertEquals(text, Redaction.text(text));
        assertEquals(text, Redaction.of(redis("", "", "pw")).redact(text));
        assertEquals(
                "nats://***@a:4222 then mail admin@example.com",
                Redaction.text("nats://u:p@a:4222 then mail admin@example.com"));
    }

    @Test
    void theRedisPasswordAndUserPasswordAreHidden() {
        Redaction redaction = Redaction.of(redis("", "default", "p@ss:// w0rd"));
        assertEquals("WRONGPASS *** for default", redaction.redact("WRONGPASS p@ss:// w0rd for default"));
        assertEquals("auth *** failed", redaction.redact("auth default:p@ss:// w0rd failed"));
        assertEquals(
                "password *** rejected",
                Redaction.of(redis("", "", "two words")).redact("password two words rejected"));
    }

    @Test
    void aTokenOrTheSecretInAMessageIsHidden() {
        Redaction redaction = Redaction.of(nats("the network secret", "", "", "s3cr3t-token"));
        assertEquals(
                "Authorization Violation: token *** rejected",
                redaction.redact("Authorization Violation: token s3cr3t-token rejected"));
        assertEquals("signed with ***", redaction.redact("signed with the network secret"));
        Redaction user = Redaction.of(nats("", "a", "nats-pass", ""));
        assertEquals("*** and ***", user.redact("a:nats-pass and nats-pass"));
    }

    @Test
    void onlyTheChosenBrokersCredentialsAreKnown() {
        NetworkSettings bungee = new NetworkSettings(
                NetworkSettings.REDISBUNGEE,
                new NetworkSettings.Redis("cache:6379", "", "unused-pass", false),
                null,
                "",
                "p3",
                "");
        assertEquals("unused-pass", Redaction.of(bungee).redact("unused-pass"));
        assertEquals("redisbungee", Redaction.of(bungee).redact("redisbungee"));
    }

    @Test
    void theLongestValueIsReplacedFirst() {
        Redaction redaction = Redaction.of(nats("hunter2-hunter2", "", "", "hunter2-h"));
        assertEquals("x *** y *** z", redaction.redact("x hunter2-hunter2 y hunter2-h z"));
    }

    @Test
    void valuesShorterThanFourCharactersAreLeftAlone() {
        Redaction redaction = Redaction.of(nats("abc", "u", "pw", ""));
        assertEquals("abc pw u", redaction.redact("abc pw u"));
        assertEquals("cannot reach nats://***@host:4222", redaction.redact("cannot reach nats://u:pw@host:4222"));
    }

    @Test
    void aLineWithoutASecretStaysByteIdentical() {
        Redaction redaction = Redaction.of(nats("the network secret", "a", "nats-pass", ""));
        String line =
                "Network transport NATS: disconnected; reconnecting é ✓ admin@example.com" + " nats://n1:4222 a:b@c";
        assertEquals(line, redaction.redact(line));
        assertEquals("null", redaction.redact(null));
        assertEquals("", redaction.redact(""));
    }

    @Test
    void anAtInThePasswordHidesEverythingUpToTheLastAt() {
        assertEquals("redis://***@cache", Redaction.text("redis://:pa://ss@cache"));
        assertEquals("cannot reach nats://***@", Redaction.text("cannot reach nats://user:pass@"));
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
        assertEquals("a :// b@c", Redaction.text("a :// b@c"));
    }
}
