package ru.vanila.live.plugin;

import java.util.ArrayList;
import java.util.List;

/** Очистка и разбиение текста. Только JDK. */
final class Text {
    private Text() {}

    /** Убирает управляющие/невидимые символы, §, схлопывает пробелы, ограничивает длину (в code points). */
    static String clean(String in, int max) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(in.length(), max + 2));
        int count = 0;
        boolean pendingSpace = false;
        for (int i = 0; i < in.length(); ) {
            int cp = in.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isISOControl(cp) || cp == 0xA0 || cp == 0x3000) {
                pendingSpace = sb.length() > 0;
                continue;
            }
            int type = Character.getType(cp);
            if (cp == '§' || isInvisible(cp) || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
                continue;
            }
            if (count >= max) {
                sb.append('…');
                break;
            }
            if (pendingSpace) {
                sb.append(' ');
                count++;
                pendingSpace = false;
            }
            sb.appendCodePoint(cp);
            count++;
        }
        return sb.toString();
    }

    private static boolean isInvisible(int cp) {
        return (cp >= 0x200B && cp <= 0x200F) || (cp >= 0x202A && cp <= 0x202E)
                || (cp >= 0x2060 && cp <= 0x206F) || cp == 0xFEFF || cp == 0x00AD;
    }

    /** Делит строку на части не длиннее max, по возможности по переводам строки. */
    static List<String> split(String s, int max) {
        List<String> out = new ArrayList<>();
        String rest = s == null ? "" : s;
        while (rest.length() > max) {
            int idx = rest.lastIndexOf('\n', max);
            if (idx < max / 2) idx = max;
            if (idx > 0 && Character.isHighSurrogate(rest.charAt(idx - 1))) idx--;
            out.add(rest.substring(0, idx));
            rest = rest.substring(idx);
            if (rest.startsWith("\n")) rest = rest.substring(1);
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }
}
