package ac.cult.cultac.manager.config.update;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.CollectionNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;

public final class ConfigPatcher {

    private final File file;
    private final List<String> lines;
    private final Map<List<String>, NodePosition> locationMap;
    // Flow collections ({a: 1} or [a, b]) by the literal keys of the node they are, with the text they span.
    private final Map<List<String>, FlowSpan> flowCollections = new HashMap<>();

    // ... (formatValue and YAML_KEYWORDS/SPECIAL_CHARS constants are unchanged) ...
    private static final Set<String> YAML_KEYWORDS_TO_QUOTE = new HashSet<>(Arrays.asList(
            "y", "Y", "yes", "Yes", "YES", "n", "N", "no", "No", "NO", "true", "True", "TRUE", "false", "False",
            "FALSE", "on", "On", "ON", "off", "Off", "OFF", "null", "Null", "NULL", "~"));
    private static final String YAML_SPECIAL_CHARS = ":{}[]#|>&*!%@`'\",-?!^$";

    public ConfigPatcher(File configFile) throws IOException {
        this.file = configFile;
        this.lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
        this.locationMap = new HashMap<>();
        mapFile();
    }

    /** The block-style node at these literal keys, one per map level. */
    public NodePosition getNodePosition(List<String> path) {
        return locationMap.get(path);
    }

    public void applyChange(PendingChange change) {
        if (change.value() instanceof List) {
            replaceListBlock(change.position(), (List<?>) change.value());
        } else {
            replaceScalarValue(change.position(), change.value());
        }
    }

    /** Insert a new setting beneath its closest existing parent, keeping surrounding text. */
    public void addMissingValue(List<String> path, Object value) {
        int parentDepth = path.size() - 1;
        NodePosition parent = null;
        while (parentDepth > 0) {
            parent = locationMap.get(path.subList(0, parentDepth));
            if (parent != null) break;
            parentDepth--;
        }
        Object nested = value;
        for (int i = path.size() - 1; i >= parentDepth; i--) {
            nested = Collections.singletonMap(path.get(i), nested);
        }
        org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions();
        options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        String yaml = new org.yaml.snakeyaml.Yaml(options).dump(nested);
        String indent = " ".repeat(parent == null ? 0 : parent.indent() + 2);
        List<String> inserted = yaml.lines().map(line -> indent + line).toList();
        lines.addAll(parent == null ? lines.size() : parent.lineIndex() + 1, inserted);
        remap();
    }

    /** Whether the node at path is, or lies inside, a flow collection, whose text the line map does not cover. */
    public boolean inFlowCollection(List<String> path) {
        return flowCollectionHolding(path) != null;
    }

    /**
     * Sets path inside the innermost flow collection holding it, rewriting that collection as one flow-style value in
     * place of its text. The text around the collection, its key, anchor and a trailing comment included, stays.
     */
    public void replaceInFlowCollection(List<String> path, Object value) {
        List<String> holder = flowCollectionHolding(path);
        if (holder == null) throw new IllegalArgumentException("no flow collection holds " + path);
        FlowSpan span = flowCollections.get(holder);
        String text = String.join("\n", lines);
        int start = text.offsetByCodePoints(0, span.startIndex());
        int end = text.offsetByCodePoints(0, span.endIndex());
        // Built from the whole document's node: an alias to an anchor outside the collection resolves.
        Object collection = new NodeConstructor().construct(span.node());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        options.setSplitLines(false);
        String flow = new Yaml(options)
                .dump(withValue(collection, path.subList(holder.size(), path.size()), value))
                .strip();
        // The node's text starts at its anchor, which aliases later in the file may name.
        if (span.node().getAnchor() != null) flow = "&" + span.node().getAnchor() + " " + flow;
        String rewritten = text.substring(0, start) + flow + text.substring(end);
        lines.clear();
        lines.addAll(Arrays.asList(rewritten.split("\n", -1)));
        remap();
    }

    /** Builds one composed node's value, its aliases resolved within the document it was composed from. */
    private static final class NodeConstructor extends SafeConstructor {
        NodeConstructor() {
            super(new LoaderOptions());
        }

        Object construct(Node node) {
            return constructDocument(node);
        }
    }

    private List<String> flowCollectionHolding(List<String> path) {
        for (int depth = path.size(); depth >= 0; depth--) {
            List<String> keys = path.subList(0, depth);
            if (flowCollections.containsKey(keys)) return keys;
        }
        return null;
    }

    /** {@code collection} with the value at keys set, the way {@link YamlMap#put} nests missing maps. */
    @SuppressWarnings("unchecked")
    private static Object withValue(Object collection, List<String> keys, Object value) {
        if (keys.isEmpty()) return value;
        Map<Object, Object> map = collection instanceof Map<?, ?> existing
                ? new LinkedHashMap<>((Map<Object, Object>) existing)
                : new LinkedHashMap<>();
        map.put(keys.get(0), withValue(map.get(keys.get(0)), keys.subList(1, keys.size()), value));
        return map;
    }

    /** A flow collection's node, and its text as code point indexes into the lines joined by {@code \n}. */
    private record FlowSpan(Node node, int startIndex, int endIndex) {}

    /**
     * One pending YAML value-replacement, sortable by descending line index
     * so list-block replacements at low line numbers don't invalidate the
     * cached positions of changes at higher line numbers.
     */
    public record PendingChange(NodePosition position, Object value) implements Comparable<PendingChange> {
        @Override
        public int compareTo(PendingChange other) {
            return Integer.compare(other.position.lineIndex(), this.position.lineIndex());
        }
    }

    /** Maps the edited text again, after changes that moved the lines the map points at. */
    public void remap() {
        locationMap.clear();
        flowCollections.clear();
        mapFile();
    }

    private void mapFile() {
        // A line inside a flow collection holds no block-style key, even where it reads like one.
        BitSet insideFlow = mapFlowCollections();
        List<String> pathStack = new ArrayList<>();
        List<Integer> indentStack = new ArrayList<>();
        Pattern keyPattern = Pattern.compile("^(\\s*)([^#:]+):(.*)");

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().isEmpty() || line.trim().startsWith("#") || insideFlow.get(i)) continue;
            Matcher matcher = keyPattern.matcher(line);
            if (matcher.matches()) {
                int currentIndent = matcher.group(1).length();
                String key = literalKey(matcher.group(2).trim());
                while (!indentStack.isEmpty() && currentIndent <= indentStack.get(indentStack.size() - 1)) {
                    pathStack.remove(pathStack.size() - 1);
                    indentStack.remove(indentStack.size() - 1);
                }
                pathStack.add(key);
                indentStack.add(currentIndent);
                locationMap.put(List.copyOf(pathStack), new NodePosition(i, currentIndent));
            }
        }
    }

    /**
     * Records every flow collection, those inside another one included; returns the lines the outermost ones span,
     * other than their key's.
     */
    private BitSet mapFlowCollections() {
        BitSet insideFlow = new BitSet();
        String text = String.join("\n", lines);
        Node root;
        try {
            root = new Yaml().compose(new java.io.StringReader(text));
        } catch (RuntimeException unparseable) {
            return insideFlow;
        }
        if (root != null) mapFlowCollections(root, List.of(), null, text, insideFlow, false);
        return insideFlow;
    }

    private void mapFlowCollections(
            Node node, List<String> keys, Node keyNode, String text, BitSet insideFlow, boolean inFlow) {
        // An alias composes to its anchor's node, written before the key: none of that text is this value's.
        if (keyNode != null
                && node.getStartMark().getIndex() < keyNode.getEndMark().getIndex()) return;
        boolean flow = node instanceof CollectionNode<?> collection
                && collection.getFlowStyle() == DumperOptions.FlowStyle.FLOW;
        if (flow) {
            int start = node.getStartMark().getIndex();
            int end = node.getEndMark().getIndex();
            flowCollections.put(keys, new FlowSpan(node, start, end));
            if (!inFlow) {
                insideFlow.set(line(text, start), line(text, end) + 1);
                if (keyNode != null)
                    insideFlow.clear(line(text, keyNode.getStartMark().getIndex()));
            }
        }
        if (!(node instanceof MappingNode mapping)) return;
        for (NodeTuple tuple : mapping.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode key)) continue;
            List<String> child = new ArrayList<>(keys);
            child.add(key.getValue());
            mapFlowCollections(tuple.getValueNode(), List.copyOf(child), key, text, insideFlow, inFlow || flow);
        }
    }

    /** The line holding a code point index into the lines joined by {@code \n}. */
    private static int line(String text, int codePointIndex) {
        int offset = text.offsetByCodePoints(0, Math.min(codePointIndex, text.codePointCount(0, text.length())));
        int line = 0;
        for (int i = text.indexOf('\n'); i >= 0 && i < offset; i = text.indexOf('\n', i + 1)) line++;
        return line;
    }

    /** The key a quoted YAML key names; a plain key, dots included, is already literal. */
    private static String literalKey(String key) {
        if (key.length() < 2) return key;
        char quote = key.charAt(0);
        if (quote != key.charAt(key.length() - 1)) return key;
        String quoted = key.substring(1, key.length() - 1);
        if (quote == '\'') return quoted.replace("''", "'");
        if (quote == '"') return quoted.replace("\\\"", "\"").replace("\\\\", "\\");
        return key;
    }

    private void replaceScalarValue(NodePosition position, Object value) {
        String line = lines.get(position.lineIndex);
        String valueStr = formatValue(value);
        Pattern linePattern = Pattern.compile("^(\\s*[^:]+:\\s*)(.*?)(\\s*#.*)?$");
        Matcher matcher = linePattern.matcher(line);
        if (matcher.matches()) {
            lines.set(
                    position.lineIndex,
                    matcher.group(1) + valueStr + (matcher.group(3) != null ? matcher.group(3) : ""));
        }
    }

    private void replaceListBlock(NodePosition keyPosition, List<?> newList) {
        // If the key has an inline list on its own line (e.g. `chain: [a, b]`),
        // strip the inline value off the line first so it doesn't co-exist with
        // the block-list items we're about to insert below.
        scrubInlineListFromKeyLine(keyPosition.lineIndex);

        int blockStartLine = -1, blockEndLine = keyPosition.lineIndex + 1, listIndent = -1;
        for (int i = keyPosition.lineIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                if (blockStartLine != -1) blockEndLine = i + 1; // Include comments/blank lines within the block
                continue;
            }
            int currentIndent = getIndentation(line);
            if (currentIndent <= keyPosition.indent) {
                blockEndLine = i;
                break;
            }
            if (line.trim().startsWith("-")) {
                if (blockStartLine == -1) {
                    blockStartLine = i;
                    listIndent = currentIndent;
                }
                blockEndLine = i + 1;
            }
        }
        if (blockStartLine == -1) {
            blockStartLine = keyPosition.lineIndex + 1;
            blockEndLine = blockStartLine;
            listIndent = keyPosition.indent + 2;
        }
        String newBlock = generateListBlock(newList, listIndent);
        if (blockEndLine > blockStartLine) {
            lines.subList(blockStartLine, blockEndLine).clear();
        }
        if (!newBlock.isEmpty()) {
            lines.addAll(blockStartLine, Arrays.asList(newBlock.split("\r?\n")));
        }
    }

    // ... (generateListBlock, formatValue, getIndentation, save, NodePosition are unchanged from previous correct
    // version) ...
    private String generateListBlock(List<?> list, int indentation) {
        if (list.isEmpty()) return "";
        StringBuilder block = new StringBuilder();
        String indentStr = " ".repeat(indentation);
        for (int i = 0; i < list.size(); i++) {
            block.append(indentStr).append("- ").append(formatValue(list.get(i)));
            if (i < list.size() - 1) block.append(System.lineSeparator());
        }
        return block.toString();
    }

    private String formatValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        String s = value.toString();
        if (s.isEmpty()) return "\"\"";
        boolean needsQuotes = false;
        if (YAML_KEYWORDS_TO_QUOTE.contains(s) || s.startsWith(" ") || s.endsWith(" ")) {
            needsQuotes = true;
        } else {
            try {
                if (s.matches("^[+-]?([0-9]*[.])?[0-9]+$")) {
                    Double.parseDouble(s);
                    needsQuotes = true;
                }
            } catch (NumberFormatException e) {
                /* Good */
            }
        }
        if (!needsQuotes) {
            for (char c : YAML_SPECIAL_CHARS.toCharArray()) {
                if (s.indexOf(c) != -1) {
                    needsQuotes = true;
                    break;
                }
            }
        }
        if (needsQuotes) {
            // Double-quoted YAML scalars interpret backslash escapes, so a
            // raw `\d` from a regex round-trips as an "unknown escape"
            // parser error. Escape `\` first so the `\` we inject ahead of
            // any embedded `"` doesn't get re-doubled by the second pass.
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }

    private int getIndentation(String line) {
        for (int i = 0; i < line.length(); i++) if (line.charAt(i) != ' ') return i;
        return 0;
    }

    /**
     * If the key on {@code lineIndex} has an inline list value
     * ({@code key: [a, b, c]}) or an alias ({@code key: *name}), rewrite the
     * line to {@code key:} so the block-list rewrite below the key is its
     * only value. Trailing comments are preserved. Lines without either
     * shape are left untouched.
     */
    private void scrubInlineListFromKeyLine(int lineIndex) {
        String line = lines.get(lineIndex);
        int colonIdx = line.indexOf(':');
        if (colonIdx < 0) return;
        String afterColon = line.substring(colonIdx + 1);
        // Split off any trailing comment so the bracket detection is local
        // to the value portion only.
        int hashIdx = -1;
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < afterColon.length(); i++) {
            char c = afterColon.charAt(i);
            if (inString) {
                if (c == quote && (i == 0 || afterColon.charAt(i - 1) != '\\')) inString = false;
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '#') {
                hashIdx = i;
                break;
            }
        }
        String valuePart = (hashIdx >= 0 ? afterColon.substring(0, hashIdx) : afterColon).trim();
        String trailingComment =
                hashIdx >= 0 ? "  " + afterColon.substring(hashIdx).trim() : "";
        // A plain scalar cannot start with '*', so this is an alias.
        if (valuePart.startsWith("*") || (valuePart.startsWith("[") && valuePart.endsWith("]"))) {
            // Keep "key:" + any trailing comment; let the block-list
            // rewrite happen below.
            lines.set(lineIndex, line.substring(0, colonIdx + 1) + trailingComment);
        }
    }

    /** Writes the patched text, unless it no longer parses; then the file, and its version, stay as they were. */
    public void save() throws IOException {
        try {
            new Yaml().load(String.join("\n", lines));
        } catch (RuntimeException broken) {
            throw new IOException(
                    "patching " + file.getName() + " would leave it unparseable; left it unchanged", broken);
        }
        Path target = file.toPath().toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".cult-config-", ".tmp");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record NodePosition(int lineIndex, int indent) {}
}
