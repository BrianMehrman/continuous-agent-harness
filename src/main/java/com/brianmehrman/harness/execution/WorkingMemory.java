package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.workspace.SnapshotHasher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    static final int REJECTED_KEPT = 5;
    static final int NOTE_FIELD_CHARS = 700;
    static final int READ_KEEP_CHARS = 1000;
    private static final int CHARS_PER_TOKEN = 3;
    private static final Pattern ERROR = Pattern.compile("\\.java:\\d+: error:|\\bFAILED\\b|AssertionFailedError");
    private static final Pattern PROGRESS = Pattern.compile("(?im)^\\s*Progress:\\s*(.+)$");
    private static final Pattern NEXT = Pattern.compile("(?im)^\\s*Next:\\s*(.+)$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Conversation(int version, String runId, Budget budget, String system, String task,
            State state, List<Turn> recent, Compacted compacted) {}
    public record Budget(int contextTokens, int outputTokens, int maxInputTokens) {}
    public record State(int turn, List<FileState> files, CheckState lastCheck, List<Rejection> rejected, Note note) {}
    public record FileState(String path, String sha256, boolean written, int turn) {}
    public record CheckState(String tool, String outcome, Integer exitCode, List<String> errors, String receiptRef) {}
    public record Rejection(int turn, String tool, String code, String detail) {}
    public record Note(int turn, String progress, String next) {}
    public record Turn(int turn, List<Map<String, Object>> messages) {}
    public record Compacted(int turns, List<String> modelAttempts) {}

    private WorkingMemory() {}

    public static Conversation start(String runId, String system, String task, int contextTokens, int outputTokens) {
        int max = (int) ((contextTokens - outputTokens) * 0.9);
        return new Conversation(2, runId, new Budget(contextTokens, outputTokens, max), system, task,
                new State(0, List.of(), null, List.of(), null), List.of(), new Compacted(0, List.of()));
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

    public static List<String> errorLines(String log) {
        if (log == null) return List.of();
        return log.lines().filter(line -> ERROR.matcher(line).find())
                .map(line -> cap(line.strip(), 300)).limit(ERROR_LINES).toList();
    }

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
        return new State(turn, List.copyOf(files.values()), check, List.copyOf(rejected),
                note == null ? previous.note() : note);
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
        if (outcomes.isEmpty()) messages.add(message("user", reminder(state)));
        return messages;
    }

    private static String reminder(State state) {
        var text = new StringBuilder("Use the provided tools and submit the immutable snapshot. Text alone cannot complete this task.");
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
            attempts.add(base.runId() + ":turn-" + kept.removeFirst().turn());
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
            values.add(cap(item.asText(""), 300));
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
