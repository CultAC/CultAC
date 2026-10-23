package ac.cult.cultac.manager.portal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.cloud.intake.v3.PortalRole;
import ac.cult.cultac.cloud.intake.v3.TeamRole;
import ac.cult.cultac.cloud.transport.TeamSocket;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.manager.portal.PortalSync.Offline;
import ac.cult.cultac.manager.portal.PortalSync.OfflineLookup;
import ac.cult.cultac.manager.portal.PortalSync.Online;
import ac.cult.cultac.manager.portal.PortalSync.Report;
import ac.cult.cultac.manager.portal.PortalSync.Role;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PortalSyncTest {
    private static final UUID GOLDEN = UUID.fromString("7445efd5-b580-47d0-b669-bf621fe82396");

    private long now = 1_000_000L;
    private final Map<UUID, Online> online = new HashMap<>();
    private final Map<UUID, Offline> stored = new HashMap<>();
    private final List<UUID> lookedUp = new ArrayList<>();
    private @Nullable OfflineLookup lookup;
    private final List<String> info = new ArrayList<>();

    private final PortalSync sync = new PortalSync(
            new PortalSync.Players() {
                @Override
                public boolean isOnline(UUID uuid) {
                    return online.containsKey(uuid);
                }

                @Override
                public Online check(UUID uuid) {
                    return online.get(uuid);
                }
            },
            () -> now,
            () -> lookup,
            info::add,
            line -> {});

    private static final class FakeSocket implements TeamSocket {
        final String id;
        final List<List<TeamRole>> sent = new ArrayList<>();
        boolean takes = true;
        @Nullable Runnable onSend;

        FakeSocket(String id) {
            this.id = id;
        }

        @Override
        public boolean send(byte[] record) {
            if (onSend != null) onSend.run();
            if (!takes) return false;
            try {
                sent.add(Intake.parseFrom(record).getMessages(0).getTeam().getPlayersList());
            } catch (InvalidProtocolBufferException e) {
                throw new AssertionError(e);
            }
            return true;
        }

        @Override
        public String serverId() {
            return id;
        }

        List<UUID> players(int message) {
            return sent.get(message).stream()
                    .map(role -> IntakeWire.uuid(role.getPlayer()))
                    .toList();
        }
    }

    private final FakeSocket a = new FakeSocket("proxy.a");
    private final FakeSocket b = new FakeSocket("proxy.b");

    private static UUID v4(int n) {
        return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", n));
    }

    private UUID join(int n, @Nullable Role role) {
        UUID uuid = v4(n);
        online.put(uuid, new Online("Player" + n, role));
        sync.checkJoined(uuid);
        return uuid;
    }

    private void useLookup() {
        lookup = uuid -> {
            lookedUp.add(uuid);
            return CompletableFuture.completedFuture(stored.get(uuid));
        };
    }

    @Test
    void oneReportEncodesAsTheGoldenBytes() {
        byte[] golden = {
            0x0a, 0x24, 0x3a, 0x22, 0x0a, 0x20, 0x0a, 0x10, 0x74, 0x45, (byte) 0xef, (byte) 0xd5, (byte) 0xb5,
            (byte) 0x80, 0x47, (byte) 0xd0, (byte) 0xb6, 0x69, (byte) 0xbf, 0x62, 0x1f, (byte) 0xe8, 0x23,
            (byte) 0x96, 0x12, 0x0a, 0x44, 0x69, 0x73, 0x72, 0x65, 0x67, 0x61, 0x72, 0x64, 0x31, 0x18, 0x03
        };
        assertArrayEquals(
                golden, PortalSync.message(List.of(new Report(GOLDEN, "Disregard1", Role.MODERATOR, false))));
    }

    @Test
    void nothingIsSentWithoutASocketThatAnsweredTeam() {
        join(1, Role.ADMIN);
        sync.opened(a, null);
        sync.tick();

        assertTrue(a.sent.isEmpty());
        assertEquals(List.of("Team sync: the cloud does not take it from this key"), info);
    }

    @Test
    void aHolderIsReportedOnceTheSocketAnswersEnabled() {
        sync.opened(a, true);
        UUID admin = join(1, Role.ADMIN);
        join(2, null);
        sync.tick();

        assertEquals(1, a.sent.size());
        TeamRole role = a.sent.get(0).get(0);
        assertEquals(admin, IntakeWire.uuid(role.getPlayer()));
        assertEquals("Player1", role.getName());
        assertEquals(PortalRole.PORTAL_ROLE_ADMIN, role.getRole());
        assertFalse(role.getOffline());
        assertEquals(1, a.sent.get(0).size());
        assertEquals(List.of("Team sync on: reporting portal roles to the cloud"), info);
    }

    @Test
    void playersCheckedWhileOffAreReportedWhenTheStateTurnsEnabled() {
        sync.opened(a, false);
        UUID admin = join(1, Role.ADMIN);
        sync.tick();
        assertTrue(a.sent.isEmpty());

        sync.state(a, true);
        sync.tick();

        assertEquals(List.of(admin), a.players(0));
        assertEquals(
                List.of("Team sync off: the organization has it off", "Team sync on: reporting portal roles to the cloud"),
                info);
    }

    @Test
    void turningDisabledDropsTheQueue() {
        sync.opened(a, true);
        join(1, Role.ADMIN);
        sync.state(a, false);
        sync.tick();

        assertTrue(a.sent.isEmpty());
    }

    @Test
    void aReconnectThatAnswersEnabledReportsEveryWatchedPlayerAgain() {
        sync.opened(a, true);
        UUID admin = join(1, Role.ADMIN);
        join(2, null);
        sync.tick();
        sync.closed(a);

        FakeSocket again = new FakeSocket("proxy.a");
        sync.opened(again, true);
        sync.tick();

        assertEquals(List.of(admin), again.players(0));
        assertEquals(
                List.of(
                        "Team sync on: reporting portal roles to the cloud",
                        "Team sync on: reporting portal roles to the cloud"),
                info);
    }

    @Test
    void aStateThatRepeatsTheAnswerChangesNothing() {
        sync.opened(a, true);
        join(1, Role.ADMIN);
        sync.tick();
        sync.state(a, true);
        sync.tick();
        sync.opened(b, false);
        sync.state(b, false);

        assertEquals(1, a.sent.size());
        assertEquals(List.of("Team sync on: reporting portal roles to the cloud"), info);
    }

    @Test
    void aSecondEnabledSocketLogsNothingNew() {
        sync.opened(a, true);
        sync.opened(b, true);
        sync.closed(a);
        sync.state(b, true);

        assertEquals(List.of("Team sync on: reporting portal roles to the cloud"), info);
    }

    @Test
    void losingTheLastEnabledSocketWhileADisabledOneStaysLogsOff() {
        sync.opened(a, true);
        sync.opened(b, false);
        sync.closed(a);
        sync.state(b, true);

        assertEquals(
                List.of(
                        "Team sync on: reporting portal roles to the cloud",
                        "Team sync off: the organization has it off",
                        "Team sync on: reporting portal roles to the cloud"),
                info);
    }

    @Test
    void anOffAlreadyLoggedIsNotLoggedAgainWhenTheEnabledSocketCloses() {
        sync.opened(a, true);
        sync.state(a, false);
        sync.opened(b, false);
        sync.closed(a);

        assertEquals(
                List.of(
                        "Team sync on: reporting portal roles to the cloud",
                        "Team sync off: the organization has it off"),
                info);
    }

    @Test
    void aLostRoleIsReportedAsNoneAfterAResync() {
        sync.opened(a, true);
        UUID admin = join(1, Role.ADMIN);
        sync.tick();
        sync.closed(a);
        online.put(admin, new Online("Player1", null));

        sync.opened(b, true);
        sync.tick();

        assertEquals(PortalRole.PORTAL_ROLE_NONE, b.sent.get(0).get(0).getRole());
    }

    @Test
    void aDemotionCarriedOnAClosedSocketIsSentAgain() {
        sync.opened(a, true);
        sync.opened(b, true);
        UUID demoted = join(1, Role.MODERATOR);
        sync.tick();
        online.put(demoted, new Online("Player1", null));
        sync.permissionsChanged(demoted);
        sync.tick();
        assertEquals(PortalRole.PORTAL_ROLE_NONE, a.sent.get(1).get(0).getRole());

        sync.closed(a);
        sync.tick();

        assertEquals(List.of(demoted), b.players(0));
        assertEquals(PortalRole.PORTAL_ROLE_NONE, b.sent.get(0).get(0).getRole());
    }

    @Test
    void aQueuedDemotionDroppedWhileOffIsSentWhenSyncComesBack() {
        sync.opened(a, true);
        UUID demoted = join(1, Role.MODERATOR);
        sync.tick();
        online.put(demoted, new Online("Player1", null));
        sync.permissionsChanged(demoted);
        a.takes = false;
        a.onSend = () -> sync.state(a, false);
        sync.tick();

        a.takes = true;
        a.onSend = null;
        sync.state(a, true);
        sync.tick();

        assertEquals(List.of(demoted), a.players(1));
        assertEquals(PortalRole.PORTAL_ROLE_NONE, a.sent.get(1).get(0).getRole());
    }

    @Test
    void aResyncTheFullQueueTurnedAwayIsSentOnceThereIsRoom() {
        sync.opened(a, true);
        sync.opened(b, true);
        UUID staff = join(0, Role.MODERATOR);
        sync.tick();
        for (int n = 1; n <= PortalSync.MAX_QUEUED; n++) join(n, Role.VIEWER);
        sync.closed(a);
        for (int i = 0; i < 10; i++) sync.tick();

        boolean resent = false;
        for (int i = 0; i < b.sent.size(); i++) resent |= b.players(i).contains(staff);
        assertTrue(resent);
    }

    @Test
    void reportsGoOnTheLongestOpenEnabledSocket() {
        sync.opened(a, false);
        sync.opened(b, true);
        FakeSocket c = new FakeSocket("proxy.c");
        sync.opened(c, true);
        join(1, Role.VIEWER);
        sync.tick();
        assertEquals(1, b.sent.size());

        sync.state(a, true);
        join(2, Role.VIEWER);
        sync.tick();

        assertEquals(1, a.sent.size());
        assertTrue(c.sent.isEmpty());
    }

    @Test
    void losingTheSocketThatCarriedReportsResyncsOnAnother() {
        sync.opened(a, true);
        sync.opened(b, true);
        UUID admin = join(1, Role.ADMIN);
        sync.tick();
        assertEquals(1, a.sent.size());

        sync.closed(a);
        sync.tick();

        assertEquals(List.of(admin), b.players(0));
    }

    @Test
    void losingASocketThatCarriedNothingResyncsNothing() {
        sync.opened(a, true);
        sync.opened(b, true);
        join(1, Role.ADMIN);
        sync.tick();

        sync.closed(b);
        sync.tick();

        assertEquals(1, a.sent.size());
    }

    @Test
    void aBatchTheSocketRefusedIsQueuedAgain() {
        sync.opened(a, true);
        UUID admin = join(1, Role.ADMIN);
        a.takes = false;
        sync.tick();
        a.takes = true;
        sync.tick();

        assertEquals(List.of(admin), a.players(0));
    }

    @Test
    void eachTickSendsAtMostOneMessageOfAHundred() {
        sync.opened(a, true);
        for (int n = 1; n <= 250; n++) join(n, Role.VIEWER);

        sync.tick();
        assertEquals(1, a.sent.size());
        assertEquals(PortalSync.MAX_PER_MESSAGE, a.sent.get(0).size());
        sync.tick();
        sync.tick();
        sync.tick();

        assertEquals(List.of(100, 100, 50), a.sent.stream().map(List::size).toList());
    }

    @Test
    void joinsPastTheQueueCapAreSentOnceThereIsRoom() {
        sync.opened(a, true);
        for (int n = 1; n <= 600; n++) join(n, Role.VIEWER);
        for (int i = 0; i < 10; i++) sync.tick();

        Set<UUID> sent = new HashSet<>();
        for (int i = 0; i < a.sent.size(); i++) sent.addAll(a.players(i));
        assertEquals(600, sent.size());
    }

    @Test
    void aRefusedBatchIsKeptWhenTheQueueFilledMeanwhile() {
        sync.opened(a, true);
        for (int n = 1; n <= PortalSync.MAX_QUEUED; n++) join(n, Role.VIEWER);
        a.takes = false;
        a.onSend = () -> {
            for (int n = 501; n <= 600; n++) join(n, Role.VIEWER);
        };
        sync.tick();
        a.takes = true;
        a.onSend = null;
        for (int i = 0; i < 10; i++) sync.tick();

        Set<UUID> sent = new HashSet<>();
        for (int i = 0; i < a.sent.size(); i++) sent.addAll(a.players(i));
        assertEquals(600, sent.size());
    }

    @Test
    void aPermissionChangeReportsTheNewRole() {
        sync.opened(a, true);
        UUID player = join(1, Role.VIEWER);
        sync.tick();
        online.put(player, new Online("Player1", Role.ADMIN));
        sync.permissionsChanged(player);
        sync.tick();

        assertEquals(PortalRole.PORTAL_ROLE_ADMIN, a.sent.get(1).get(0).getRole());
    }

    @Test
    void aSweepReportsStoredRolesOfPlayersNotOnline() {
        useLookup();
        sync.opened(a, true);
        UUID here = join(1, Role.ADMIN);
        UUID away = v4(2);
        UUID unnamed = v4(3);
        stored.put(away, new Offline("Away", null));
        stored.put(unnamed, new Offline("not a name!", Role.VIEWER));
        UUID notV4 = UUID.fromString("00000000-0000-3000-8000-000000000004");

        sync.sweep(a, List.of(here, away, unnamed, notV4));
        sync.tick();

        assertEquals(List.of(away, unnamed), lookedUp);
        List<TeamRole> roles = a.sent.get(0);
        assertEquals(3, roles.size());
        TeamRole awayRole = roles.get(1);
        assertTrue(awayRole.getOffline());
        assertEquals("Away", awayRole.getName());
        assertEquals(PortalRole.PORTAL_ROLE_NONE, awayRole.getRole());
        TeamRole unnamedRole = roles.get(2);
        assertFalse(unnamedRole.hasName());
        assertEquals(PortalRole.PORTAL_ROLE_VIEWER, unnamedRole.getRole());
    }

    @Test
    void withoutALookupASweepIsIgnored() {
        sync.opened(a, true);
        stored.put(v4(2), new Offline("Away", Role.ADMIN));

        sync.sweep(a, List.of(v4(2)));
        sync.tick();

        assertTrue(a.sent.isEmpty());
    }

    @Test
    void oneSweepAnHourIsActedOnWithItsParts() {
        useLookup();
        sync.opened(a, true);
        sync.opened(b, true);

        sync.sweep(a, List.of(v4(1)));
        now += TimeUnit.SECONDS.toMillis(30);
        sync.sweep(a, List.of(v4(2)));
        now += TimeUnit.SECONDS.toMillis(30);
        sync.sweep(b, List.of(v4(3)));
        now += 1;
        sync.sweep(a, List.of(v4(4)));
        now += TimeUnit.MINUTES.toMillis(30);
        sync.sweep(a, List.of(v4(5)));
        assertEquals(List.of(v4(1), v4(2), v4(3)), lookedUp);

        now += TimeUnit.MINUTES.toMillis(29);
        sync.sweep(b, List.of(v4(6)));

        assertEquals(List.of(v4(1), v4(2), v4(3), v4(6)), lookedUp);
    }

    @Test
    void aSweepOnADisabledSocketIsIgnored() {
        useLookup();
        sync.opened(a, false);

        sync.sweep(a, List.of(v4(1)));

        assertTrue(lookedUp.isEmpty());
    }
}
