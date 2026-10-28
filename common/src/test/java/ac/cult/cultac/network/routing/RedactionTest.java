package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RedactionTest {

    private static Redaction configured(String... urls) {
        return Redaction.of(List.of(urls), List.of());
    }

    private static NetworkSettings settings(String secret, String redisUri, List<String> natsServers, String token) {
        return new NetworkSettings("nats", "p3", "default", secret, redisUri, natsServers, token, "", "");
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
        String url = "nats://user:example-password@host:4222";
        assertEquals("Bad URL:nats://***@host:4222", Redaction.text("Bad URL:" + url));
        assertEquals("Bad URL:nats://***@host:4222", configured(url).redact("Bad URL:" + url));
        assertEquals("1nats://***@h", Redaction.text("1nats://u:pw@h"));
    }

    @Test
    void textAfterAUrlIsNeverTouched() {
        String text = "Cannot connect nats://host:4222; contact admin@example.com for help";
        assertEquals(text, Redaction.text(text));
        assertEquals(text, configured("nats://host:4222").redact(text));
        assertEquals(
                "nats://***@a:4222 then mail admin@example.com",
                Redaction.text("nats://u:p@a:4222 then mail admin@example.com"));
    }

    @Test
    void aConfiguredUrlThatDoesNotParseIsReplacedWhole() {
        String url = "nats://user:two words host:4222";
        Redaction redaction = configured(url);
        assertEquals(Redaction.HIDDEN, redaction.redact(url));
        assertEquals(
                "Bad server URL " + Redaction.HIDDEN + " (rejected)",
                redaction.redact("Bad server URL " + url + " (rejected)"));
        assertEquals(
                Redaction.HIDDEN + ", nats://b:4222", configured("nats://u:p").redact("nats://u:p, nats://b:4222"));
        assertEquals(url, Redaction.text(url));
    }

    @Test
    void configuredUserInfoWithWhitespaceIsHidden() {
        String url = "nats://user:two words@host:4222";
        Redaction redaction = configured(url);
        assertEquals("nats://***@host:4222", Redaction.url(url));
        assertEquals("nats://***@host:4222", redaction.redact(url));
        assertEquals(
                "Bad server URL: nats://***@host:4222 (rejected)",
                redaction.redact("Bad server URL: " + url + " (rejected)"));
        assertEquals("password *** rejected", redaction.redact("password two words rejected"));
    }

    @Test
    void aPasswordHoldingAnAtASchemeSeparatorAndSpacesIsHidden() {
        String url = "redis://default:p@ss:// w0rd@cache:6379";
        Redaction redaction = configured(url);
        assertEquals("redis://***@cache:6379", Redaction.url(url));
        assertEquals(
                "Failed connecting to redis://***@cache:6379 (refused)",
                redaction.redact("Failed connecting to " + url + " (refused)"));
        assertEquals("WRONGPASS *** for default", redaction.redact("WRONGPASS p@ss:// w0rd for default"));
        assertEquals("auth *** failed", redaction.redact("auth default:p@ss:// w0rd failed"));
    }

    @Test
    void aPercentEncodedPasswordIsHiddenEncodedAndDecoded() {
        Redaction redaction = configured("redis://default:p%40ssw0rd@cache:6379");
        assertEquals("redis://***@cache:6379", redaction.redact("redis://default:p%40ssw0rd@cache:6379"));
        assertEquals("WRONGPASS *** and ***", redaction.redact("WRONGPASS p@ssw0rd and p%40ssw0rd"));
    }

    @Test
    void aTokenOrTheSecretInAMessageWithoutAUrlIsHidden() {
        Redaction redaction =
                Redaction.of(settings("the network secret", "", List.of("nats://host:4222"), "s3cr3t-token"));
        assertEquals(
                "Authorization Violation: token *** rejected",
                redaction.redact("Authorization Violation: token s3cr3t-token rejected"));
        assertEquals("signed with ***", redaction.redact("signed with the network secret"));
    }

    @Test
    void everyConfiguredUrlIsKnown() {
        Redaction redaction = Redaction.of(settings(
                "", "redis://:redis-pass@cache:6379", List.of("nats://a:nats-pass@n1:4222", "tls://n2-token@n2"), ""));
        assertEquals(
                "redis://***@cache:6379 nats://***@n1:4222 tls://***@n2 *** *** ***",
                redaction.redact("redis://:redis-pass@cache:6379 nats://a:nats-pass@n1:4222 tls://n2-token@n2"
                        + " redis-pass nats-pass n2-token"));
    }

    @Test
    void theLongestValueIsReplacedFirst() {
        Redaction redaction = Redaction.of(settings("hunter2-hunter2", "", List.of(), "hunter2-h"));
        assertEquals("x *** y *** z", redaction.redact("x hunter2-hunter2 y hunter2-h z"));
    }

    @Test
    void valuesShorterThanFourCharactersAreLeftAlone() {
        Redaction redaction = Redaction.of(settings("", "", List.of("nats://u:pw@host:4222"), "abc"));
        assertEquals("abc pw u", redaction.redact("abc pw u"));
        assertEquals("cannot reach nats://***@host:4222", redaction.redact("cannot reach nats://u:pw@host:4222"));
    }

    @Test
    void aLineWithoutASecretStaysByteIdentical() {
        Redaction redaction = Redaction.of(settings(
                "the network secret",
                "redis://:redis-pass@cache:6379",
                List.of("nats://a:nats-pass@n1:4222"),
                "s3cr3t-token"));
        String line =
                "Network transport NATS: disconnected; reconnecting é ✓ admin@example.com" + " nats://n1:4222 a:b@c";
        assertEquals(line, redaction.redact(line));
        assertEquals("null", redaction.redact(null));
        assertEquals("", redaction.redact(""));
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
        assertEquals("rediss://cache:6380/0", Redaction.url("rediss://cache:6380/0"));
        assertEquals("nats://[::1]:4222", Redaction.url("nats://[::1]:4222"));
        assertEquals("localhost:4222", Redaction.url("localhost:4222"));
        assertEquals("", Redaction.url(""));
    }
}
