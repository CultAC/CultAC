package ac.cult.cultac.parity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Inventory and reconciliation primitives used by both the CLI and tests. */
public final class CheckInventory {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS = Pattern.compile(
            "(?m)\\b(?:public\\s+|protected\\s+|private\\s+|abstract\\s+|final\\s+|static\\s+)*"
                    + "class\\s+([A-Za-z_$][\\w$]*)\\s*(?:extends\\s+([A-Za-z_$][\\w$]*))?");
    private static final Pattern CHECK_DATA = Pattern.compile(
            "@CheckData\\s*\\([^)]*?stableKey\\s*=\\s*\\\"([^\\\"]+)\\\"[^)]*\\)",
            Pattern.DOTALL
    );
    private static final Pattern CHECK_INFO = Pattern.compile("\\.stableKey\\s*\\(\\s*\\\"([^\\\"]+)\\\"\\s*\\)");
    private static final Pattern DEAD_CHECK = Pattern.compile(
            "@DeadCheck\\s*\\(\\s*reason\\s*=\\s*DeadCheck\\.Reason\\.([A-Z_]+)"
                    + "(?:\\s*,\\s*detail\\s*=\\s*\\\"([^\\\"]*)\\\")?",
            Pattern.DOTALL
    );

    private CheckInventory() {
    }

    public record Entry(
            String stableKey,
            String runtimeClass,
            String checkName,
            String configName,
            boolean enabled,
            String sourceFile,
            String identityKind,
            String classification,
            String reason
    ) {
        public Entry {
            stableKey = stableKey == null ? "" : stableKey;
            runtimeClass = runtimeClass == null ? "" : runtimeClass;
            checkName = checkName == null ? "" : checkName;
            configName = configName == null ? "" : configName;
            sourceFile = sourceFile == null ? "" : sourceFile;
            identityKind = identityKind == null ? "" : identityKind;
            classification = classification == null ? "" : classification;
            reason = reason == null ? "" : reason;
        }
    }

    public record SourceEntry(
            String stableKey,
            String runtimeClass,
            String sourceFile,
            String directSuperclass,
            boolean annotated,
            boolean hasConfigIdentity,
            boolean deadCheck,
            String deadReason
    ) {
        public SourceEntry(
                String stableKey,
                String runtimeClass,
                String sourceFile,
                String directSuperclass,
                boolean annotated,
                boolean hasConfigIdentity
        ) {
            this(stableKey, runtimeClass, sourceFile, directSuperclass, annotated, hasConfigIdentity, false, "");
        }

        public SourceEntry {
            stableKey = stableKey == null ? "" : stableKey;
            runtimeClass = runtimeClass == null ? "" : runtimeClass;
            sourceFile = sourceFile == null ? "" : sourceFile;
            directSuperclass = directSuperclass == null ? "" : directSuperclass;
            deadReason = deadReason == null ? "" : deadReason;
        }
    }

    public record RuntimeInventory(
            String managerClass,
            List<Entry> entries,
            List<String> errors
    ) {
        public RuntimeInventory {
            entries = List.copyOf(entries == null ? List.of() : entries);
            errors = List.copyOf(errors == null ? List.of() : errors);
        }

        public Set<String> stableKeys() {
            return entries.stream()
                    .map(Entry::stableKey)
                    .filter(key -> !key.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    public record Reconciliation(
            List<Entry> shared,
            List<Entry> baselineOnly,
            List<Entry> currentOnly,
            List<Entry> ambiguous,
            List<Entry> disabled,
            List<String> errors
    ) {
        public Reconciliation {
            shared = List.copyOf(shared);
            baselineOnly = List.copyOf(baselineOnly);
            currentOnly = List.copyOf(currentOnly);
            ambiguous = List.copyOf(ambiguous);
            disabled = List.copyOf(disabled);
            errors = List.copyOf(errors);
        }

        public List<Entry> all() {
            List<Entry> all = new ArrayList<>();
            all.addAll(shared);
            all.addAll(baselineOnly);
            all.addAll(currentOnly);
            all.addAll(ambiguous);
            all.addAll(disabled);
            return List.copyOf(all);
        }
    }

    public static List<SourceEntry> scanSource(Path sourceRoot) throws IOException {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return List.of();
        }

        List<RawSourceClass> raw = new ArrayList<>();
        try (var paths = Files.walk(sourceRoot)) {
            for (Path file : paths.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file);
                String lexicalSource = stripComments(source);
                String structureSource = maskStringAndCharLiterals(lexicalSource);
                Matcher packageMatcher = PACKAGE.matcher(lexicalSource);
                String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
                Matcher classMatcher = CLASS.matcher(lexicalSource);
                while (classMatcher.find()) {
                    String className = classMatcher.group(1);
                    if (className.contains("$")) {
                        continue;
                    }
                    String before = lexicalSource.substring(0, classMatcher.start());
                    String line = before.substring(before.lastIndexOf('\n') + 1).trim();
                    if (line.startsWith("//") || line.startsWith("*")) {
                        continue;
                    }
                    String directSuperclass = classMatcher.group(2);
                    int bodyStart = findOpeningBrace(structureSource, classMatcher.end());
                    int bodyEnd = bodyStart < 0 ? lexicalSource.length()
                            : matchingBrace(structureSource, bodyStart);
                    String stableKey = findStableKey(
                            lexicalSource,
                            classMatcher.start(),
                            classMatcher.end(),
                            bodyStart,
                            bodyEnd
                    );
                    boolean annotated = stableKey != null;
                    DeadMetadata deadMetadata = findDeadMetadata(
                            lexicalSource,
                            classMatcher.start(),
                            bodyStart,
                            bodyEnd
                    );
                    raw.add(new RawSourceClass(
                            packageName.isBlank() ? className : packageName + "." + className,
                            stableKey == null ? "" : stableKey,
                            file.toAbsolutePath().normalize().toString(),
                            directSuperclass == null ? "" : directSuperclass,
                            annotated,
                            hasConfigIdentity(lexicalSource, classMatcher.start(), bodyStart, bodyEnd),
                            deadMetadata != null,
                            deadMetadata == null ? "" : deadMetadata.reason()
                    ));
                }
            }
        }

        Map<String, RawSourceClass> byRuntimeClass = new LinkedHashMap<>();
        Map<String, List<RawSourceClass>> bySimpleName = new LinkedHashMap<>();
        for (RawSourceClass value : raw) {
            byRuntimeClass.putIfAbsent(value.runtimeClass(), value);
            bySimpleName.computeIfAbsent(simpleName(value.runtimeClass()), ignored -> new ArrayList<>()).add(value);
        }
        Map<String, Boolean> checkMemo = new LinkedHashMap<>();
        List<SourceEntry> result = new ArrayList<>();
        for (RawSourceClass value : raw) {
            if (isCheck(value, byRuntimeClass, bySimpleName, checkMemo, 0)) {
                result.add(new SourceEntry(
                        value.stableKey(),
                        value.runtimeClass(),
                        value.sourceFile(),
                        value.directSuperclass(),
                        value.annotated(),
                        value.hasConfigIdentity(),
                        value.deadCheck(),
                        value.deadReason()
                ));
            }
        }
        result.sort(Comparator.comparing(SourceEntry::runtimeClass));
        return List.copyOf(result);
    }

    public static RuntimeInventory readRuntimeInventory(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return new RuntimeInventory("", List.of(), List.of("missing runtime inventory: " + file));
        }

        String managerClass = "";
        int managerRecords = 0;
        List<Entry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                String kind = string(object, "kind");
                if (kind.equals("runtime-manager")) {
                    managerRecords++;
                    managerClass = string(object, "managerClass");
                    JsonArray values = object.getAsJsonArray("entries");
                    if (values == null) {
                        errors.add("runtime-manager record has no entries array");
                        continue;
                    }
                    for (JsonElement value : values) {
                        JsonObject entry = value.getAsJsonObject();
                        entries.add(new Entry(
                                string(entry, "stableKey"),
                                string(entry, "class"),
                                string(entry, "checkName"),
                                string(entry, "configName"),
                                booleanValue(entry, "enabled"),
                                "",
                                "runtime-stable-key",
                                "runtime",
                                ""
                        ));
                    }
                }
            } catch (RuntimeException exception) {
                errors.add("invalid runtime inventory JSONL: " + exception.getMessage());
            }
        }
        if (managerClass.isBlank()) {
            errors.add("runtime inventory has no runtime-manager record");
        } else if (managerRecords != 1) {
            errors.add("runtime inventory must contain exactly one runtime-manager record: " + managerRecords);
        }

        Map<String, Entry> unique = new LinkedHashMap<>();
        for (Entry entry : entries) {
            String key = entry.runtimeClass() + "|" + entry.stableKey();
            if (unique.putIfAbsent(key, entry) != null) {
                errors.add("duplicate runtime check entry: " + key);
            }
        }
        return new RuntimeInventory(managerClass, sorted(unique.values()), errors);
    }

    public static Reconciliation reconcile(
            RuntimeInventory baseline,
            RuntimeInventory current,
            List<SourceEntry> baselineSources,
            List<SourceEntry> currentSources,
            Map<String, ClassMapping> reviewedMappings
    ) {
        return reconcile(baseline, current, baselineSources, currentSources, reviewedMappings, Map.of());
    }

    public static Reconciliation reconcile(
            RuntimeInventory baseline,
            RuntimeInventory current,
            List<SourceEntry> baselineSources,
            List<SourceEntry> currentSources,
            Map<String, ClassMapping> reviewedMappings,
            Map<String, Classification> reviewedClassifications
    ) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(current, "current");
        Map<String, SourceEntry> baselineByClass = baselineSources.stream()
                .collect(Collectors.toMap(SourceEntry::runtimeClass, value -> value, (left, right) -> left));
        Map<String, SourceEntry> currentByClass = currentSources.stream()
                .collect(Collectors.toMap(SourceEntry::runtimeClass, value -> value, (left, right) -> left));

        List<String> errors = new ArrayList<>();
        errors.addAll(baseline.errors());
        errors.addAll(current.errors());
        reportSourceKeyDuplicates("baseline", baselineSources, errors);
        reportSourceKeyDuplicates("current", currentSources, errors);
        Map<String, Entry> baselineByKey = keyEntries(baseline.entries(), "baseline", errors);
        Map<String, Entry> currentByKey = keyEntries(current.entries(), "current", errors);
        List<Entry> shared = new ArrayList<>();
        List<Entry> baselineOnly = new ArrayList<>();
        List<Entry> currentOnly = new ArrayList<>();
        List<Entry> ambiguous = new ArrayList<>();
        List<Entry> disabled = new ArrayList<>();

        for (Entry entry : baseline.entries()) {
            if (entry.stableKey().isBlank()) {
                classifyBlank(entry, "baseline", baselineByClass, reviewedClassifications, disabled, ambiguous, errors);
            }
        }
        for (Entry entry : current.entries()) {
            if (entry.stableKey().isBlank()) {
                classifyBlank(entry, "current", currentByClass, reviewedClassifications, disabled, ambiguous, errors);
            }
        }

        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(baselineByKey.keySet());
        allKeys.addAll(currentByKey.keySet());
        for (String key : allKeys.stream().sorted().toList()) {
            Entry left = baselineByKey.get(key);
            Entry right = currentByKey.get(key);
            if (left == null) {
                currentOnly.add(withClassification(right, "current-only", "stable key absent from baseline runtime manager"));
                continue;
            }
            if (right == null) {
                baselineOnly.add(withClassification(left, "baseline-only", "stable key absent from current runtime manager"));
                continue;
            }

            SourceEntry leftSource = baselineByClass.get(left.runtimeClass());
            SourceEntry rightSource = currentByClass.get(right.runtimeClass());
            if (leftSource == null || rightSource == null) {
                errors.add("runtime/source discovery disagreement for " + key);
                ambiguous.add(withClassification(right, "ambiguous", "runtime class missing from annotated source inventory"));
                continue;
            }
            if (leftSource.deadCheck() || rightSource.deadCheck()) {
                String reason = leftSource.deadCheck() && !leftSource.deadReason().isBlank()
                        ? leftSource.deadReason()
                        : rightSource.deadReason();
                disabled.add(withClassification(right, "disabled",
                        reason.isBlank() ? "source marks runtime check as disabled" : reason));
                continue;
            }
            ClassMapping mapping = reviewedMappings.get(key);
            boolean classEqual = left.runtimeClass().equals(right.runtimeClass());
            boolean mappingMatches = mapping != null
                    && mapping.baselineClass().equals(left.runtimeClass())
                    && mapping.currentClass().equals(right.runtimeClass());
            if (!classEqual && !mappingMatches) {
                errors.add("moved/renamed check lacks reviewed mapping for " + key
                        + ": " + left.runtimeClass() + " -> " + right.runtimeClass());
                ambiguous.add(withClassification(right, "ambiguous", "class identity changed without reviewed mapping"));
                continue;
            }
            shared.add(new Entry(
                    key,
                    right.runtimeClass(),
                    right.checkName(),
                    right.configName(),
                    right.enabled(),
                    rightSource.sourceFile(),
                    mapping == null ? "stable-key" : mapping.rule(),
                    "shared",
                    mapping == null ? "same runtime class and stable key" : mapping.reason()
            ));
        }

        reportSourceOnly("baseline", baselineSources, baselineByKey, reviewedClassifications,
                disabled, ambiguous, errors);
        reportSourceOnly("current", currentSources, currentByKey, reviewedClassifications,
                disabled, ambiguous, errors);

        return new Reconciliation(
                sorted(shared),
                sorted(baselineOnly),
                sorted(currentOnly),
                sorted(ambiguous),
                sorted(disabled),
                List.copyOf(new LinkedHashSet<>(errors))
        );
    }

    private static void classifyBlank(
            Entry entry,
            String side,
            Map<String, SourceEntry> sources,
            Map<String, Classification> reviewedClassifications,
            List<Entry> disabled,
            List<Entry> ambiguous,
            List<String> errors
    ) {
        Classification reviewed = reviewedClassifications.get(entry.runtimeClass());
        SourceEntry source = sources.get(entry.runtimeClass());
        if (reviewed != null) {
            if (!reviewed.classification().equals("disabled")) {
                errors.add("unsupported reviewed blank-key classification for " + entry.runtimeClass()
                        + " on " + side + ": " + reviewed.classification());
            } else {
                disabled.add(withClassification(entry, "disabled", reviewed.reason()));
            }
            return;
        }
        if (source != null && source.deadCheck()) {
            disabled.add(withClassification(entry, "disabled", source.deadReason()));
            return;
        }
        ambiguous.add(withClassification(entry, "ambiguous", "runtime entry has no stable key and no reviewed classification"));
        errors.add("unreviewed blank-key runtime entry on " + side + ": " + entry.runtimeClass());
    }

    private static void reportSourceKeyDuplicates(
            String side,
            List<SourceEntry> sources,
            List<String> errors
    ) {
        sources.stream()
                .filter(source -> !source.stableKey().isBlank() && !source.deadCheck())
                .collect(Collectors.groupingBy(SourceEntry::stableKey, LinkedHashMap::new, Collectors.toList()))
                .forEach((key, values) -> {
                    if (values.size() > 1) {
                        errors.add("duplicate active source stable key on " + side + " for " + key + ": "
                                + values.stream().map(SourceEntry::runtimeClass).sorted().toList());
                    }
                });
    }

    private static void reportSourceOnly(
            String side,
            List<SourceEntry> sources,
            Map<String, Entry> runtimeByKey,
            Map<String, Classification> reviewedClassifications,
            List<Entry> disabled,
            List<Entry> ambiguous,
            List<String> errors
    ) {
        for (SourceEntry source : sources) {
            if (source.stableKey().isBlank() || runtimeByKey.containsKey(source.stableKey())) {
                continue;
            }
            Classification reviewed = reviewedClassifications.get(source.runtimeClass());
            if (source.deadCheck() || (reviewed != null && reviewed.classification().equals("disabled"))) {
                String reason = source.deadCheck() && !source.deadReason().isBlank()
                        ? source.deadReason() : reviewed.reason();
                disabled.add(new Entry(
                        source.stableKey(), source.runtimeClass(), "", "", false,
                        source.sourceFile(), "source-only", "disabled", reason
                ));
                continue;
            }
            Entry entry = new Entry(
                    source.stableKey(), source.runtimeClass(), "", "", false,
                    source.sourceFile(), "source-only", "ambiguous",
                    "annotated source check is absent from the runtime manager"
            );
            ambiguous.add(entry);
            errors.add("source/runtime discovery disagreement on " + side + " for "
                    + source.stableKey() + ": " + source.runtimeClass() + " is not runtime-registered");
        }
    }

    public static Map<String, ClassMapping> readMappings(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("missing reviewed check mapping file: " + file);
        }
        Map<String, ClassMapping> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                continue;
            }
            String[] columns = trimmed.split("\\t", -1);
            if (columns.length != 5 || columns[0].isBlank() || columns[1].isBlank()
                    || columns[2].isBlank() || columns[3].isBlank() || columns[4].isBlank()) {
                throw new IOException("invalid check mapping row (expected 5 tab-separated columns): " + line);
            }
            ClassMapping previous = result.put(columns[0], new ClassMapping(
                    columns[1], columns[2], columns[3], columns[4]
            ));
            if (previous != null) {
                throw new IOException("duplicate reviewed check mapping: " + columns[0]);
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Ensures every reviewed exception is attached to something discovered in
     * the two inputs.  A stale mapping/classification can otherwise silently
     * make a future inventory look reviewed while covering nothing.
     */
    public static List<String> validateReviewedInputs(
            Map<String, ClassMapping> mappings,
            Map<String, Classification> classifications,
            RuntimeInventory baseline,
            RuntimeInventory current,
            List<SourceEntry> baselineSources,
            List<SourceEntry> currentSources
    ) {
        Objects.requireNonNull(mappings, "mappings");
        Objects.requireNonNull(classifications, "classifications");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(current, "current");
        Set<String> knownKeys = new LinkedHashSet<>();
        knownKeys.addAll(baseline.stableKeys());
        knownKeys.addAll(current.stableKeys());
        knownKeys.addAll(baselineSources.stream()
                .map(SourceEntry::stableKey).filter(key -> !key.isBlank()).toList());
        knownKeys.addAll(currentSources.stream()
                .map(SourceEntry::stableKey).filter(key -> !key.isBlank()).toList());

        Set<String> baselineClasses = new LinkedHashSet<>();
        baselineClasses.addAll(baseline.entries().stream().map(Entry::runtimeClass).toList());
        baselineClasses.addAll(baselineSources.stream().map(SourceEntry::runtimeClass).toList());
        Set<String> currentClasses = new LinkedHashSet<>();
        currentClasses.addAll(current.entries().stream().map(Entry::runtimeClass).toList());
        currentClasses.addAll(currentSources.stream().map(SourceEntry::runtimeClass).toList());

        List<String> errors = new ArrayList<>();
        mappings.forEach((key, mapping) -> {
            if (!knownKeys.contains(key)) {
                errors.add("reviewed mapping has no discovered stable key: " + key);
            }
            if (!baselineClasses.contains(mapping.baselineClass())) {
                errors.add("reviewed mapping baseline class is undiscovered for " + key
                        + ": " + mapping.baselineClass());
            }
            if (!currentClasses.contains(mapping.currentClass())) {
                errors.add("reviewed mapping current class is undiscovered for " + key
                        + ": " + mapping.currentClass());
            }
        });
        Set<String> knownClasses = new LinkedHashSet<>(baselineClasses);
        knownClasses.addAll(currentClasses);
        classifications.forEach((runtimeClass, classification) -> {
            if (!knownClasses.contains(runtimeClass)) {
                errors.add("reviewed classification has no discovered runtime class: " + runtimeClass);
            }
            if (!classification.classification().equals("disabled")) {
                errors.add("unsupported reviewed classification for " + runtimeClass
                        + ": " + classification.classification());
            }
        });
        return List.copyOf(new LinkedHashSet<>(errors));
    }

    /** Reads the reviewed classification for runtime entries without a stable key. */
    public static Map<String, Classification> readClassifications(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("missing reviewed check classification file: " + file);
        }
        Map<String, Classification> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                continue;
            }
            String[] columns = trimmed.split("\\t", -1);
            if (columns.length != 3 || columns[0].isBlank() || columns[1].isBlank() || columns[2].isBlank()) {
                throw new IOException("invalid reviewed check classification row (expected 3 tab-separated columns): " + line);
            }
            Classification previous = result.put(columns[0], new Classification(columns[1], columns[2]));
            if (previous != null) {
                throw new IOException("duplicate reviewed check classification: " + columns[0]);
            }
        }
        return Map.copyOf(result);
    }

    public static List<Entry> withSourceFiles(List<Entry> runtime, List<SourceEntry> sources) {
        Map<String, SourceEntry> byClass = sources.stream()
                .collect(Collectors.toMap(SourceEntry::runtimeClass, value -> value, (left, right) -> left));
        return runtime.stream().map(entry -> {
            SourceEntry source = byClass.get(entry.runtimeClass());
            return source == null ? entry : new Entry(
                    entry.stableKey(), entry.runtimeClass(), entry.checkName(), entry.configName(), entry.enabled(),
                    source.sourceFile(), entry.identityKind(), entry.classification(), entry.reason()
            );
        }).toList();
    }

    private static Map<String, Entry> keyEntries(List<Entry> entries, String side, List<String> errors) {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry.stableKey().isBlank()) {
                continue;
            }
            Entry old = result.putIfAbsent(entry.stableKey(), entry);
            if (old != null) {
                errors.add("duplicate stable key in " + side + " runtime inventory: " + entry.stableKey());
            }
        }
        return result;
    }

    private static String findStableKey(
            String source,
            int classStart,
            int classEnd,
            int bodyStart,
            int bodyEnd
    ) {
        int annotationStart = Math.max(0, classStart - 2400);
        Matcher annotation = CHECK_DATA.matcher(source.substring(annotationStart, classStart));
        String annotationKey = null;
        while (annotation.find()) {
            annotationKey = annotation.group(1);
        }
        if (annotationKey != null) {
            return annotationKey;
        }
        int boundedBodyStart = Math.max(0, Math.min(source.length(), bodyStart < 0 ? classEnd : bodyStart));
        int boundedBodyEnd = Math.max(boundedBodyStart, Math.min(source.length(), bodyEnd));
        Matcher info = CHECK_INFO.matcher(source.substring(boundedBodyStart, boundedBodyEnd));
        return info.find() ? info.group(1) : null;
    }

    private static boolean hasConfigIdentity(String source, int classStart, int bodyStart, int bodyEnd) {
        int start = Math.max(0, classStart - 2400);
        int end = Math.max(start, Math.min(source.length(), bodyEnd));
        String window = source.substring(start, end);
        return CHECK_DATA.matcher(window).find() || window.contains("CheckInfo.builder()");
    }

    private static DeadMetadata findDeadMetadata(String source, int classStart, int bodyStart, int bodyEnd) {
        int start = Math.max(0, classStart - 2400);
        int end = Math.max(start, Math.min(source.length(), bodyStart < 0 ? bodyEnd : bodyStart));
        Matcher matcher = DEAD_CHECK.matcher(source.substring(start, end));
        DeadMetadata metadata = null;
        while (matcher.find()) {
            String detail = matcher.group(2);
            metadata = new DeadMetadata(
                    matcher.group(1) + (detail == null || detail.isBlank() ? "" : ": " + detail)
            );
        }
        return metadata;
    }

    private static boolean isCheck(
            RawSourceClass sourceClass,
            Map<String, RawSourceClass> byRuntimeClass,
            Map<String, List<RawSourceClass>> bySimpleName,
            Map<String, Boolean> memo,
            int depth
    ) {
        String className = sourceClass.runtimeClass();
        String directSuperclass = sourceClass.directSuperclass();
        boolean checkBase = className.equals("ac.cult.cultac.checks.Check")
                || className.equals("ac.grim.grimac.checks.Check");
        if (checkBase || depth > 16) {
            return checkBase;
        }
        Boolean known = memo.get(className);
        if (known != null) {
            return known;
        }
        if (directSuperclass.isBlank()) {
            memo.put(className, false);
            return false;
        }
        if (directSuperclass.equals("Check") || directSuperclass.equals("BlockPlaceCheck")
                || directSuperclass.equals("AutoClickCheck")) {
            memo.put(className, true);
            return true;
        }
        RawSourceClass parent = resolveParent(sourceClass, byRuntimeClass, bySimpleName);
        boolean result = parent != null && isCheck(parent, byRuntimeClass, bySimpleName, memo, depth + 1);
        memo.put(className, result);
        return result;
    }

    private static RawSourceClass resolveParent(
            RawSourceClass sourceClass,
            Map<String, RawSourceClass> byRuntimeClass,
            Map<String, List<RawSourceClass>> bySimpleName
    ) {
        String directSuperclass = sourceClass.directSuperclass();
        if (directSuperclass.contains(".")) {
            RawSourceClass exact = byRuntimeClass.get(directSuperclass);
            if (exact != null) {
                return exact;
            }
        }
        int packageEnd = sourceClass.runtimeClass().lastIndexOf('.');
        String samePackage = packageEnd < 0
                ? directSuperclass
                : sourceClass.runtimeClass().substring(0, packageEnd) + "." + directSuperclass;
        RawSourceClass exact = byRuntimeClass.get(samePackage);
        if (exact != null) {
            return exact;
        }
        List<RawSourceClass> candidates = bySimpleName.getOrDefault(directSuperclass, List.of());
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static int findOpeningBrace(String source, int start) {
        for (int index = Math.max(0, start); index < source.length(); index++) {
            if (source.charAt(index) == '{') {
                return index;
            }
        }
        return -1;
    }

    private static int matchingBrace(String source, int openingBrace) {
        int depth = 0;
        for (int index = openingBrace; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return index + 1;
            }
        }
        return source.length();
    }

    private static String stripComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean string = false;
        boolean character = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (lineComment) {
                if (value == '\n' || value == '\r') {
                    lineComment = false;
                    result.append(value);
                } else {
                    result.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (value == '*' && next == '/') {
                    result.append("  ");
                    index++;
                    blockComment = false;
                } else {
                    result.append(value == '\n' || value == '\r' ? value : ' ');
                }
                continue;
            }
            if (!string && !character && value == '/' && next == '/') {
                result.append("  ");
                index++;
                lineComment = true;
                continue;
            }
            if (!string && !character && value == '/' && next == '*') {
                result.append("  ");
                index++;
                blockComment = true;
                continue;
            }
            result.append(value);
            if (escaped) {
                escaped = false;
            } else if ((string || character) && value == '\\') {
                escaped = true;
            } else if (!character && value == '"') {
                string = !string;
            } else if (!string && value == '\'') {
                character = !character;
            }
        }
        return result.toString();
    }

    private static String maskStringAndCharLiterals(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean string = false;
        boolean character = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (string || character) {
                if (value == '\n' || value == '\r') {
                    result.append(value);
                } else {
                    result.append(' ');
                }
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if ((string && value == '"') || (character && value == '\'')) {
                    string = false;
                    character = false;
                }
            } else {
                result.append(value);
                if (value == '"') {
                    string = true;
                } else if (value == '\'') {
                    character = true;
                }
            }
        }
        return result.toString();
    }

    private static String simpleName(String value) {
        int index = value.lastIndexOf('.');
        return index < 0 ? value : value.substring(index + 1);
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static boolean booleanValue(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return false;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return "true".equalsIgnoreCase(value.getAsString());
        }
    }

    private static Entry withClassification(Entry entry, String classification, String reason) {
        return new Entry(
                entry.stableKey(), entry.runtimeClass(), entry.checkName(), entry.configName(), entry.enabled(),
                entry.sourceFile(), entry.identityKind(), classification, reason
        );
    }

    private static List<Entry> sorted(Collection<Entry> entries) {
        return entries.stream()
                .sorted(Comparator.comparing(Entry::stableKey).thenComparing(Entry::runtimeClass))
                .toList();
    }

    private record RawSourceClass(
            String runtimeClass,
            String stableKey,
            String sourceFile,
            String directSuperclass,
            boolean annotated,
            boolean hasConfigIdentity,
            boolean deadCheck,
            String deadReason
    ) {
    }

    private record DeadMetadata(String reason) {
    }

    public record ClassMapping(String baselineClass, String currentClass, String rule, String reason) {
    }

    public record Classification(String classification, String reason) {
    }
}
