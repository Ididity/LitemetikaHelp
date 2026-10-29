package dev.litextras.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.litextras.util.ChatOut;
import dev.litextras.util.Lang;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Prüft, ob das Ollama-Modell vorhanden ist, und lädt es sonst automatisch herunter (ollama pull). */
final class OllamaHelper {
    private OllamaHelper() {}

    private static final Set<String> READY = ConcurrentHashMap.newKeySet();
    private static final Set<String> PULLING = ConcurrentHashMap.newKeySet();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static String base(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return "http://localhost:11434";
        int i = endpoint.indexOf("/v1/");
        return i > 0 ? endpoint.substring(0, i) : endpoint.replaceAll("/+$", "");
    }

    /** true = Modell bereit. false = wird geladen bzw. Ollama nicht erreichbar (Meldung wurde ausgegeben). */
    static boolean ensureModel(String model, String endpoint) {
        if (model == null || model.isBlank() || READY.contains(model)) return true;
        if (PULLING.contains(model)) {
            ChatOut.warn(Lang.get("ollama.stillPulling", "model", model));
            return false;
        }
        String base = base(endpoint);
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/tags")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            String want = model.contains(":") ? model : model + ":latest";
            for (JsonElement m : o.getAsJsonArray("models")) {
                String name = m.getAsJsonObject().get("name").getAsString();
                if (name.equals(model) || name.equals(want)) {
                    READY.add(model);
                    return true;
                }
            }
        } catch (Exception e) {
            ChatOut.error(Lang.get("ollama.notRunning"));
            return false;
        }
        // Nicht vorhanden -> automatisch laden
        PULLING.add(model);
        ChatOut.info(Lang.get("ollama.pulling", "model", model));
        Thread t = new Thread(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("model", model);
                body.addProperty("stream", true);
                HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/pull")).timeout(Duration.ofHours(2))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                HttpResponse<java.io.InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int lastPct = -1;
                boolean ok = false;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        JsonObject st = JsonParser.parseString(line).getAsJsonObject();
                        if (st.has("error")) throw new RuntimeException(st.get("error").getAsString());
                        if (st.has("total") && st.has("completed")) {
                            long total = st.get("total").getAsLong();
                            int pct = total > 0 ? (int) (st.get("completed").getAsLong() * 100 / total) : 0;
                            if (pct / 20 != lastPct / 20 && total > 50_000_000L) {
                                lastPct = pct;
                                ChatOut.send(Lang.get("voice.progress", "what", model, "pct", pct, "mb", total / 1_000_000), 0xAAAAAA);
                            }
                        }
                        if (st.has("status") && "success".equals(st.get("status").getAsString())) ok = true;
                    }
                }
                if (ok) {
                    READY.add(model);
                    ChatOut.ok(Lang.get("ollama.pulled", "model", model));
                    dev.litextras.voice.VoiceManager.speak(Lang.get("ollama.pulledSpeak"));
                } else {
                    ChatOut.error(Lang.get("ollama.pullFailed", "model", model, "error", "unbekannt"));
                }
            } catch (Exception e) {
                ChatOut.error(Lang.get("ollama.pullFailed", "model", model, "error", String.valueOf(e.getMessage())));
            } finally {
                PULLING.remove(model);
            }
        }, "LitematicaExtras-OllamaPull");
        t.setDaemon(true);
        t.start();
        return false;
    }
}
