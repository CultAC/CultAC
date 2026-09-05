package ac.cult.cultac.parity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParityConformanceTest {
    @TempDir
    Path temp;

    @Test
    void catalogAccountsForEverySharedCheckAndAllRequiredTrials() throws Exception {
        Path catalogPath = Path.of("parity/scenarios/shared-check-scenarios.json");
        if (!Files.isRegularFile(catalogPath)) {
            catalogPath = Path.of("../parity/scenarios/shared-check-scenarios.json");
        }
        JsonObject catalog = ScenarioCatalog.read(catalogPath);
        ScenarioCatalog.Validation validation = ScenarioCatalog.validate(
                catalog,
                Set.of("cult.prediction.simulation", "cult.combat.reach")
        );
        assertFalse(validation.valid(), "the frozen all-check catalog must reject a partial inventory");
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("non-shared checks")));
    }

    @Test
    void reviewedIdentityMappingMakesMovedClassesShared() {
        CheckInventory.RuntimeInventory baseline = new CheckInventory.RuntimeInventory(
                "baseline", List.of(new CheckInventory.Entry(
                        "cult.prediction.simulation", "old.OffsetHandler", "Simulation", "Simulation",
                        true, "", "runtime", "", "")), List.of()
        );
        CheckInventory.RuntimeInventory current = new CheckInventory.RuntimeInventory(
                "current", List.of(new CheckInventory.Entry(
                        "cult.prediction.simulation", "new.OffsetHandler", "Simulation", "Simulation",
                        true, "", "runtime", "", "")), List.of()
        );
        CheckInventory.SourceEntry oldSource = new CheckInventory.SourceEntry(
                "cult.prediction.simulation", "old.OffsetHandler", "old.java", "Check", true, true
        );
        CheckInventory.SourceEntry newSource = new CheckInventory.SourceEntry(
                "cult.prediction.simulation", "new.OffsetHandler", "new.java", "Check", true, true
        );
        CheckInventory.Reconciliation reconciliation = CheckInventory.reconcile(
                baseline, current, List.of(oldSource), List.of(newSource),
                Map.of("cult.prediction.simulation", new CheckInventory.ClassMapping(
                        "old.OffsetHandler", "new.OffsetHandler", "moved-class", "reviewed port move"
                )),
                Map.of()
        );
        assertEquals(List.of("cult.prediction.simulation"),
                reconciliation.shared().stream().map(CheckInventory.Entry::stableKey).toList());
        assertTrue(reconciliation.ambiguous().isEmpty());
        assertTrue(reconciliation.errors().isEmpty());
    }

    @Test
    void unreviewedBlankRuntimeIdentityIsHardFailure() {
        CheckInventory.RuntimeInventory runtime = new CheckInventory.RuntimeInventory(
                "manager", List.of(new CheckInventory.Entry(
                        "", "fixture.UnknownProcessor", "Unknown", "Unknown", true,
                        "", "runtime", "", "")), List.of()
        );
        CheckInventory.Reconciliation reconciliation = CheckInventory.reconcile(
                runtime, runtime, List.of(), List.of(), Map.of(), Map.of()
        );
        assertFalse(reconciliation.ambiguous().isEmpty());
        assertTrue(reconciliation.errors().stream().anyMatch(error -> error.contains("unreviewed blank-key")));
    }

    @Test
    void staleReviewedInputsAreHardFailures() {
        CheckInventory.RuntimeInventory runtime = new CheckInventory.RuntimeInventory(
                "manager", List.of(new CheckInventory.Entry(
                        "cult.a", "fixture.A", "A", "A", true,
                        "", "runtime", "", "")), List.of()
        );
        List<String> errors = CheckInventory.validateReviewedInputs(
                Map.of("cult.missing", new CheckInventory.ClassMapping(
                        "fixture.Missing", "fixture.Missing", "move", "stale")),
                Map.of("fixture.MissingProcessor", new CheckInventory.Classification(
                        "disabled", "stale")),
                runtime,
                runtime,
                List.of(new CheckInventory.SourceEntry("cult.a", "fixture.A", "a.java", "Check", true, true)),
                List.of(new CheckInventory.SourceEntry("cult.a", "fixture.A", "a.java", "Check", true, true))
        );
        assertTrue(errors.stream().anyMatch(error -> error.contains("no discovered stable key")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("no discovered runtime class")));
    }

    @Test
    void normalizerOnlyRemovesExplicitNonsemanticEventIdentity() {
        JsonObject left = JsonParser.parseString("""
                {"kind":"invoke","sequence":1,"state":{"sequence":7,"value":4},"threadId":11}
                """).getAsJsonObject();
        JsonObject right = JsonParser.parseString("""
                {"kind":"invoke","sequence":99,"state":{"sequence":8,"value":4},"threadId":22}
                """).getAsJsonObject();
        assertFalse(TraceComparator.normalize(left, "baseline", Map.of())
                .equals(TraceComparator.normalize(right, "current", Map.of())),
                "nested semantic sequence changes must not be normalized away");

        JsonObject sameLeft = JsonParser.parseString(
                "{\"kind\":\"invoke\",\"sequence\":1,\"value\":4,\"threadId\":11}"
        ).getAsJsonObject();
        JsonObject sameRight = JsonParser.parseString(
                "{\"kind\":\"invoke\",\"sequence\":99,\"value\":4,\"threadId\":22}"
        ).getAsJsonObject();
        assertEquals(
                TraceComparator.normalize(sameLeft, "baseline", Map.of()),
                TraceComparator.normalize(sameRight, "current", Map.of())
        );
    }

    @Test
    void traceAlignmentReportsFirstStimulusDifferenceAndDoesNotInventOutcomeFailures() throws Exception {
        Path baseline = temp.resolve("baseline.jsonl");
        Path current = temp.resolve("current.jsonl");
        Files.writeString(baseline, "{\"kind\":\"packet\",\"sequence\":1,\"className\":\"A\"}\n"
                + "{\"kind\":\"flag\",\"expected\":true}\n");
        Files.writeString(current, "{\"kind\":\"packet\",\"sequence\":8,\"className\":\"B\"}\n"
                + "{\"kind\":\"flag\",\"expected\":true}\n");
        TraceComparator.Result result = TraceComparator.compare(baseline, current, Map.of());
        assertFalse(result.equal());
        assertFalse(result.packetStimuliEqual());
        assertFalse(result.hasUnexpectedFlagsOrCorrections());
        assertEquals(0, result.firstDifference().eventIndex());
    }

    @Test
    void mutatedFixtureDetectsConstantsThresholdsOrderingFieldsCancellationFlagsAndPacketModification() {
        byte[] original = fixture(false, false, false, false, false, 0.001D);
        List<byte[]> mutations = List.of(
                fixture(false, false, false, false, false, 0.002D), // threshold/constant
                fixture(true, false, false, false, false, 0.001D),  // branch ordering
                fixture(false, true, false, false, false, 0.001D),  // state field
                fixture(false, false, true, false, false, 0.001D), // cancellation
                fixture(false, false, false, true, false, 0.001D), // flag
                fixture(false, false, false, false, true, 0.001D)  // packet modification
        );
        StaticParity.Equivalence equivalence = new StaticParity.Equivalence(Map.of());
        StaticParity.ClassSignature source = StaticParity.signature(original, "current", equivalence);
        for (byte[] mutation : mutations) {
            StaticParity.ClassSignature changed = StaticParity.signature(mutation, "current", equivalence);
            assertFalse(source.digest().equals(changed.digest()), "fixture mutation escaped static comparator");
        }
    }

    @Test
    void instrumentationCoverageCanBeProvenFromInvokeEvents() {
        List<JsonObject> events = List.of(
                JsonParser.parseString("{\"kind\":\"invoke\",\"stableKey\":\"cult.a\"}").getAsJsonObject(),
                JsonParser.parseString("{\"kind\":\"invoke\",\"stableKey\":\"cult.a\"}").getAsJsonObject(),
                JsonParser.parseString("{\"kind\":\"other\",\"stableKey\":\"cult.b\"}").getAsJsonObject()
        );
        assertEquals(Set.of("cult.a"), TraceComparator.invokedStableKeys(events));
    }

    private static byte[] fixture(
            boolean reverseBranch,
            boolean extraField,
            boolean cancellation,
            boolean flag,
            boolean packetModification,
            double threshold
    ) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/Check", null, "java/lang/Object", null);
        if (extraField) writer.visitField(Opcodes.ACC_PUBLIC, "mutatedState", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "evaluate", "(D)Z", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.DLOAD, 1);
        method.visitLdcInsn(threshold);
        method.visitInsn(Opcodes.DCMPL);
        Label positive = new Label();
        method.visitJumpInsn(reverseBranch ? Opcodes.IFGT : Opcodes.IFLE, positive);
        if (cancellation) method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "fixture/Event", "cancel", "()V", false);
        if (flag) method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "fixture/Check", "flag", "()V", false);
        if (packetModification) method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "fixture/Packet", "modify", "()V", false);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(positive);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(3, 3);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
