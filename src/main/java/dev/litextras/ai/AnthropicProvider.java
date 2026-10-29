package dev.litextras.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.litextras.config.Cfg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Claude (Anthropic Messages API) mit Tool-Use. */
final class AnthropicProvider implements AiTypes.Provider {
    private final String key;
    AnthropicProvider(String key) { this.key = key; }

    @Override
    public AiTypes.Response chat(String system, List<AiTypes.Message> messages, List<AiTypes.ToolSpec> tools, boolean allowTools) throws AiTypes.AiException {
        String model = Cfg.str("ai.model").isBlank() ? "claude-haiku-5-5" : Cfg.str("ai.model");
        String url = Cfg.str("ai.endpoint").isBlank() ? "https://api.anthropic.com/v1/messages" : Cfg.str("ai.endpoint");
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", Cfg.integer("ai.maxTokens"));
        if (Cfg.bool("ai.sendTemperature")) body.addProperty("temperature", Cfg.dbl("ai.temperature"));
        body.addProperty("system", system);
        JsonArray msgs = new JsonArray();
        for (AiTypes.Message m : messages) msgs.add(convert(m));
        body.add("messages", msgs);
        if (!tools.isEmpty()) {
            JsonArray ts = new JsonArray();
            for (AiTypes.ToolSpec t : tools) {
                JsonObject o = new JsonObject();
                o.addProperty("name", t.name());
                o.addProperty("description", t.description());
                o.add("input_schema", t.parameters());
                ts.add(o);
            }
            body.add("tools", ts);
            JsonObject choice = new JsonObject();
            choice.addProperty("type", allowTools ? "auto" : "none");
            body.add("tool_choice", choice);
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-api-key", key);
        h.put("anthropic-version", "2023-06-01");
        JsonObject res = Http.postJson(url, h, body, key);

        StringBuilder text = new StringBuilder();
        List<AiTypes.ToolCall> calls = new ArrayList<>();
        if (res.has("content") && res.get("content").isJsonArray()) {
            for (JsonElement el : res.getAsJsonArray("content")) {
                JsonObject c = el.getAsJsonObject();
                String type = Http.str(c, "type");
                if ("text".equals(type)) text.append(Http.str(c, "text"));
                else if ("tool_use".equals(type)) {
                    JsonObject input = c.has("input") && c.get("input").isJsonObject() ? c.getAsJsonObject("input") : new JsonObject();
                    calls.add(new AiTypes.ToolCall(Http.str(c, "id"), Http.str(c, "name"), input));
                }
            }
        }
        JsonObject usage = res.has("usage") ? res.getAsJsonObject("usage") : null;
        return new AiTypes.Response(text.toString().trim(), calls, Http.intOr(usage, "input_tokens"), Http.intOr(usage, "output_tokens"));
    }

    private static JsonObject convert(AiTypes.Message m) {
        JsonObject o = new JsonObject();
        if (m instanceof AiTypes.UserMsg u) {
            o.addProperty("role", "user");
            o.addProperty("content", u.text());
        } else if (m instanceof AiTypes.AssistantMsg a) {
            o.addProperty("role", "assistant");
            JsonArray content = new JsonArray();
            if (a.text() != null && !a.text().isBlank()) {
                JsonObject t = new JsonObject();
                t.addProperty("type", "text");
                t.addProperty("text", a.text());
                content.add(t);
            }
            for (AiTypes.ToolCall c : a.calls()) {
                JsonObject t = new JsonObject();
                t.addProperty("type", "tool_use");
                t.addProperty("id", c.id());
                t.addProperty("name", c.name());
                t.add("input", c.args());
                content.add(t);
            }
            o.add("content", content);
        } else if (m instanceof AiTypes.ToolResultsMsg r) {
            o.addProperty("role", "user");
            JsonArray content = new JsonArray();
            for (AiTypes.ToolResult res : r.results()) {
                JsonObject t = new JsonObject();
                t.addProperty("type", "tool_result");
                t.addProperty("tool_use_id", res.callId());
                t.addProperty("content", res.content());
                content.add(t);
            }
            o.add("content", content);
        }
        return o;
    }
}
