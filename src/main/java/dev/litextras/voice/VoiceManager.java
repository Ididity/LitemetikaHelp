package dev.litextras.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.litextras.LitematicaExtras;
import dev.litextras.ai.AiService;
import dev.litextras.config.Cfg;
import dev.litextras.util.ChatOut;
import dev.litextras.util.Lang;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Sprachausgabe (Piper, deutsche Frauenstimme) und Spracheingabe (Whisper, Taste gedrückt halten).
 * Beides läuft komplett lokal auf dem PC – es wird nichts an einen Dienst geschickt.
 * Die Programme werden mit "/lx stimme installieren" einmalig in config/litematica_extras/voice/ geladen.
 * Aktuell nur für Windows (die Programme gibt es dort als fertige .exe).
 */
public final class VoiceManager {
    private VoiceManager() {}

    private static final String PIPER_ZIP = "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip";
    private static final String VOICE_BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/main/";
    private static final String WHISPER_RELEASES = "https://api.github.com/repos/ggml-org/whisper.cpp/releases?per_page=20";
    private static final String WHISPER_MODEL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-%s.bin";

    private static final ExecutorService SPEAK = Executors.newSingleThreadExecutor(r -> daemon(r, "LitematicaExtras-Stimme"));
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> daemon(r, "LitematicaExtras-Stimme-Install"));
    private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);
    private static final AtomicBoolean RECORDING = new AtomicBoolean(false);
    private static volatile Clip currentClip;
    private static volatile Process currentPiper;
    private static volatile TargetDataLine mic;
    private static volatile ByteArrayOutputStream recorded;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20)).build();

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ================================================================== Pfade
    public static Path dir() { return Cfg.manager().dir().resolve("voice"); }
    private static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    /** Sucht die Dateien in der angegebenen Reihenfolge (erster Name hat Vorrang). */
    private static Path findFile(Path root, String... names) {
        if (!Files.isDirectory(root)) return null;
        for (String want : names) {
            try (Stream<Path> s = Files.walk(root, 6)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.getFileName().toString().toLowerCase(Locale.ROOT).equals(want)) return p;
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    private static Path piperExe() { return findFile(dir().resolve("piper"), "piper.exe"); }
    private static Path whisperExe() { return findFile(dir().resolve("whisper"), "whisper-cli.exe", "main.exe"); }
    /** Aktuelle Stimme: Frauenstimme (voice.voiceName) oder Männerstimme (voice.maleVoice) je nach Persona. */
    public static String currentVoice() {
        return "junge".equals(Cfg.str("ai.persona")) ? Cfg.str("voice.maleVoice") : Cfg.str("voice.voiceName");
    }

    private static Path voiceModel() { return dir().resolve("voices").resolve(currentVoice() + ".onnx"); }

    /** Nach Persona-Wechsel: fehlende Stimme im Hintergrund laden und kurz Hallo sagen. */
    public static void personaChanged() {
        if (!windows() || piperExe() == null) return;
        WORK.execute(() -> {
            try {
                ensureVoice(currentVoice());
                speak(Lang.get("voice.hello"));
            } catch (Exception e) {
                ChatOut.error(Lang.get("voice.voiceUnknown", "name", currentVoice()));
            }
        });
    }
    private static Path whisperModel() { return dir().resolve("models").resolve("ggml-" + Cfg.str("voice.sttModel") + ".bin"); }

    public static boolean ttsReady() { return piperExe() != null && Files.exists(voiceModel()) && Files.exists(Path.of(voiceModel() + ".json")); }
    public static boolean sttReady() { return whisperExe() != null && Files.exists(whisperModel()); }

    // ================================================================== Installation
    public static void install(boolean announceIfReady) {
        if (!windows()) {
            ChatOut.warn(Lang.get("voice.onlyWindows"));
            return;
        }
        if (ttsReady() && sttReady()) {
            if (announceIfReady) ChatOut.ok(Lang.get("voice.alreadyInstalled"));
            return;
        }
        if (!INSTALLING.compareAndSet(false, true)) {
            ChatOut.warn(Lang.get("voice.installRunning"));
            return;
        }
        ChatOut.info(Lang.get("voice.installStart"));
        WORK.execute(() -> {
            try {
                Files.createDirectories(dir());
                if (piperExe() == null) {
                    Path zip = download(PIPER_ZIP, dir().resolve("piper.zip"), "Piper");
                    unzip(zip, dir().resolve("piper"));
                    Files.deleteIfExists(zip);
                }
                ensureVoice(currentVoice());
                if (whisperExe() == null) {
                    String url = findWhisperZip();
                    if (url == null) {
                        ChatOut.error(Lang.get("voice.whisperNotFound", "dir", dir().resolve("whisper").toString()));
                    } else {
                        Path zip = download(url, dir().resolve("whisper.zip"), "Whisper");
                        unzip(zip, dir().resolve("whisper"));
                        Files.deleteIfExists(zip);
                    }
                }
                if (!Files.exists(whisperModel())) {
                    Files.createDirectories(whisperModel().getParent());
                    download(String.format(WHISPER_MODEL, Cfg.str("voice.sttModel")), whisperModel(), "Spracherkennung");
                }
                ChatOut.ok(Lang.get("voice.installDone", "key", dev.litextras.util.Hotkeys.describe("pushToTalk")));
                speak(Lang.get("voice.hello"));
            } catch (Exception e) {
                LitematicaExtras.LOG.error("Stimme installieren fehlgeschlagen", e);
                ChatOut.error(Lang.get("voice.installFailed", "error", String.valueOf(e.getMessage())));
            } finally {
                INSTALLING.set(false);
            }
        });
    }

    /** Stimme wechseln (wird bei Bedarf heruntergeladen). */
    public static void setVoice(String name) {
        WORK.execute(() -> {
            try {
                ensureVoice(name);
                Cfg.manager().setRuntime("voice.voiceName", new com.google.gson.JsonPrimitive(name));
                ChatOut.ok(Lang.get("voice.voiceSet", "name", name));
                speak(Lang.get("voice.hello"));
            } catch (Exception e) {
                ChatOut.error(Lang.get("voice.voiceUnknown", "name", name));
            }
        });
    }

    private static void ensureVoice(String name) throws Exception {
        Path onnx = dir().resolve("voices").resolve(name + ".onnx");
        if (Files.exists(onnx) && Files.exists(Path.of(onnx + ".json"))) return;
        // Name z. B. de_DE-kerstin-low  ->  de/de_DE/kerstin/low/de_DE-kerstin-low.onnx
        String[] p = name.split("-");
        if (p.length < 3) throw new IllegalArgumentException("Stimmenname muss wie de_DE-kerstin-low aussehen");
        String lang = p[0];
        String family = lang.substring(0, lang.indexOf('_'));
        String speaker = String.join("-", java.util.Arrays.copyOfRange(p, 1, p.length - 1));
        String quality = p[p.length - 1];
        String base = VOICE_BASE + family + "/" + lang + "/" + speaker + "/" + quality + "/" + name;
        Files.createDirectories(onnx.getParent());
        download(base + ".onnx.json", Path.of(onnx + ".json"), null);
        download(base + ".onnx", onnx, "Stimme " + name);
    }

    private static String findWhisperZip() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(WHISPER_RELEASES))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "litematica-extras")
                    .timeout(Duration.ofSeconds(30)).GET().build();
            String body = HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
            JsonArray releases = JsonParser.parseString(body).getAsJsonArray();
            for (String wanted : new String[]{"whisper-bin-x64.zip", "whisper-blas-bin-x64.zip"}) {
                for (JsonElement r : releases) {
                    for (JsonElement a : r.getAsJsonObject().getAsJsonArray("assets")) {
                        if (wanted.equals(a.getAsJsonObject().get("name").getAsString())) {
                            return a.getAsJsonObject().get("browser_download_url").getAsString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            LitematicaExtras.LOG.warn("Whisper-Release nicht gefunden: {}", e.getMessage());
        }
        return null;
    }

    private static Path download(String url, Path target, String label) throws Exception {
        Files.createDirectories(target.getParent());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "litematica-extras")
                .timeout(Duration.ofMinutes(20)).GET().build();
        HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " bei " + url);
        long total = res.headers().firstValueAsLong("content-length").orElse(-1);
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int lastPct = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (label != null && total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct / 25 != lastPct / 25) {
                        lastPct = pct;
                        ChatOut.send(Lang.get("voice.progress", "what", label, "pct", pct, "mb", total / 1_000_000), 0xAAAAAA);
                    }
                }
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static void unzip(Path zip, Path dest) throws IOException {
        Files.createDirectories(dest);
        Path root = dest.toAbsolutePath().normalize();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) continue; // Schutz vor bösartigen Pfaden
                if (e.isDirectory()) Files.createDirectories(out);
                else {
                    Files.createDirectories(out.getParent());
                    Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // ================================================================== Sprechen (Piper)
    /** Jede neue Ausgabe erhöht die Nummer – alte, noch laufende Sätze werden dadurch verworfen. */
    private static final java.util.concurrent.atomic.AtomicLong SPEECH_GEN = new java.util.concurrent.atomic.AtomicLong();
    private static final ExecutorService PLAY = Executors.newSingleThreadExecutor(r -> daemon(r, "LitematicaExtras-Abspielen"));

    /**
     * Liest Text vor. Schnell: Der Text wird in Sätze geteilt; der erste Satz wird sofort erzeugt und abgespielt,
     * während die nächsten schon im Hintergrund entstehen.
     */
    public static void speak(String text) {
        if (!Cfg.bool("voice.ttsEnabled") || text == null || text.isBlank()) return;
        if (!windows() || !ttsReady()) return;
        String clean = text.replaceAll("[*_#`>«»\\[\\]()]", " ").replaceAll("\\s+", " ").trim();
        int max = Cfg.integer("voice.maxSpeakChars");
        if (clean.length() > max) clean = clean.substring(0, max);
        stopSpeaking();
        long gen = SPEECH_GEN.incrementAndGet();
        for (String chunk : sentences(clean)) {
            SPEAK.execute(() -> {
                if (SPEECH_GEN.get() != gen) return;
                Path wav = synth(chunk);
                if (wav == null) return;
                PLAY.execute(() -> {
                    try {
                        if (SPEECH_GEN.get() == gen) play(wav);
                    } catch (Throwable t) {
                        LitematicaExtras.debug("Abspielen fehlgeschlagen: " + t);
                    } finally {
                        try { Files.deleteIfExists(wav); } catch (IOException ignored) {}
                    }
                });
            });
        }
    }

    /** Teilt in Sätze; sehr kurze Stücke werden zusammengefasst, der erste bleibt kurz für schnellen Start. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String part : text.split("(?<=[.!?…:;])\\s+")) {
            if (cur.length() > 0 && (out.isEmpty() ? cur.length() >= 20 : cur.length() + part.length() > 220)) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(part);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static Path synth(String say) {
        Path wav = null;
        try {
            wav = Files.createTempFile("lx-tts", ".wav");
            List<String> cmd = new ArrayList<>(List.of(piperExe().toString(), "--model", voiceModel().toString(),
                    "--output_file", wav.toString(), "--length_scale", String.valueOf(1.0 / Math.max(0.3, Cfg.dbl("voice.speed"))),
                    "--sentence_silence", "0.1"));
            Process p = new ProcessBuilder(cmd).directory(piperExe().getParent().toFile()).redirectErrorStream(true).start();
            currentPiper = p;
            try (OutputStream os = p.getOutputStream()) {
                os.write(say.getBytes(StandardCharsets.UTF_8));
            }
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            return wav;
        } catch (Throwable t) {
            LitematicaExtras.debug("Sprachausgabe fehlgeschlagen: " + t);
            if (wav != null) try { Files.deleteIfExists(wav); } catch (IOException ignored) {}
            return null;
        } finally {
            currentPiper = null;
        }
    }

    private static void play(Path wav) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            Clip clip = AudioSystem.getClip();
            clip.open(in);
            try {
                FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                double vol = Math.max(0.01, Math.min(1.0, Cfg.dbl("voice.volume")));
                gain.setValue((float) Math.max(gain.getMinimum(), 20.0 * Math.log10(vol)));
            } catch (Exception ignored) {}
            currentClip = clip;
            clip.start();
            long end = System.currentTimeMillis() + Math.max(100, clip.getMicrosecondLength() / 1000);
            while (System.currentTimeMillis() < end && clip.isOpen()) Thread.sleep(20);
            clip.close();
        }
    }

    public static void stopSpeaking() {
        SPEECH_GEN.incrementAndGet();
        Clip c = currentClip;
        if (c != null) try { c.stop(); c.close(); } catch (Exception ignored) {}
        Process p = currentPiper;
        if (p != null) p.destroyForcibly();
    }

    // ================================================================== Zuhören (Whisper)
    /** Hotkey gedrückt: Aufnahme starten. Losgelassen: auswerten und als KI-Frage stellen. */
    public static void pushToTalk(boolean down) {
        if (!Cfg.bool("voice.sttEnabled")) return;
        if (down) startRecording();
        else stopRecordingAndAsk();
    }

    private static volatile float recordRate = 16000f;

    /** Alle Mikrofone (Aufnahmegeräte). */
    public static List<javax.sound.sampled.Mixer.Info> microphones() {
        List<javax.sound.sampled.Mixer.Info> out = new ArrayList<>();
        DataLine.Info want = new DataLine.Info(TargetDataLine.class, new AudioFormat(44100f, 16, 1, true, false));
        for (javax.sound.sampled.Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                if (AudioSystem.getMixer(mi).isLineSupported(want)) out.add(mi);
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Öffnet das eingestellte (oder Standard-)Mikrofon; erst 16 kHz, sonst 48/44,1 kHz (wird später umgerechnet). */
    private static TargetDataLine openMic() throws Exception {
        String name = Cfg.str("voice.micName");
        javax.sound.sampled.Mixer mixer = null;
        if (!name.isBlank()) {
            for (javax.sound.sampled.Mixer.Info mi : microphones()) {
                if (mi.getName().equals(name)) { mixer = AudioSystem.getMixer(mi); break; }
            }
        }
        Exception last = null;
        for (float rate : new float[]{16000f, 48000f, 44100f}) {
            AudioFormat fmt = new AudioFormat(rate, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            try {
                TargetDataLine line = mixer != null ? (TargetDataLine) mixer.getLine(info) : (TargetDataLine) AudioSystem.getLine(info);
                line.open(fmt, (int) (rate / 5) * 2);
                recordRate = rate;
                return line;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("kein Mikrofon");
    }

    private static void startRecording() {
        if (!windows()) { ChatOut.warn(Lang.get("voice.onlyWindows")); return; }
        if (!sttReady()) {
            ChatOut.warn(Lang.get("voice.notInstalled"));
            install(false);
            return;
        }
        if (!RECORDING.compareAndSet(false, true)) return;
        stopSpeaking();
        try {
            TargetDataLine line = openMic();
            line.start();
            mic = line;
            recorded = new ByteArrayOutputStream();
            ChatOut.send(Lang.get("voice.listening"), 0x55FFFF);
            Thread t = new Thread(() -> {
                byte[] buf = new byte[4096];
                long maxBytes = (long) (recordRate * 2) * Cfg.integer("voice.maxRecordSeconds");
                while (RECORDING.get() && recorded.size() < maxBytes) {
                    int n = line.read(buf, 0, buf.length);
                    if (n > 0) recorded.write(buf, 0, n);
                }
            }, "LitematicaExtras-Mikrofon");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            RECORDING.set(false);
            ChatOut.error(Lang.get("voice.micError", "error", String.valueOf(e.getMessage())));
        }
    }

    /** 16-bit-PCM -> 16 kHz, Pegel anheben (Normalisieren), damit Whisper gut versteht. */
    static short[] prepare(byte[] pcm, float rate) {
        int n = pcm.length / 2;
        short[] in = new short[n];
        for (int i = 0; i < n; i++) in[i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
        short[] out;
        if (Math.abs(rate - 16000f) < 1) out = in;
        else {
            double ratio = rate / 16000.0;
            int m = (int) (n / ratio);
            out = new short[m];
            for (int i = 0; i < m; i++) {
                // Mittelwert über das Fenster = einfacher Tiefpass gegen Verzerrung
                int from = (int) (i * ratio), to = Math.min(n, (int) ((i + 1) * ratio));
                long sum = 0;
                for (int k = from; k < Math.max(from + 1, to); k++) sum += in[Math.min(k, n - 1)];
                out[i] = (short) (sum / Math.max(1, to - from));
            }
        }
        // Gleichanteil entfernen und normalisieren
        long mean = 0;
        for (short v : out) mean += v;
        mean = out.length == 0 ? 0 : mean / out.length;
        int peak = 1;
        for (int i = 0; i < out.length; i++) {
            int v = out[i] - (int) mean;
            out[i] = (short) Math.max(-32768, Math.min(32767, v));
            peak = Math.max(peak, Math.abs(v));
        }
        lastPeak = peak / 32768.0;
        if (peak > 300 && peak < 26000) {
            double gain = Math.min(20.0, 26000.0 / peak);
            for (int i = 0; i < out.length; i++) out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(out[i] * gain)));
        }
        return out;
    }

    static volatile double lastPeak = 0;

    private static void stopRecordingAndAsk() {
        if (!RECORDING.compareAndSet(true, false)) return;
        TargetDataLine line = mic;
        ByteArrayOutputStream data = recorded;
        mic = null;
        if (line != null) { line.stop(); line.close(); }
        float rate = recordRate;
        if (data == null || data.size() < rate * 2 * 0.3) {
            ChatOut.send(Lang.get("voice.tooShort"), 0xAAAAAA);
            return;
        }
        ChatOut.send(Lang.get("voice.processing"), 0xAAAAAA);
        WORK.execute(() -> {
            try {
                short[] samples = prepare(data.toByteArray(), rate);
                if (lastPeak < 0.01) {
                    ChatOut.warn(Lang.get("voice.tooQuiet"));
                    return;
                }
                byte[] pcm = new byte[samples.length * 2];
                for (int i = 0; i < samples.length; i++) { pcm[2 * i] = (byte) samples[i]; pcm[2 * i + 1] = (byte) (samples[i] >> 8); }
                Path wav = dir().resolve("letzte-aufnahme.wav");
                AudioFormat fmt = new AudioFormat(16000f, 16, 1, true, false);
                try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), fmt, samples.length)) {
                    AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, wav.toFile());
                }
                int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
                List<String> cmd = new ArrayList<>(List.of(whisperExe().toString(), "-m", whisperModel().toString(),
                        "-l", Cfg.str("voice.sttLanguage"), "-t", String.valueOf(threads), "-nt", "-np", "-f", wav.toString(),
                        "--prompt", "Minecraft, Litematica, Schematic, Ebene, Block, Bauen, Creeper, Spieler."));
                String text = runWhisper(cmd);
                if (text == null) {
                    // ältere whisper-Versionen kennen -np/--prompt nicht -> ohne erneut
                    cmd.removeIf(x -> x.equals("-np"));
                    int pi = cmd.indexOf("--prompt");
                    if (pi >= 0) { cmd.remove(pi + 1); cmd.remove(pi); }
                    text = runWhisper(cmd);
                }
                if (text == null || text.isEmpty()) {
                    ChatOut.send(Lang.get("voice.notUnderstood"), 0xAAAAAA);
                    return;
                }
                final String said = text;
                ChatOut.send(Lang.get("voice.youSaid", "text", said), 0xFFFFAA);
                net.minecraft.client.MinecraftClient.getInstance().execute(() -> AiService.ask(said));
            } catch (Exception e) {
                ChatOut.error(Lang.get("voice.sttError", "error", String.valueOf(e.getMessage())));
            }
        });
    }

    /** Startet whisper und liefert den erkannten Text (ohne [BLANK_AUDIO] usw.), null bei Programmfehler. */
    private static String runWhisper(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(whisperExe().getParent().toFile()).start();
        p.getOutputStream().close();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> { try { p.getErrorStream().transferTo(err); } catch (IOException ignored) {} });
        drain.setDaemon(true);
        drain.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return ""; }
        drain.join(1000);
        if (p.exitValue() != 0 && out.isBlank()) {
            LitematicaExtras.LOG.warn("whisper Fehler: {}", err.toString(StandardCharsets.UTF_8));
            return null;
        }
        return out.replaceAll("\\[[^\\]]*\\]", " ").replaceAll("\\([^)]*\\)", " ").replaceAll("\\s+", " ").trim();
    }
}
