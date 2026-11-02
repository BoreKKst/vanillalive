package ru.vanila.live.bot;

import java.util.List;
import java.util.regex.Pattern;

/** Вырезает секреты и ANSI-коды из строк консоли. Только JDK. */
final class Redactor {
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d?]*[ -/]*[@-~]");
    private static final Pattern DISCORD_TOKEN =
            Pattern.compile("[A-Za-z0-9_-]{23,28}\\.[A-Za-z0-9_-]{6,7}\\.[A-Za-z0-9_-]{27,}");
    private static final Pattern KV = Pattern.compile(
            "(?i)\\b(password|passwd|pass|token|secret|api[-_ ]?key|authorization)\\b\\s*[=:]\\s*\\S+");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]+");

    private final List<String> secrets;

    Redactor(List<String> secrets) {
        this.secrets = List.copyOf(secrets);
    }

    String redact(String line) {
        if (line == null) return "";
        String s = ANSI.matcher(line).replaceAll("");
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 6) s = s.replace(secret, "[REDACTED]");
        }
        s = DISCORD_TOKEN.matcher(s).replaceAll("[REDACTED]");
        s = BEARER.matcher(s).replaceAll("Bearer [REDACTED]");
        s = KV.matcher(s).replaceAll("$1=[REDACTED]");
        return s;
    }
}
