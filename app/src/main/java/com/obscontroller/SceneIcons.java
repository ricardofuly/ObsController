package com.obscontroller;

import java.text.Normalizer;
import java.util.Locale;

/** Ícones disponíveis para as cenas e o palpite automático pelo nome. */
final class SceneIcons {

    static final String[] KEYS = {
            "play", "user", "cam", "screen", "game", "chat", "coffee", "flag",
            "max", "mic", "music", "bell", "star", "headset", "clock", "layers"
    };
    static final String[] LABELS = {
            "Início", "Pessoa", "Câmera", "Tela", "Jogo", "Chat", "Pausa", "Fim",
            "Cheia", "Voz", "Música", "Alerta", "Destaque", "Fone", "Espera", "Outra"
    };
    static final int[] RES = {
            R.drawable.ic_play, R.drawable.ic_user, R.drawable.ic_cam, R.drawable.ic_screen,
            R.drawable.ic_game, R.drawable.ic_chat, R.drawable.ic_coffee, R.drawable.ic_flag,
            R.drawable.ic_max, R.drawable.ic_mic, R.drawable.ic_music, R.drawable.ic_bell,
            R.drawable.ic_star, R.drawable.ic_headset, R.drawable.ic_clock, R.drawable.ic_layers
    };

    /** Cor da tecla (estilo deck) para cada ícone, na mesma ordem de KEYS. Todas passam 4.5:1 com texto branco. */
    static final int[] COLORS = {
            0xFF2457C5, 0xFF0B7A6E, 0xFF1F5FB0, 0xFF6A3FB5, 0xFFB4245A, 0xFF8A6A00, 0xFF9A5B00, 0xFF4A5262,
            0xFF4B47B8, 0xFF7A3FA8, 0xFFA3268A, 0xFFB5451B, 0xFF8F6B00, 0xFF0E7490, 0xFF555B66, 0xFF3F4552
    };

    static int colorFor(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return COLORS[i];
        return 0xFF3F4552;
    }

    /** Palavras-chave (sem acento, minúsculas) → ícone. A ordem importa: a primeira que bater vence. */
    private static final String[][] RULES = {
            {"max", "full", "cheia", "inteira"},
            {"coffee", "brb", "pausa", "volto", "break", "intervalo", "afk"},
            {"play", "intro", "inicio", "abertura", "start", "comecando"},
            {"flag", "end", "fim", "encerr", "outro", "tchau", "final"},
            {"game", "game", "jogo", "gaming"},
            {"chat", "chat", "conversa", "papo", "talk"},
            {"cam", "cam", "camera", "webcam", "face"},
            {"screen", "tela", "screen", "desktop", "monitor", "display"},
            {"music", "music", "musica", "dj"},
            {"bell", "alert", "aviso", "notif"},
            {"star", "destaque", "highlight", "replay", "clip"},
            {"clock", "espera", "wait", "soon", "aguard"},
            {"mic", "mic", "voz", "podcast", "fala"},
            {"headset", "fone", "audio"},
            {"user", "main", "principal", "live", "ao vivo", "host"},
    };

    static int resFor(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return RES[i];
        return R.drawable.ic_layers;
    }

    static String guess(String sceneName) {
        String n = Normalizer.normalize(sceneName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        for (String[] rule : RULES) {
            for (int i = 1; i < rule.length; i++) {
                if (n.contains(rule[i])) return rule[0];
            }
        }
        return "layers";
    }

    /** Se o nome começa com emoji/símbolo (ex.: "☕ BRB"), devolve {emoji, resto}; senão null. */
    static String[] splitEmoji(String name) {
        String s = name.trim();
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (Character.isLetterOrDigit(cp) || Character.isWhitespace(cp)) break;
            i += Character.charCount(cp);
        }
        if (i == 0 || s.codePointAt(0) < 0x2000) return null; // ignora pontuação comum como "[" ou "-"
        String rest = s.substring(i).trim();
        return new String[]{s.substring(0, i), rest.isEmpty() ? s.substring(0, i) : rest};
    }

    private SceneIcons() {
    }
}
