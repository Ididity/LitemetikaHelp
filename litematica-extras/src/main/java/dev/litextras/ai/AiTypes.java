package dev.litextras.ai;

import com.google.gson.JsonObject;

import java.util.List;

/** Anbieter-neutrales Gesprächsmodell. Jeder Anbieter übersetzt es in sein eigenes Format. */
public final class AiTypes {
    private AiTypes() {}

    public sealed interface Message permits UserMsg, AssistantMsg, ToolResultsMsg {}

    public record UserMsg(String text) implements Message {}

    public record AssistantMsg(String text, List<ToolCall> calls) implements Message {}

    public record ToolResultsMsg(List<ToolResult> results) implements Message {}

    public record ToolCall(String id, String name, JsonObject args) {}

    public record ToolResult(String callId, String name, String content) {}

    public record ToolSpec(String name, String description, JsonObject parameters) {}

    public record Response(String text, List<ToolCall> calls, int inputTokens, int outputTokens) {}

    /** Verständliche Fehlermeldung (enthält NIE den API-Key). */
    public static final class AiException extends Exception {
        public AiException(String msg) { super(msg); }
    }

    public interface Provider {
        /** allowTools=false: das Modell soll jetzt ohne weitere Tool-Aufrufe antworten. */
        Response chat(String system, List<Message> messages, List<ToolSpec> tools, boolean allowTools) throws AiException;
    }
}
