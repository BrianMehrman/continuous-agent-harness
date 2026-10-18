package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.workspace.SnapshotHasher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure fold of completed turns into the bounded, model-visible conversation (format v2). */
public final class WorkingMemory {
    static final int RECENT_TURNS = 2;
    static final int ERROR_LINES = 20;
    static final int ERROR_ENTRY_CHARS = 400;
    static final int REJECTED_KEPT = 5;
    static final int NOTE_FIELD_CHARS = 700;
    static final int READ_KEEP_CHARS = 1000;
    private static final int CHARS_PER_TOKEN = 3;
    private static final Pattern ERROR = Pattern.compile("\\.java:\\d+: error:|\\bFAILED\\b|AssertionFailedError");
    private static final Pattern CONTEXT = Pattern.compile("^\\s+(symbol|location|required|found|reason):");
    private static final Pattern PROGRESS = Pattern.compile("(?im)^\\s*Progress:\\s*(.+)$");
    private static final Pattern NEXT = Pattern.compile("(?im)^\\s*Next:\\s*(.+)$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Conversation(int version, String runId, Budget budget, String system, String task,
            State state, List<Turn> recent, Compacted compacted) {}
    public record Budget(int contextTokens, int outputTokens, int maxInputTokens) {}
    public record State(int turn, List<FileState> files, CheckState lastCheck, List<Rejection> rejected, Note note,
            Progress progress) {}
    public record Thresholds(int repeatedFailure, int repeatedContent, int noNewFacts) {
        public static Thresholds defaults() { return new Thresholds(3, 3, 6); }
    }
    public record StallVerdict(String signal, int sinceTurn, List<String> evidence) {}
    public record Progress(Thresholds thresholds, List<String> checks, boolean writeSinceCheck,
            Map<String, List<String>> writeHistory, int nonProgressWrites, List<String> recentWrites,
            int lastNewFactTurn, List<String> seen, List<String> nudged, List<String> hints,
            String fired, StallVerdict stall) {
        static Progress empty(Thresholds thresholds, int turn) {
            return new Progress(thresholds, List.of(), false, Map.of(), 0, List.of(), turn,
                    List.of(), List.of(), List.of(), null, null);
        }
    }
    static final int HISTORY_PER_PATH = 16;
    static final int SEEN_KEPT = 128;
    static final int WRITES_KEPT = 8;
    static final int EVIDENCE_ERRORS = 5;
    private static final Pattern POSITION = Pattern.compile(":\\d+(:\\d+)?(?=:)");
    public record FileState(String path, String sha256, boolean written, int turn) {}
    public record CheckState(String tool, String outcome, Integer exitCode, List<String> errors, String receiptRef) {}
    public record Rejection(int turn, String tool, String code, String detail) {}
    public record Note(int turn, String progress, String next) {}
    public record Turn(int turn, List<Map<String, Object>> messages) {}
    public record Compacted(int turns, List<String> modelAttempts) {}

    private WorkingMemory() {}

    public static Conversation start(String runId, String system, String task, int contextTokens, int outputTokens) {
        return start(runId, system, task, contextTokens, outputTokens, Thresholds.defaults());
    }

    public static Conversation start(String runId, String system, String task, int contextTokens, int outputTokens,
            Thresholds thresholds) {
        int max = (int) ((contextTokens - outputTokens) * 0.9);
        return new Conversation(2, runId, new Budget(contextTokens, outputTokens, max), system, task,
                new State(0, List.of(), null, List.of(), null, Progress.empty(thresholds, 0)), List.of(),
                new Compacted(0, List.of()));
    }

    /** Restart stall counters after an operator resume; a hint also clears nudges and enters the conversation. */
    public static Conversation resume(Conversation memory, String hint) {
        State state = memory.state();
        Progress old = state.progress() == null ? Progress.empty(Thresholds.defaults(), state.turn()) : state.progress();
        var hints = new ArrayList<>(old.hints());
        if (hint != null) hints.add(hint.startsWith("Operator:") ? hint : "Operator: " + hint);
        Progress restarted = new Progress(old.thresholds(), List.of(), false, old.writeHistory(), 0, List.of(),
                state.turn(), old.seen(), hint == null ? old.nudged() : List.of(), List.copyOf(hints), null, null);
        State next = new State(state.turn(), state.files(), state.lastCheck(), state.rejected(), state.note(), restarted);
        var turns = new ArrayList<>(memory.recent());
        if (hint != null) turns.add(new Turn(state.turn(), List.of(message("user", hints.getLast()))));
        Conversation resumed = keep(memory, next, turns, RECENT_TURNS + (hint == null ? 0 : 1));
        return fits(resumed) ? resumed : keep(memory, next, turns, 1);
    }

    public static String nudgeSignal(Conversation memory) {
        return memory.state().progress() == null ? null : memory.state().progress().fired();
    }

    public static StallVerdict stall(Conversation memory) {
        return memory.state().progress() == null ? null : memory.state().progress().stall();
    }

    public static Conversation fold(Conversation previous, ModelReply reply, List<ToolOutcome> outcomes) {
        int turn = previous.state().turn() + 1;
        State state = update(previous.state(), turn, reply, outcomes);
        var turns = new ArrayList<>(previous.recent());
        turns.add(new Turn(turn, messages(reply, outcomes, state)));
        Conversation next = keep(previous, state, turns, RECENT_TURNS);
        if (fits(next)) return next;
        next = keep(previous, state, turns, 1);
        if (fits(next)) return next;
        next = mapRecent(next, WorkingMemory::elideWrites);
        if (fits(next)) return next;
        return mapRecent(next, WorkingMemory::shortenReads); // callModel reports overflow if this still does not fit
    }

    public static Map<String, Object> render(Conversation memory) {
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(message("system", memory.system()));
        messages.add(message("user", memory.task()));
        if (memory.state().turn() > 0) messages.add(message("user", stateText(memory)));
        memory.recent().forEach(turn -> messages.addAll(turn.messages()));
        var root = new LinkedHashMap<String, Object>();
        root.put("version", 1);
        root.put("messages", messages);
        return root;
    }

    public static int estimateTokens(Conversation memory) {
        return (JSON.writeValueAsString(render(memory)).length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    public static boolean fits(Conversation memory) {
        return estimateTokens(memory) <= memory.budget().maxInputTokens();
    }

    public static byte[] write(Conversation memory) { return JSON.writeValueAsBytes(memory); }

    public static Conversation read(byte[] bytes) { return JSON.readValue(bytes, Conversation.class); }

    public static boolean isV2(byte[] bytes) { return JSON.readTree(bytes).path("version").asInt() == 2; }

    /** Error lines with their javac/JUnit context lines, in order, without Gradle's repeated output. */
    public static List<String> errorLines(String log) {
        if (log == null) return List.of();
        var lines = log.lines().toList();
        var errors = new LinkedHashSet<String>();
        for (int i = 0; i < lines.size() && errors.size() < ERROR_LINES; i++) {
            String line = lines.get(i);
            if (!ERROR.matcher(line).find()) continue;
            var entry = new StringBuilder(line.strip());
            int next = i + 1;
            boolean testFailure = line.contains(" > ") && line.strip().endsWith(" FAILED");
            if (testFailure && next < lines.size() && lines.get(next).startsWith(" "))
                entry.append(" | ").append(normalized(lines.get(next++)));
            for (; next < lines.size() && next <= i + 6 && !ERROR.matcher(lines.get(next)).find(); next++)
                if (CONTEXT.matcher(lines.get(next)).find()) entry.append(" | ").append(normalized(lines.get(next)));
            errors.add(cap(entry.toString(), ERROR_ENTRY_CHARS));
            i = next - 1;
        }
        return List.copyOf(errors);
    }

    private static String normalized(String line) { return line.strip().replaceAll("\\s+", " "); }

    private static State update(State previous, int turn, ModelReply reply, List<ToolOutcome> outcomes) {
        var files = new TreeMap<String, FileState>();
        previous.files().forEach(file -> files.put(file.path(), file));
        CheckState check = previous.lastCheck();
        var rejected = new ArrayList<>(previous.rejected());
        for (int i = 0; i < outcomes.size() && i < reply.tools().size(); i++) {
            ToolOutcome outcome = outcomes.get(i);
            JsonNode result = parse(outcome.responseJson());
            if (result == null) continue;
            if ("ERROR".equals(result.path("status").asText(""))) {
                rejected.add(new Rejection(turn, outcome.name(), result.path("code").asText(""), text(result, "detail")));
                continue;
            }
            switch (outcome.name()) {
                case "read_file" -> {
                    JsonNode args = parse(reply.tools().get(i).argumentsJson());
                    String path = args == null ? null : text(args, "path");
                    FileState old = path == null ? null : files.get(path);
                    if (path != null) files.put(path, new FileState(path, result.path("sha256").asText(""),
                            old != null && old.written(), turn));
                }
                case "write_file" -> {
                    String path = text(result, "path");
                    if (path != null) files.put(path, new FileState(path, result.path("sha256").asText(""), true, turn));
                }
                case "build", "run_tests" -> check = new CheckState(outcome.name(), result.path("status").asText(""),
                        result.path("exitCode").isNumber() ? result.path("exitCode").asInt() : null,
                        strings(result.path("errors")), outcome.invocationId());
                case "submit" -> check = new CheckState("submit", result.path("status").asText(""), null,
                        strings(result.path("failedCases")), outcome.invocationId());
                default -> { }
            }
        }
        while (rejected.size() > REJECTED_KEPT) rejected.removeFirst();
        Note note = note(turn, reply.text());
        Progress progress = progress(previous.progress() == null
                ? Progress.empty(Thresholds.defaults(), previous.turn()) : previous.progress(), turn, reply, outcomes);
        return new State(turn, List.copyOf(files.values()), check, List.copyOf(rejected),
                note == null ? previous.note() : note, progress);
    }

    private static Progress progress(Progress p, int turn, ModelReply reply, List<ToolOutcome> outcomes) {
        var checks = new ArrayList<>(p.checks());
        boolean writeSinceCheck = p.writeSinceCheck();
        var history = new TreeMap<String, List<String>>();
        p.writeHistory().forEach((path, hashes) -> history.put(path, new ArrayList<>(hashes)));
        int nonProgress = p.nonProgressWrites();
        var writes = new ArrayList<>(p.recentWrites());
        var seen = new LinkedHashSet<>(p.seen());
        boolean newFact = false;
        for (int i = 0; i < outcomes.size() && i < reply.tools().size(); i++) {
            ToolOutcome outcome = outcomes.get(i);
            JsonNode result = parse(outcome.responseJson());
            if (result == null || "ERROR".equals(result.path("status").asText(""))) continue;
            switch (outcome.name()) {
                case "write_file" -> {
                    String path = text(result, "path");
                    String sha = result.path("sha256").asText("");
                    if (path == null) continue;
                    writeSinceCheck = true;
                    var hashes = history.computeIfAbsent(path, k -> new ArrayList<>());
                    boolean repeat = result.path("changed").isBoolean() && !result.path("changed").asBoolean()
                            || hashes.contains(sha);
                    nonProgress = repeat ? nonProgress + 1 : 0;
                    if (!repeat) { newFact = true; hashes.add(sha); }
                    while (hashes.size() > HISTORY_PER_PATH) hashes.removeFirst();
                    writes.add("turn " + turn + " write " + path + " sha256=" + cap(sha, 12) +
                            (repeat ? " unchanged" : " changed"));
                    while (writes.size() > WRITES_KEPT) writes.removeFirst();
                }
                case "build", "run_tests" -> {
                    String fingerprint = fingerprint(outcome.name(), result);
                    String last = checks.isEmpty() ? null : checks.getLast().substring(checks.getLast().indexOf('|') + 1);
                    if (!fingerprint.equals(last)) newFact = true;
                    checks.add((writeSinceCheck ? "w" : "-") + turn + "|" + fingerprint);
                    while (checks.size() > p.thresholds().repeatedFailure()) checks.removeFirst();
                    writeSinceCheck = false;
                }
                case "read_file", "list_files" -> {
                    JsonNode args = parse(reply.tools().get(i).argumentsJson());
                    String key = outcome.name() + ":" + (args == null ? "" : args.path("path").asText(""));
                    if (seen.add(key)) newFact = true;
                }
                default -> newFact = true;
            }
        }
        var seenList = new ArrayList<>(seen);
        while (seenList.size() > SEEN_KEPT) seenList.removeFirst();
        int lastNewFact = newFact ? turn : p.lastNewFactTurn();
        String signal = null;
        var evidence = new ArrayList<String>();
        if (reply.tools().isEmpty()) {
            signal = "NO_TOOL_CALL";
            evidence.add("turn " + turn + " reply had no tool call");
        } else if (checks.size() == p.thresholds().repeatedFailure() && failingRepeat(checks)) {
            signal = "REPEATED_FAILURE";
            for (String c : checks) evidence.add("check at turn " + c.substring(1, c.indexOf('|')));
            parse(outcomes.getLast().responseJson()).path("errors").forEach(e -> {
                if (evidence.size() < EVIDENCE_ERRORS + checks.size()) evidence.add(e.asText(""));
            });
        } else if (nonProgress >= p.thresholds().repeatedContent()) {
            signal = "REPEATED_CONTENT";
            evidence.addAll(writes);
        } else if (turn - lastNewFact >= p.thresholds().noNewFacts()) {
            signal = "NO_NEW_FACTS";
            evidence.add("no changed write, new check result, or new read since turn " + lastNewFact);
        }
        var nudged = new ArrayList<>(p.nudged());
        String fired = null;
        StallVerdict stall = null;
        if (signal != null) {
            if (nudged.contains(signal)) stall = new StallVerdict(signal, turn, List.copyOf(evidence));
            else { nudged.add(signal); fired = signal; }
        }
        return new Progress(p.thresholds(), List.copyOf(checks), writeSinceCheck, Map.copyOf(history), nonProgress,
                List.copyOf(writes), lastNewFact, List.copyOf(seenList), List.copyOf(nudged), p.hints(), fired, stall);
    }

    private static boolean failingRepeat(List<String> checks) {
        String first = checks.getFirst().substring(checks.getFirst().indexOf('|') + 1);
        if (!first.contains("|FAILED|")) return false;
        for (int i = 0; i < checks.size(); i++) {
            String c = checks.get(i);
            if (!c.substring(c.indexOf('|') + 1).equals(first)) return false;
            if (i > 0 && c.charAt(0) != 'w') return false;
        }
        return true;
    }

    private static String fingerprint(String tool, JsonNode result) {
        var errors = new ArrayList<String>();
        result.path("errors").forEach(e -> errors.add(POSITION.matcher(e.asText("").replace("/work/input/", ""))
                .replaceAll("")));
        java.util.Collections.sort(errors);
        return tool + "|" + result.path("status").asText("") + "|" + String.join("\n", errors);
    }

    private static String nudge(String signal, Progress progress, List<String> errors) {
        return switch (signal) {
            case "REPEATED_FAILURE" -> "The last " + progress.thresholds().repeatedFailure() +
                    " checks failed with the same errors:\n" + String.join("\n", errors) +
                    "\nThe writes between them did not change these errors. Change the specific lines named, or take a different approach.";
            case "REPEATED_CONTENT" -> "Your last " + progress.thresholds().repeatedContent() +
                    " writes did not change any file (changed=false, or a previous version restored). " +
                    "Rewriting the same content will not fix the reported errors. Edit the specific lines that cause them, or take a different approach.";
            case "NO_NEW_FACTS" -> "Your last " + progress.thresholds().noNewFacts() +
                    " turns produced no new information: no changed file, no new check result, no new file read. " +
                    "Take a different step, such as running build or editing the code that fails.";
            default -> null; // NO_TOOL_CALL uses the existing protocol reminder
        };
    }

    private static List<Map<String, Object>> messages(ModelReply reply, List<ToolOutcome> outcomes, State state) {
        var messages = new ArrayList<Map<String, Object>>();
        var calls = new ArrayList<Map<String, Object>>();
        for (var request : reply.tools()) {
            var call = new LinkedHashMap<String, Object>();
            call.put("id", request.providerCallId());
            call.put("name", request.name());
            call.put("argumentsJson", request.argumentsJson());
            calls.add(call);
        }
        var assistant = new LinkedHashMap<String, Object>();
        assistant.put("role", "assistant");
        assistant.put("content", reply.text() == null ? "" : reply.text());
        assistant.put("toolCalls", calls);
        messages.add(assistant);
        if (outcomes.size() != calls.size()) throw new IllegalArgumentException("Tool result count mismatch");
        for (int i = 0; i < outcomes.size(); i++) {
            var outcome = outcomes.get(i);
            var request = reply.tools().get(i);
            if (!request.providerCallId().equals(outcome.providerCallId()) || !request.name().equals(outcome.name()))
                throw new IllegalArgumentException("Tool result does not match request");
            var tool = new LinkedHashMap<String, Object>();
            tool.put("role", "tool");
            tool.put("id", outcome.providerCallId());
            tool.put("name", outcome.name());
            tool.put("content", outcome.responseJson());
            messages.add(tool);
        }
        if (outcomes.isEmpty()) messages.add(message("user", reminder(state, reply)));
        Progress progress = state.progress();
        if (progress != null && progress.fired() != null) {
            String text = nudge(progress.fired(), progress,
                    state.lastCheck() == null ? List.of() : state.lastCheck().errors().stream().limit(EVIDENCE_ERRORS).toList());
            if (text != null) messages.add(message("user", text));
        }
        return messages;
    }

    private static String reminder(State state, ModelReply reply) {
        var text = new StringBuilder("Use the provided tools and submit the immutable snapshot. Text alone cannot complete this task.");
        if ("length".equals(reply.finishReason()))
            text.append("\nYour reply was cut off at the output limit before a tool call completed, so nothing was written. " +
                    "Write one smaller file now; split a large class or test suite into several files over several replies.");
        if (state.note() != null && state.note().next() != null)
            text.append("\nYour last Next: ").append(state.note().next());
        if (state.lastCheck() != null && !state.lastCheck().errors().isEmpty()) {
            text.append("\nLast ").append(state.lastCheck().tool()).append(" errors:");
            state.lastCheck().errors().stream().limit(5).forEach(error -> text.append('\n').append(error));
        }
        return text.toString();
    }

    private static String stateText(Conversation memory) {
        State state = memory.state();
        var text = new StringBuilder("Working state after turn ").append(state.turn()).append(" (")
                .append(memory.compacted().turns())
                .append(" earlier turns compacted). This note is authoritative; reread files when you need their contents.");
        if (!state.files().isEmpty()) {
            text.append("\nFiles you read or wrote:");
            for (var file : state.files())
                text.append("\n- ").append(file.path()).append(" sha256=").append(file.sha256())
                        .append(file.written() ? " (written" : " (read").append(", turn ").append(file.turn()).append(')');
        }
        if (state.lastCheck() != null) {
            var check = state.lastCheck();
            text.append("\nLast check: ").append(check.tool()).append(' ').append(check.outcome());
            if (check.exitCode() != null) text.append(" exit ").append(check.exitCode());
            check.errors().forEach(error -> text.append("\n  ").append(error));
        }
        if (!state.rejected().isEmpty()) {
            text.append("\nRejected tool calls:");
            for (var rejection : state.rejected()) {
                text.append("\n- turn ").append(rejection.turn()).append(' ').append(rejection.tool())
                        .append(' ').append(rejection.code());
                if (rejection.detail() != null) text.append(": ").append(rejection.detail());
            }
        }
        if (state.progress() != null && !state.progress().hints().isEmpty()) {
            text.append("\nOperator hints:");
            state.progress().hints().forEach(hint -> text.append("\n- ").append(hint));
        }
        if (state.note() != null) {
            text.append("\nYour note from turn ").append(state.note().turn()).append(':');
            if (state.note().progress() != null) text.append("\nProgress: ").append(state.note().progress());
            if (state.note().next() != null) text.append("\nNext: ").append(state.note().next());
        }
        return text.toString();
    }

    private static Conversation keep(Conversation base, State state, List<Turn> turns, int keep) {
        var attempts = new ArrayList<>(base.compacted().modelAttempts());
        int folded = base.compacted().turns();
        var kept = new ArrayList<>(turns);
        while (kept.size() > keep) {
            String ref = base.runId() + ":turn-" + kept.removeFirst().turn();
            if (!attempts.contains(ref)) attempts.add(ref);
            folded++;
        }
        return new Conversation(2, base.runId(), base.budget(), base.system(), base.task(), state,
                List.copyOf(kept), new Compacted(folded, List.copyOf(attempts)));
    }

    private static Conversation mapRecent(Conversation memory, UnaryOperator<Map<String, Object>> change) {
        var turns = memory.recent().stream()
                .map(turn -> new Turn(turn.turn(), turn.messages().stream().map(change).toList())).toList();
        return new Conversation(2, memory.runId(), memory.budget(), memory.system(), memory.task(),
                memory.state(), turns, memory.compacted());
    }

    private static Map<String, Object> elideWrites(Map<String, Object> message) {
        if (!(message.get("toolCalls") instanceof List<?> calls)) return message;
        var changed = new ArrayList<Map<String, Object>>();
        for (Object item : calls) {
            @SuppressWarnings("unchecked")
            var call = new LinkedHashMap<>((Map<String, Object>) item);
            JsonNode args = "write_file".equals(call.get("name")) ? parse((String) call.get("argumentsJson")) : null;
            String content = args == null ? null : text(args, "content");
            if (content != null && !content.startsWith("[written: ")) {
                var shortened = new LinkedHashMap<String, Object>();
                shortened.put("path", args.path("path").asText(""));
                shortened.put("expectedSha256", args.path("expectedSha256").asText(""));
                shortened.put("content", "[written: " + args.path("path").asText("") + hash(content) +
                        ", " + content.length() + " chars]");
                call.put("argumentsJson", JSON.writeValueAsString(shortened));
            }
            changed.add(call);
        }
        var copy = new LinkedHashMap<>(message);
        copy.put("toolCalls", changed);
        return copy;
    }

    private static Map<String, Object> shortenReads(Map<String, Object> message) {
        if (!"tool".equals(message.get("role")) || !"read_file".equals(message.get("name"))) return message;
        JsonNode result = parse((String) message.get("content"));
        String content = result == null ? null : text(result, "content");
        if (content == null || content.length() <= 2 * READ_KEEP_CHARS) return message;
        var shortened = new LinkedHashMap<String, Object>();
        shortened.put("status", result.path("status").asText(""));
        shortened.put("sha256", result.path("sha256").asText(""));
        shortened.put("content", content.substring(0, READ_KEEP_CHARS) + "\n[... " +
                (content.length() - 2 * READ_KEEP_CHARS) + " chars elided; read_file again if needed ...]\n" +
                content.substring(content.length() - READ_KEEP_CHARS));
        var copy = new LinkedHashMap<>(message);
        copy.put("content", JSON.writeValueAsString(shortened));
        return copy;
    }

    private static String hash(String content) {
        try {
            return ", sha256 " + SnapshotHasher.content(content);
        } catch (IllegalArgumentException invalidText) {
            return ""; // the store already rejected this content; no hash identifies it
        }
    }

    private static Note note(int turn, String text) {
        if (text == null) return null;
        String progress = last(PROGRESS, text);
        String next = last(NEXT, text);
        return progress == null && next == null ? null : new Note(turn, progress, next);
    }

    private static String last(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        String found = null;
        while (matcher.find()) found = cap(matcher.group(1).strip(), NOTE_FIELD_CHARS);
        return found;
    }

    private static List<String> strings(JsonNode array) {
        var values = new ArrayList<String>();
        if (array.isArray()) for (JsonNode item : array) {
            if (values.size() == ERROR_LINES) break;
            values.add(cap(item.asText(""), ERROR_ENTRY_CHARS));
        }
        return List.copyOf(values);
    }

    private static JsonNode parse(String json) {
        if (json == null) return null;
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException malformed) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText() : null;
    }

    private static String cap(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static Map<String, Object> message(String role, String content) {
        var message = new LinkedHashMap<String, Object>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}
