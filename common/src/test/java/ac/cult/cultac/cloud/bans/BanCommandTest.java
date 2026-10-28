package ac.cult.cultac.cloud.bans;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class BanCommandTest {
    private static final UUID PLAYER = UUID.fromString("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b");
    private static final String ID = "0f0e0d0c-0b0a-4908-8706-050403020100";

    private static StaffBans.Ban ban(String name, String reason, Long seconds, String staff) {
        return ban(name, reason, seconds, staff, null, null);
    }

    private static StaffBans.Ban ban(
            String name, String reason, Long seconds, String staff, String staffUuid, String evidence) {
        return new StaffBans.Ban(
                ID,
                PLAYER,
                name,
                reason,
                "killaura",
                "Kill Aura",
                seconds,
                staff,
                0,
                "https://panel/players/" + PLAYER,
                staffUuid,
                evidence);
    }

    @Test
    void durationsUseTheLargestUnitThatDividesEvenly() {
        assertEquals("30m", BanCommand.duration(1800));
        assertEquals("12h", BanCommand.duration(43200));
        assertEquals("7d", BanCommand.duration(604800));
        assertEquals("90m", BanCommand.duration(5400));
        assertEquals("25h", BanCommand.duration(90000));
        assertEquals("90s", BanCommand.duration(90));
        assertEquals("3601s", BanCommand.duration(3601));
    }

    @Test
    void everyPlaceholderIsFilled() {
        String command = BanCommand.fill(
                "tempban %player% %duration% %reason% [%cheat%/%cheat_id%] %seconds%s by %staff% #%ban_id% %link% %uuid%",
                ban("Notch", "flying around", 43200L, "Mod One"));
        assertEquals(
                "tempban Notch 12h flying around [Kill Aura/killaura] 43200s by Mod One #" + ID
                        + " https://panel/players/" + PLAYER + " " + PLAYER,
                command);
    }

    @Test
    void aReasonOrStaffStartingWithADashIsNeverAFlag() {
        assertEquals(
                "ban Notch '-s silent '--Mod 1d grief",
                BanCommand.fill(
                        "ban %player% %reason% %staff% %duration% grief", ban("Notch", "-s silent", 86400L, "--Mod")));
        assertEquals("ban Notch a-b", BanCommand.fill("ban %player% %reason%", ban("Notch", "  a-b", null, "s")));
    }

    @Test
    void aGameplayBanLeavesTheCheatEmpty() {
        StaffBans.Ban gameplay =
                new StaffBans.Ban(
                        ID, PLAYER, "Notch", "griefing", null, null, null, "Mod", 0, "https://panel/x", null, null);
        assertEquals("ban Notch griefing [|]", BanCommand.fill("ban %player% %reason% [%cheat%|%cheat_id%]", gameplay));
    }

    @Test
    void aPermanentBanLeavesDurationAndSecondsEmpty() {
        assertEquals(
                "ban Notch  x", BanCommand.fill("ban %player% %duration%%seconds% x", ban("Notch", "r", null, "s")));
    }

    @Test
    void controlCharactersAndNewlinesGoAndWhitespaceCollapses() {
        String command = BanCommand.fill(
                "ban %player% %reason%",
                ban(
                        "Notch",
                        "  line one\nban Other\r\n\tline\u0000 three   end" + Character.toString(0x2028) + " ",
                        null,
                        "s"));
        assertEquals("ban Notch line oneban Otherline three end", command);
    }

    @Test
    void reasonAndStaffKeepTwoHundredCharacters() {
        String reason = "r".repeat(500);
        String staff = "s".repeat(300);
        String command = BanCommand.fill("%reason%|%staff%", ban("Notch", reason, null, staff));
        assertEquals("r".repeat(200) + "|" + "s".repeat(200), command);
    }

    @Test
    void anUnsafeOrMissingNameFallsBackToTheUuid() {
        assertEquals("ban " + PLAYER, BanCommand.fill("ban %player%", ban("Notch Two", "r", null, "s")));
        assertEquals("ban " + PLAYER, BanCommand.fill("ban %player%", ban("Notch;op", "r", null, "s")));
        assertEquals("ban " + PLAYER, BanCommand.fill("ban %player%", ban(null, "r", null, "s")));
        assertEquals("ban *Bedrock.Name-1_", BanCommand.fill("ban %player%", ban("*Bedrock.Name-1_", "r", null, "s")));
    }

    @Test
    void aStaffNameNeverReadsAsAKey() {
        assertEquals(
                "gban by=Alice reasonx reason=r",
                BanCommand.fill("gban by=%staff% reason=%reason%", ban("Notch", "r", null, "Alice reason=x")));
        assertEquals("by='-x a b", BanCommand.fill("by=%staff%", ban("Notch", "r", null, "=-x = a == b")));
    }

    @Test
    void staffUuidAndEvidenceAreFilledWhenKnown() {
        String staff = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
        String evidence = "https://panel.example/replays/watch?window=w-1_a.b~c&x=%20";
        assertEquals(
                "gban " + staff + " " + evidence,
                BanCommand.fill("gban %staff_uuid% %evidence%", ban("Notch", "r", null, "s", staff, evidence)));
    }

    @Test
    void staffUuidAndEvidenceAreEmptyWhenUnknown() {
        assertEquals("gban staff= evidence= x", BanCommand.fill(
                "gban staff=%staff_uuid% evidence=%evidence% x", ban("Notch", "r", null, "s")));
    }

    @Test
    void staffUuidAndEvidenceDropHostileCharacters() {
        assertEquals(
                "gban s= evidence=https://x.example/a?b=1&c=2op",
                BanCommand.fill(
                        "gban s=%staff_uuid% evidence=%evidence%",
                        ban(
                                "Notch",
                                "r",
                                null,
                                "s",
                                "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d; op",
                                "https://x.example/a?b=1&c=2 ;op\n\"<'`$(é)\\>")));
        assertEquals(
                "s=0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
                BanCommand.fill(
                        "s=%staff_uuid%", ban("Notch", "r", null, "s", "0A1B2C3D-4E5F-4A6B-8C7D-9E0F1A2B3C4D", null)));
        assertEquals(
                "s= e=",
                BanCommand.fill(
                        "s=%staff_uuid% e=%evidence%",
                        ban("Notch", "r", null, "s", "0a1b2c3d4e5f4a6b8c7d9e0f1a2b3c4d", "javascript:alert(1)")));
    }

    private static StaffBans.Ban hostile(String cheat, String cheatName, String link) {
        return new StaffBans.Ban(
                ID, PLAYER, "Notch", "r", cheat, cheatName, null, "Mod", 0, link, null, null);
    }

    @Test
    void noTextButTheReasonCanInjectAKey() {
        for (String space : new String[] {" ", " ", " ", "　", " "}) {
            String injected = "Fly" + space + "reason=forged";
            assertEquals(
                    "gban cheat=Fly reasonforged id=fly reasonforged link=https://p.example/xreason=forged reason=r",
                    BanCommand.fill(
                            "gban cheat=%cheat% id=%cheat_id% link=%link% reason=%reason%",
                            hostile(
                                    "fly" + space + "reason=forged",
                                    injected,
                                    "https://p.example/x" + space + "reason=forged")),
                    "space U+" + Integer.toHexString(space.charAt(0)));
        }
    }

    @Test
    void noPlaceholderStartsWithADash() {
        StaffBans.Ban ban = new StaffBans.Ban(
                ID,
                PLAYER,
                "-s",
                "-r",
                "=-id",
                "-Fly",
                null,
                "-Mod",
                0,
                "-https://p.example/x",
                "--dead",
                "-s https://p.example/r");
        assertEquals(
                PLAYER + "|'-r|'-Fly|'-id|'-Mod|||",
                BanCommand.fill("%player%|%reason%|%cheat%|%cheat_id%|%staff%|%link%|%staff_uuid%|%evidence%", ban));
    }

    @Test
    void aProtectedValueStaysWithinTheCap() {
        String staff = "-" + "a".repeat(199);
        String protectedStaff = BanCommand.fill("%staff%", ban("Notch", "r", null, staff));
        assertEquals("'-" + "a".repeat(198), protectedStaff);
        assertEquals(BanCommand.MAX_TEXT, protectedStaff.length());

        String reason = "-" + "a".repeat(197) + "😀";
        String protectedReason = BanCommand.fill("%reason%", ban("Notch", reason, null, "s"));
        assertEquals("'-" + "a".repeat(197), protectedReason);

        String plain = "a".repeat(199) + "😀";
        assertEquals("a".repeat(199), BanCommand.fill("%reason%", ban("Notch", plain, null, "s")));
        assertEquals("a".repeat(200), BanCommand.fill("%staff%", ban("Notch", "r", null, "a".repeat(200))));
    }

    @Test
    void valuesAreNotExpandedAgainAndUnknownPlaceholdersStay() {
        assertEquals(
                "ban Notch %uuid% %unknown%",
                BanCommand.fill("/ban %player% %reason% %unknown%", ban("Notch", "%uuid%", null, "s")));
    }
}
