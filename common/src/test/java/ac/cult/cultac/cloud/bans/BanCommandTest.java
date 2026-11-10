package ac.cult.cultac.cloud.bans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ac.cult.cultac.cloud.bans.StaffBans.Term;
import java.util.List;
import java.util.Map;
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
    void aGameplayBanLeavesOutTheCheat() {
        StaffBans.Ban gameplay =
                new StaffBans.Ban(
                        ID, PLAYER, "Notch", "griefing", null, null, null, "Mod", 0, "https://panel/x", null, null);
        assertEquals("ban Notch griefing", BanCommand.fill("ban %player% %reason% [%cheat%|%cheat_id%]", gameplay));
        assertEquals("", BanCommand.values(gameplay).get("cheat"));
        assertEquals("", BanCommand.values(gameplay).get("cheat_id"));
    }

    @Test
    void aPermanentBanLeavesOutDurationAndSeconds() {
        StaffBans.Ban permanent = ban("Notch", "r", null, "s");
        assertEquals("ban Notch x", BanCommand.fill("ban %player% %duration%%seconds% x", permanent));
        assertEquals("", BanCommand.values(permanent).get("duration"));
        assertEquals("", BanCommand.values(permanent).get("seconds"));
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
    void staffUuidAndEvidenceAreLeftOutWhenUnknown() {
        assertEquals("gban x", BanCommand.fill(
                "gban staff=%staff_uuid% evidence=%evidence% x", ban("Notch", "r", null, "s")));
    }

    @Test
    void staffUuidAndEvidenceDropHostileCharacters() {
        assertEquals(
                "gban evidence=https://x.example/a?b=1&c=2op",
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
                "gban",
                BanCommand.fill(
                        "gban s=%staff_uuid% e=%evidence%",
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
                PLAYER + "|'-r|'-Fly|'-id|'-Mod", BanCommand.fill("%player%|%reason%|%cheat%|%cheat_id%|%staff%", ban));
        Map<String, String> values = BanCommand.values(ban);
        assertEquals("", values.get("link"));
        assertEquals("", values.get("staff_uuid"));
        assertEquals("", values.get("evidence"));
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

    private static StaffBans.Ban v2(
            Term term, Long seconds, String duration, String reason, String reasonLabel, String silentFlag) {
        return new StaffBans.Ban(
                ID,
                PLAYER,
                "Notch",
                reason,
                "killaura",
                "Kill Aura",
                seconds,
                "Mod",
                0,
                "https://panel/players/" + PLAYER,
                null,
                null,
                term,
                duration,
                reasonLabel,
                silentFlag);
    }

    private static String refusal(String template, StaffBans.Ban ban) {
        return assertThrows(BanCommand.Refused.class, () -> BanCommand.fill(template, ban))
                .getMessage();
    }

    @Test
    void theReasonLabelAndTheSilentFlagFill() {
        StaffBans.Ban ban =
                v2(Term.TIMED, 2592000L, "30d", "blacklisted_modifications", "Blacklisted modifications", "-s");
        assertEquals(
                "ban -s Notch 30d blacklisted_modifications Blacklisted modifications",
                BanCommand.fill("ban %silent% %player% %duration% %reason% %reason_label%", ban));
    }

    @Test
    void aMissingReasonLabelIsTheReasonAndOneSentIsCleanedLikeIt() {
        assertEquals(
                "ban Notch fly hacks", BanCommand.fill("ban %player% %reason_label%", ban("Notch", "fly hacks", null, "s")));
        assertEquals(
                "ban Notch '-x typedline", BanCommand.fill(
                        "ban %player% %reason_label%", v2(Term.PERMANENT, null, null, "r", "  -x typed\nline ", "")));
        assertEquals(
                "l".repeat(BanCommand.MAX_TEXT),
                BanCommand.fill("%reason_label%", v2(Term.PERMANENT, null, null, "r", "l".repeat(300), "")));
    }

    @Test
    void aPermanentBanSendsThePolicyTokenAsItsDuration() {
        String template = "ban %player% %duration% seconds=%seconds% %reason%";
        assertEquals("ban Notch perm r", BanCommand.fill(template, v2(Term.PERMANENT, null, "perm", "r", null, "")));
        assertEquals("ban Notch r", BanCommand.fill(template, v2(Term.PERMANENT, null, "", "r", null, "")));
        assertEquals("ban Notch r", BanCommand.fill(template, v2(Term.PERMANENT, null, null, "r", null, "")));
    }

    @Test
    void aBanWhoseLengthTheServerDecidesLeavesOutDurationAndSeconds() {
        StaffBans.Ban ban = v2(Term.SERVER, null, "30d", "exploiting", "Exploit abuse", "");
        assertEquals(
                "tempban Notch exploiting", BanCommand.fill("tempban %player% %duration% s=%seconds% %reason%", ban));
    }

    @Test
    void aTimedBanTakesTheServiceLengthWhenItIsATokenElseFormatsItsSeconds() {
        String template = "tempban %player% %duration% %seconds%";
        assertEquals("tempban Notch 1w 604800", BanCommand.fill(template, v2(Term.TIMED, 604800L, "1w", "r", null, "")));
        for (String unusable : new String[] {null, "", "7 d;op", "x".repeat(33)}) {
            assertEquals(
                    "tempban Notch 7d 604800",
                    BanCommand.fill(template, v2(Term.TIMED, 604800L, unusable, "r", null, "")),
                    String.valueOf(unusable));
        }
    }

    @Test
    void aWordWhosePlaceholderFillsEmptyIsLeftOutWhole() {
        StaffBans.Ban ban = v2(Term.PERMANENT, null, null, "", null, "");
        assertEquals(
                "ban Notch --flag %unknown%x",
                BanCommand.fill(
                        "ban %player% --sender=%staff_uuid% %silent% --flag %reason%:%unknown% %unknown%x [%duration%]",
                        ban));
    }

    @Test
    void whitespaceCollapsesAndTheEndsAreTrimmed() {
        assertEquals(
                "ban Notch fly hacks",
                BanCommand.fill(
                        "  /ban \t %silent%   %player%  %duration%\n %reason%  ", ban("Notch", "fly   hacks", null, "s")));
    }

    @Test
    void aValueThatWouldAddAFlagIsRefused() {
        assertEquals(
                "%reason% has a flag word (- and a letter)",
                refusal("ban %player% %reason%", ban("Notch", "hacking -s", null, "s")));
        assertEquals(
                "%reason% has a flag word (- and a letter)",
                refusal("ban %player% %reason%", ban("Notch", "hacking\u00A0-s", null, "s")));
        assertEquals(
                "%reason_label% has a flag word (- and a letter)",
                refusal(
                        "ban %player% %reason_label%",
                        v2(Term.PERMANENT, null, null, "other", "Other --sender=Console", "")));
        assertEquals(
                "%staff% has a flag word (- and a letter)", refusal("ban %player% by %staff%", ban("Notch", "r", null, "Mod -p")));
        assertEquals(
                "%cheat% has a flag word (- and a letter)",
                refusal("ban %player% %cheat%", hostile("killaura", "Kill -Aura", "https://p.example/x")));
    }

    @Test
    void aReasonStartingWithALengthOrServerWordIsRefused() {
        String template = "ban %player% %reason%";
        for (String reason : List.of("7d fly", "1h30m", "2Weeks x", "server:lobby fly", "SERVER:all")) {
            assertEquals(
                    "%reason% starts with a length or a server: word",
                    refusal(template, ban("Notch", reason, null, "s")),
                    reason);
        }
        assertEquals(
                "%reason_label% starts with a length or a server: word",
                refusal("ban %player% %reason_label%", ban("Notch", "30d", null, "s")));
        assertEquals(
                "ban Notch 30 Days of fly",
                BanCommand.fill("ban %player% %reason_label%", v2(Term.PERMANENT, null, null, "30d", "30 Days of fly", "")));
        for (String reason : List.of("fly 7d", "7 days", "d7", "x server:lobby", "7dx")) {
            assertEquals("ban Notch " + reason, BanCommand.fill(template, ban("Notch", reason, null, "s")), reason);
        }
    }

    @Test
    void aReasonWhoseLengthWordComesOnlyFromCleaningIsRunWithAQuote() {
        assertEquals("ban Notch '73d fly", BanCommand.fill("ban %player% %reason%", ban("Notch", "§73d fly", null, "s")));
        assertEquals("ban Notch '3d", BanCommand.fill("ban %player% %reason_label%", ban("Notch", "<3d", null, "s")));
        assertEquals(
                "ban Notch 'server:lobby x",
                BanCommand.fill("ban %player% %reason%", ban("Notch", "<server:lobby x", null, "s")));
        assertEquals("ban Notch c3d fly", BanCommand.fill("ban %player% %reason%", ban("Notch", "&c3d fly", null, "s")));
    }

    @Test
    void reasonStaffAndCheatLoseTagsAndFormatCodes() {
        StaffBans.Ban ban = new StaffBans.Ban(
                ID,
                PLAYER,
                "Notch",
                "<red>fly</red> &chacks §lnow",
                "killaura",
                "Kill <b>Aura",
                null,
                "&4Mod",
                0,
                null,
                null,
                null);
        assertEquals(
                "ban Notch red>fly/red> chacks lnow Kill b>Aura 4Mod",
                BanCommand.fill("ban %player% %reason% %cheat% %staff%", ban));
        assertEquals(
                "ban Notch fly & hacks, 50% (really)!",
                BanCommand.fill("ban %player% %reason%", ban("Notch", "fly & hacks, 50% (really)!", null, "s")));
    }

    @Test
    void aFormatCodeThatOtherRemovalsExposeIsDroppedToo() {
        StaffBans.Ban ban = new StaffBans.Ban(
                ID,
                PLAYER,
                "Notch",
                "​&c3d fly",
                "killaura",
                "Kill &4Aura",
                null,
                "&=c Mod​",
                0,
                null,
                null,
                null);
        assertEquals("ban Notch c3d fly Kill 4Aura c Mod", BanCommand.fill("ban %player% %reason% %cheat% %staff%", ban));
        assertEquals("ban Notch '73d", BanCommand.fill("ban %player% %reason%", ban("Notch", "​&73d", null, "s")));
        assertEquals(
                "ban Notch '-s x", BanCommand.fill("ban %player% %reason%", ban("Notch", "​<-s x", null, "s")));
    }

    @Test
    void cleanedTextIsAFixedPoint() {
        for (String text : List.of(
                "​&c3d fly",
                "&=c",
                "a &4b",
                "<&<cx",
                "§&§7 & &&ab",
                "x &k y",
                " ​ <&c> ")) {
            for (boolean keyless : new boolean[] {false, true}) {
                String once = BanCommand.plain(text, keyless, BanCommand.MAX_TEXT);
                assertEquals(once, BanCommand.plain(once, keyless, BanCommand.MAX_TEXT), text);
            }
        }
    }

    @Test
    void aReasonWithANameEqualsWordIsRefusedOnlyInAKeyValueTemplate() {
        String keyed = "gban target=%uuid% by=%staff% reason=%reason%";
        assertEquals(
                "%reason% has a name= word, which this key=value command would read as an option",
                refusal(keyed, ban("Notch", "x target=" + PLAYER, null, "s")));
        assertEquals(
                "%reason% has a name= word, which this key=value command would read as an option",
                refusal(keyed, ban("Notch", "_a-1=b", null, "s")));
        assertEquals(
                "%reason_label% has a name= word, which this key=value command would read as an option",
                refusal(
                        "gban target=%uuid% label=%reason_label%",
                        v2(Term.PERMANENT, null, null, "other", "Other by=Console", "")));
        assertEquals(
                "gban target=" + PLAYER + " by=s reason=2+2=4 x = y",
                BanCommand.fill(keyed, ban("Notch", "2+2=4 x = y", null, "s")));
        assertEquals(
                "ban Notch x target=forged", BanCommand.fill("ban %player% %reason%", ban("Notch", "x target=forged", null, "s")));
    }

    @Test
    void onlyThePlaceholdersATemplateUsesAreCheckedAndALeadingDashIsQuoted() {
        assertEquals("ban Notch", BanCommand.fill("ban %player%", ban("Notch", "hacking -s", null, "Mod -p")));
        assertEquals("ban Notch '-s hacking", BanCommand.fill("ban %player% %reason%", ban("Notch", "-s hacking", null, "s")));
    }

    @Test
    void theSilentFlagIsOneFlagStartingWithADash() {
        String template = "ban %silent% %player%";
        assertEquals("ban -s Notch", BanCommand.fill(template, v2(Term.PERMANENT, null, null, "r", null, "-s")));
        assertEquals(
                "ban --silent Notch", BanCommand.fill(template, v2(Term.PERMANENT, null, null, "r", null, "--silent")));
        assertEquals("ban Notch", BanCommand.fill(template, v2(Term.PERMANENT, null, null, "r", null, "")));
        for (String bad : new String[] {"s", "-s -p", "-s x", "-s;op", "-" + "s".repeat(32)}) {
            assertEquals(
                    "%silent% is not one flag starting with -",
                    refusal(template, v2(Term.PERMANENT, null, null, "r", null, bad)),
                    bad);
        }
        assertEquals("ban Notch", BanCommand.fill("ban %player%", v2(Term.PERMANENT, null, null, "r", null, "s")));
    }

    @Test
    void aBareDashOrANegativeNumberIsNotAFlag() {
        assertEquals("ban Notch -1", BanCommand.fill("ban %player% %duration%", v2(Term.PERMANENT, null, "-1", "r", null, "")));
        assertEquals(
                "ban Notch fly - see replay",
                BanCommand.fill("ban %player% %reason%", ban("Notch", "fly - see replay", null, "s")));
        assertEquals(
                "%reason% has a flag word (- and a letter)",
                refusal("ban %player% %reason%", ban("Notch", "fly -s", null, "s")));
    }

    @Test
    void aPermanentTokenThatIsAFlagIsRefused() {
        assertEquals(
                "%duration% has a flag word (- and a letter)",
                refusal("ban %player% %duration%", v2(Term.PERMANENT, null, "-perm", "r", null, "")));
        assertEquals(
                "ban Notch", BanCommand.fill("ban %player% %duration%", v2(Term.PERMANENT, null, "perm -s", "r", null, "")));
    }

    @Test
    void aCommandThatFillsToNothingIsRefused() {
        assertEquals("the command is empty once filled", refusal("%reason%", ban("Notch", "", null, "s")));
        assertEquals("the command is empty once filled", refusal("/ %silent%", ban("Notch", "r", null, "s")));
    }

    @Test
    void aBanFromAServiceWithoutTheNewFieldsFillsAsBefore() {
        StaffBans.Ban permanent = ban("Notch", "fly hacks", null, "Mod");
        StaffBans.Ban timed = ban("Notch", "fly hacks", 43200L, "Mod");
        assertEquals(Term.PERMANENT, permanent.term());
        assertEquals(Term.TIMED, timed.term());
        assertNull(timed.duration());
        assertNull(timed.reasonLabel());
        assertEquals("", timed.silentFlag());

        Map<String, String> permanentValues = BanCommand.values(permanent);
        assertEquals("", permanentValues.get("duration"));
        assertEquals("", permanentValues.get("seconds"));
        assertEquals("fly hacks", permanentValues.get("reason_label"));
        assertEquals("", permanentValues.get("silent"));
        Map<String, String> timedValues = BanCommand.values(timed);
        assertEquals("12h", timedValues.get("duration"));
        assertEquals("43200", timedValues.get("seconds"));
        assertEquals(
                "tempban Notch 12h fly hacks [Kill Aura/killaura] 43200s by Mod #" + ID,
                BanCommand.fill("tempban %player% %duration% %reason% [%cheat%/%cheat_id%] %seconds%s by %staff% #%ban_id%", timed));
    }
}
