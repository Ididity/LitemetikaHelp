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

/** Google Gemini (generateContent) mit Function Calling. UNSICHER: Schema-Details der Gemini-API. */
final class GeminiProvider implements AiTypes.Provider {
    private final String key;
    GeminiProvider(String key) { this.key = key; }

    @Override
    public AiTypes.Response chat(String system, List<AiTypes.Message> messages, List<AiTypes.ToolSpec> tools, boolean allowTools) throws AiTypes.AiException {
        String model = Cfg.str("ai.model");
        if (model.isBlank()) throw new AiTypes.AiException("Bitte ein Gemini-Modell in ai.model eintragen.");
        String url = Cfg.str("ai.endpoint").isBlank()
                ? "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
                : Cfg.str("ai.endpoint");
        JsonObject body = new JsonObject();
        JsonObject sys = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sp = new JsonObject();
        sp.addProperty("text", system);
        sysParts.add(sp);
        sys.add("parts", sysParts);
        body.add("systemInstruction", sys);
        JsonArray contents = new JsonArray();
        for (AiTypes.Message m : messages) contents.add(convert(m));
        body.add("contents", contents);
        if (!tools.isEmpty()) {
            JsonArray decls = new JsonArray();
            for (AiTypes.ToolSpec t : tools) {
                JsonObject d = new JsonObject();
                d.addProperty("name", t.name());
                d.addProperty("description", t.description());
                JsonObject props = t.parameters().has("properties") ? t.parameters().getAsJsonObject("properties") : null;
                if (props != null && !props.entrySet().isEmpty()) d.add("parameters", t.parameters());
                decls.add(d);
            }
            JsonObject tool = new JsonObject();
            tool.add("functionDeclarations", decls);
            JsonArray ts = new JsonArray();
            ts.add(tool);
            body.add("tools", ts);
            JsonObject fcc = new JsonObject();
            fcc.addProperty("mode", allowTools ? "AUTO" : "NONE");
            JsonObject tc = new JsonObject();
            tc.add("functionCallingConfig", fcc);
            body.add("toolConfig", tc);
        }
        JsonObject gen = new JsonObject();
        gen.addProperty("maxOutputTokens", Cfg.integer("ai.maxTokens"));
        if (Cfg.bool("ai.sendTemperature")) gen.addProperty("temperature", Cfg.dbl("ai.temperature"));
        body.add("generationConfig", gen);

        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-goog-api-key", key);
        JsonObject res = Http.postJson(url, h, body, key);

        StringBuilder text = new StringBuilder();
        List<AiTypes.ToolCall> calls = new ArrayList<>();
        if (res.has("candidates") && !res.getAsJsonArray("candidates").isEmpty()) {
            JsonObject cand = res.getAsJsonArray("candidates").get(0).getAsJsonObject();
            if (cand.has("content") && cand.getAsJsonObject("content").has("parts")) {
                int n = 0;
                for (JsonElement el : cand.getAsJsonObject("content").getAsJsonArray("parts")) {
                    JsonObject p = el.getAsJsonObject();
                    if (p.has("text")) text.append(p.get("text").getAsString());
                    if (p.has("functionCall")) {
                        JsonObject fc = p.getAsJsonObject("functionCall");
                        JsonObject args = fc.has("args") && fc.get("args").isJsonObject() ? fc.getAsJsonObject("args") : new JsonObject();
                        calls.add(new AiTypes.ToolCall("gemini_" + (n++), Http.str(fc, "name"), args));
                    }
                }
            }
        }
        JsonObject usage = res.has("usageMetadata") ? res.getAsJsonObject("usageMetadata") : null;
        return new AiTypes.Response(text.toString().trim(), calls, Http.intOr(usage, "promptTokenCount"), Http.intOr(usage, "candidatesTokenCount"));
    }

    private static JsonObject convert(AiTypes.Message m) {
        JsonObject o = new JsonObject();
        JsonArray parts = new JsonArray();
        if (m instanceof AiTypes.UserMsg u) {
            o.addProperty("role", "user");
            JsonObject p = new JsonObject();
            p.addProperty("text", u.text());
            parts.add(p);
        } else if (m instanceof AiTypes.AssistantMsg a) {
            o.addProperty("role", "model");
            if (a.text() != null && !a.text().isBlank()) {
                JsonObject p = new JsonObject();
                p.addProperty("text", a.text());
                parts.add(p);
            }
            for (AiTypes.ToolCall c : a.calls()) {
                JsonObject fc = new JsonObject();
                fc.addProperty("name", c.name());
                fc.add("args", c.args());
                JsonObject p = new JsonObject();
                p.add("functionCall", fc);
                parts.add(p);
            }
        } else if (m instanceof AiTypes.ToolResultsMsg r) {
            o.addProperty("role", "user");
            for (AiTypes.ToolResult res : r.results()) {
                JsonObject response = new JsonObject();
                try {
                    response.add("result", JsonParser.parseString(res.content()));
                } catch (Exception e) {
                    response.addProperty("result", res.content());
                }
                JsonObject fr = new JsonObject();
                fr.addProperty("name", res.name());
                fr.add("response", response);
                JsonObject p = new JsonObject();
                p.add("functionResponse", fr);
                parts.add(p);
            }
        }
        o.add("parts", parts);
        return o;
    }
}
