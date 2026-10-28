package dev.litextras.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.litextras.config.Cfg;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.Map;

/** Gemeinsamer HTTP-Teil. Läuft nur im KI-Hintergrundthread. Key wird aus allen Meldungen entfernt. */
final class Http {
    private Http() {}

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static JsonObject postJson(String url, Map<String, String> headers, JsonObject body, String secretToHide) throws AiTypes.AiException {
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder(URI.create(url));
        } catch (IllegalArgumentException e) {
            throw new AiTypes.AiException("Ungültige Endpoint-URL in der Config (ai.endpoint).");
        }
        b.timeout(Duration.ofSeconds(Cfg.integer("ai.timeoutSeconds")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        for (Map.Entry<String, String> h : headers.entrySet()) b.header(h.getKey(), h.getValue());
        HttpResponse<String> res;
        try {
            res = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new AiTypes.AiException("Zeitüberschreitung – der KI-Dienst hat nicht rechtzeitig geantwortet (ai.timeoutSeconds).");
        } catch (ConnectException | UnresolvedAddressException e) {
            throw new AiTypes.AiException("Keine Verbindung zum KI-Dienst (kein Internet, falsche URL oder Ollama läuft nicht).");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiTypes.AiException("Anfrage abgebrochen.");
        } catch (Exception e) {
            throw new AiTypes.AiException("Netzwerkfehler: " + hide(String.valueOf(e.getMessage()), secretToHide));
        }
        int code = res.statusCode();
        String text = res.body() == null ? "" : res.body();
        if (code == 401 || code == 403) throw new AiTypes.AiException("API-Key ungültig oder ohne Berechtigung (HTTP " + code + "). Key in secrets.json5 prüfen.");
        if (code == 429) throw new AiTypes.AiException("Rate-Limit des Anbieters erreicht (HTTP 429). Bitte kurz warten.");
        if (code == 404) throw new AiTypes.AiException("Endpoint oder Modell nicht gefunden (HTTP 404). ai.model / ai.endpoint prüfen.");
        if (code >= 400) throw new AiTypes.AiException("Fehler vom KI-Dienst (HTTP " + code + "): " + shortError(text, secretToHide));
        try {
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) throw new AiTypes.AiException("Unerwartete Antwort vom KI-Dienst.");
            return el.getAsJsonObject();
        } catch (AiTypes.AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiTypes.AiException("Antwort des KI-Dienstes nicht lesbar.");
        }
    }

    private static String shortError(String body, String secret) {
        String msg = body;
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("error")) {
                JsonElement e = o.get("error");
                msg = e.isJsonObject() && e.getAsJsonObject().has("message") ? e.getAsJsonObject().get("message").getAsString() : e.toString();
            }
        } catch (Exception ignored) {}
        msg = hide(msg, secret);
        return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
    }

    static String hide(String s, String secret) {
        if (s == null) return "";
        if (secret != null && secret.length() >= 6) s = s.replace(secret, "***");
        return s;
    }

    static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    static int intOr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : 0;
    }
}
