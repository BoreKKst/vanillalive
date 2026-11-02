package ru.vanila.live.plugin;

import java.util.Comparator;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /list [страница]: работает только на Paper API, без сети и без Discord-бота. */
final class ListCommand implements CommandExecutor {
    private final int pageSize;

    ListCommand(int pageSize) {
        this.pageSize = pageSize;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> names = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .sorted(Comparator.comparing(String::toLowerCase))
                .toList();
        int total = names.size();
        int max = Bukkit.getMaxPlayers();

        if (total == 0) {
            sender.sendMessage(Component.text("Сервер пуст: игроков онлайн нет (0/" + max + ").", NamedTextColor.YELLOW));
            return true;
        }

        int pages = (total + pageSize - 1) / pageSize;
        int page = 1;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Номер страницы должен быть числом. Использование: /" + label + " [страница]", NamedTextColor.RED));
                return true;
            }
            if (page < 1 || page > pages) {
                sender.sendMessage(Component.text("Страницы " + page + " не существует. Доступно страниц: " + pages
                        + " (от 1 до " + pages + ").", NamedTextColor.RED));
                return true;
            }
        }

        int from = (page - 1) * pageSize;
        int to = Math.min(from + pageSize, total);
        sender.sendMessage(Component.text("Игроки онлайн (" + total + "/" + max + ")", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Страница " + page + "/" + pages, NamedTextColor.GRAY));
        sender.sendMessage(Component.text(String.join(", ", names.subList(from, to)), NamedTextColor.WHITE));
        if (page < pages) {
            String next = "/" + label + " " + (page + 1);
            sender.sendMessage(Component.text("Следующая страница: ", NamedTextColor.GRAY)
                    .append(Component.text(next, NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand(next))));
        }
        return true;
    }
}
