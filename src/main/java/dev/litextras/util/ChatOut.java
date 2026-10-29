package dev.litextras.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.List;

/**
 * EINZIGER Ausgabeweg des Mods für Text: das lokale Chatfenster (nur für mich sichtbar).
 * Diese Klasse sendet NIEMALS etwas an den Server (kein sendChatMessage / sendChatCommand).
 * Das ist fest verdrahtet und nicht per Config änderbar.
 */
public final class ChatOut {
    private ChatOut() {}

    private static final int PREFIX_COLOR = 0x55FFFF;

    public static void info(String msg) { send(msg, 0xFFFFFF); }
    public static void warn(String msg) { send(msg, 0xFFFF55); }
    public static void error(String msg) { send(msg, 0xFF5555); }
    public static void ok(String msg) { send(msg, 0x55FF55); }

    public static void lines(List<String> msgs) {
        for (String m : msgs) info(m);
    }

    public static void send(String msg, int rgb) {
        MutableText t = Text.literal("[LX] ").styled(s -> s.withColor(PREFIX_COLOR))
                .append(Text.literal(msg).styled(s -> s.withColor(rgb & 0xFFFFFF)));
        sendText(t);
    }

    public static void segments(List<Template.Seg> segs) {
        MutableText t = Text.literal("[LX] ").styled(s -> s.withColor(PREFIX_COLOR));
        for (Template.Seg seg : segs) {
            int c = seg.color() & 0xFFFFFF;
            t.append(Text.literal(seg.text()).styled(s -> s.withColor(c)));
        }
        sendText(t);
    }

    private static void sendText(Text t) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(t);
        });
    }
}
