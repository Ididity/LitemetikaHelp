package dev.litextras.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.litextras.config.Cfg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions mit Function Calling. Deckt auch Ollama (http://localhost:11434/v1/chat/completions)
 * und andere OpenAI-kompatible Server ab.
 */
final class OpenAiProvider implements AiTypes.Provider {
    private final String key;
    private final String flavor; // openai | ollama | openai_compatible

    OpenAiProvider(String key, String flavor) {
        this.key = key;
        this.flavor = flavor;
    }

    @Override
    public AiTypes.Response chat(String system, List<AiTypes.Message> messages, List<AiTypes.ToolSpec> tools, boolean allowTools) throws AiTypes.AiException {
        String model = Cfg.str("ai.model");
        if (model.isBlank()) {
            if ("ollama".equals(flavor)) model = "llama3.1";
            else throw new AiTypes.AiException("Bitte ein Modell in ai.model eintragen (für " + flavor + ").");
        }
        String url = Cfg.str("ai.endpoint");
        if (url.isBlank()) {
            url = switch (flavor) {
                case "ollama" -> "http://localhost:11434/v1/chat/completions";
                case "openai" -> "https://api.openai.com/v1/chat/completions";
                default -> throw new AiTypes.AiException("Für openai_compatible bitte ai.endpoint eintragen.");
            };
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        // UNSICHER: Neuere OpenAI-Modelle erwarten max_completion_tokens statt max_tokens.
        body.addProperty("openai".equals(flavor) ? "max_completion_tokens" : "max_tokens", Cfg.integer("ai.maxTokens"));
        if (Cfg.bool("ai.sendTemperature")) body.addProperty("temperature", Cfg.dbl("ai.temperature"));
        JsonArray msgs = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", system);
        msgs.add(sys);
        for (AiTypes.Message m : messages) convert(m, msgs);
        body.add("messages", msgs);
        if (!tools.isEmpty()) {
            JsonArray ts = new JsonArray();
            for (AiTypes.ToolSpec t : tools) {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", t.name());
                fn.addProperty("description", t.description());
                fn.add("parameters", t.parameters());
                JsonObject o = new JsonObject();
                o.addProperty("type", "function");
                o.add("function", fn);
                ts.add(o);
            }
            body.add("tools", ts);
            body.addProperty("tool_choice", allowTools ? "auto" : "none");
        }
        Map<String, String> h = new LinkedHashMap<>();
        if (key != null && !key.isBlank()) h.put("Authorization", "Bearer " + key);
        JsonObject res = Http.postJson(url, h, body, key);

        if (!res.has("choices") || res.getAsJsonArray("choices").isEmpty()) throw new AiTypes.AiException("Leere Antwort vom KI-Dienst.");
        JsonObject msg = res.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
        String text = msg.has("content") && !msg.get("content").isJsonNull() ? msg.get("content").getAsString() : "";
        List<AiTypes.ToolCall> calls = new ArrayList<>();
        if (msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()) {
            int n = 0;
            for (JsonElement el : msg.getAsJsonArray("tool_calls")) {
                JsonObject c = el.getAsJsonObject();
                JsonObject fn = c.getAsJsonObject("function");
                JsonObject args = new JsonObject();
                try {
                    JsonElement a = fn.get("arguments");
                    if (a != null && a.isJsonPrimitive()) args = JsonParser.parseString(a.getAsString()).getAsJsonObject();
                    else if (a != null && a.isJsonObject()) args = a.getAsJsonObject();
                } catch (Exception ignored) {}
                String id = c.has("id") ? c.get("id").getAsString() : "call_" + (n++);
                calls.add(new AiTypes.ToolCall(id, Http.str(fn, "name"), args));
            }
        }
        JsonObject usage = res.has("usage") && res.get("usage").isJsonObject() ? res.getAsJsonObject("usage") : null;
        return new AiTypes.Response(text.trim(), calls, Http.intOr(usage, "prompt_tokens"), Http.intOr(usage, "completion_tokens"));
    }

    private static void convert(AiTypes.Message m, JsonArray out) {
        if (m instanceof AiTypes.UserMsg u) {
            JsonObject o = new JsonObject();
            o.addProperty("role", "user");
            o.addProperty("content", u.text());
            out.add(o);
        } else if (m instanceof AiTypes.AssistantMsg a) {
            JsonObject o = new JsonObject();
            o.addProperty("role", "assistant");
            o.addProperty("content", a.text() == null ? "" : a.text());
            if (!a.calls().isEmpty()) {
                JsonArray calls = new JsonArray();
                for (AiTypes.ToolCall c : a.calls()) {
                    JsonObject fn = new JsonObject();
                    fn.addProperty("name", c.name());
                    fn.addProperty("arguments", c.args().toString());
                    JsonObject call = new JsonObject();
                    call.addProperty("id", c.id());
                    call.addProperty("type", "function");
                    call.add("function", fn);
                    calls.add(call);
                }
                o.add("tool_calls", calls);
            }
            out.add(o);
        } else if (m instanceof AiTypes.ToolResultsMsg r) {
            for (AiTypes.ToolResult res : r.results()) {
                JsonObject o = new JsonObject();
                o.addProperty("role", "tool");
                o.addProperty("tool_call_id", res.callId());
                o.addProperty("content", res.content());
                out.add(o);
            }
        }
    }
}
