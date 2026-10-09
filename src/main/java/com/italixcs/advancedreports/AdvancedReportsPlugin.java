package com.italixcs.advancedreports;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

public final class AdvancedReportsPlugin extends JavaPlugin implements Listener, TabExecutor {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String NO_REASON = "ɴᴏ ʀᴇᴀꜱᴏɴ ᴘʀᴏᴠɪᴅᴇᴅ";
    private static final Map<Integer, String> SMALL_CAPS = Map.ofEntries(
            Map.entry((int) 'a', "ᴀ"), Map.entry((int) 'b', "ʙ"), Map.entry((int) 'c', "ᴄ"),
            Map.entry((int) 'd', "ᴅ"), Map.entry((int) 'e', "ᴇ"), Map.entry((int) 'f', "ꜰ"),
            Map.entry((int) 'g', "ɢ"), Map.entry((int) 'h', "ʜ"), Map.entry((int) 'i', "ɪ"),
            Map.entry((int) 'j', "ᴊ"), Map.entry((int) 'k', "ᴋ"), Map.entry((int) 'l', "ʟ"),
            Map.entry((int) 'm', "ᴍ"), Map.entry((int) 'n', "ɴ"), Map.entry((int) 'o', "ᴏ"),
            Map.entry((int) 'p', "ᴘ"), Map.entry((int) 'r', "ʀ"), Map.entry((int) 's', "ꜱ"),
            Map.entry((int) 't', "ᴛ"), Map.entry((int) 'u', "ᴜ"), Map.entry((int) 'v', "ᴠ"),
            Map.entry((int) 'w', "ᴡ"), Map.entry((int) 'y', "ʏ"), Map.entry((int) 'z', "ᴢ"));

    private ReportRepository repository;
    private ReportGui gui;
    private volatile boolean ready;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        repository = new ReportRepository(this);
        gui = new ReportGui(this, repository);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(gui, this);
        var reportCommand = getCommand("report");
        var reportsCommand = getCommand("reports");
        if (reportCommand == null || reportsCommand == null) {
            getLogger().severe("Commands are missing from plugin.yml; disabling AdvancedReports.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        reportCommand.setExecutor(this);
        reportCommand.setTabCompleter(this);
        reportsCommand.setExecutor(this);
        gui.startRefreshTask();
        repository.initialize().whenComplete((unused, error) -> runSync(() -> {
            if (error != null) {
                getLogger().severe("Could not connect to MySQL or create the report tables: " + rootMessage(error));
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            ready = true;
            getLogger().info("Connected to MySQL; AdvancedReports is ready.");
            for (Player player : Bukkit.getOnlinePlayers()) repository.rememberPlayer(player.getUniqueId(), player.getName());
        }));
    }

    @Override
    public void onDisable() {
        ready = false;
        if (gui != null) gui.stopRefreshTask();
        if (repository != null) repository.close();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (ready) repository.rememberPlayer(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("reports")) {
            if (!(sender instanceof Player player)) {
                send(sender, "players-only");
                return true;
            }
            if (!player.hasPermission("advancedreports.view")) {
                send(sender, "no-permission");
                return true;
            }
            if (!ready) {
                send(sender, "starting");
                return true;
            }
            gui.openHome(player, 0, ReportRepository.Sort.MOST);
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("advancedreports.report")) {
            send(sender, "no-permission");
            return true;
        }
        if (args.length < 1) {
            send(player, "report-usage");
            return true;
        }
        if (!ready) {
            send(player, "starting");
            return true;
        }

        String inputName = args[0];
        String reason = String.join(" ", args).substring(inputName.length()).trim();
        int maxLength = Math.max(1, Math.min(255, getConfig().getInt("reasons.max-length", 20)));
        if (reason.codePointCount(0, reason.length()) > maxLength) {
            send(player, "reason-too-long", Map.of("limit", Integer.toString(maxLength)));
            return true;
        }
        if (!reason.isEmpty() && !getConfig().getBoolean("reasons.free", true)) {
            String selected = getConfig().getStringList("reasons.presets").stream()
                    .filter(option -> option.equalsIgnoreCase(reason)).findFirst().orElse(null);
            if (selected == null) {
                send(player, "invalid-reason", Map.of("reasons", String.join(", ",
                        getConfig().getStringList("reasons.presets").stream().map(this::smallCaps).toList())));
                return true;
            }
            reason = selected;
        }

        final String reportReason = reason;
        long cooldown = Math.max(0, getConfig().getLong("cooldown-seconds", 60)) * 1000L;
        Player onlineTarget = Bukkit.getOnlinePlayers().stream()
                .filter(candidate -> candidate.getName().equalsIgnoreCase(inputName)).findFirst().orElse(null);
        if (onlineTarget != null) {
            repository.rememberPlayer(onlineTarget.getUniqueId(), onlineTarget.getName());
            submit(player, onlineTarget.getUniqueId(), onlineTarget.getName(), reportReason, cooldown);
            return true;
        }
        var cached = Bukkit.getOfflinePlayerIfCached(inputName);
        if (cached != null) {
            submit(player, cached.getUniqueId(), cached.getName() == null ? inputName : cached.getName(), reportReason, cooldown);
            return true;
        }
        repository.knownPlayer(inputName).whenComplete((known, error) -> runSync(() -> {
            if (!player.isOnline()) return;
            if (error != null) {
                getLogger().warning("Could not look up report target: " + rootMessage(error));
                send(player, "database-error");
            } else if (known == null) send(player, "unknown-player");
            else submit(player, known.id(), known.name(), reportReason, cooldown);
        }));
        return true;
    }

    private void submit(Player player, UUID targetId, String targetName, String reason, long cooldown) {
        if (targetId.equals(player.getUniqueId())) {
            send(player, "self-report");
            return;
        }
        repository.submit(targetId, targetName, player.getUniqueId(), player.getName(), reason, cooldown)
                .whenComplete((submission, error) -> runSync(() -> {
                    if (!player.isOnline()) return;
                    if (error != null) {
                        getLogger().warning("Could not submit a report: " + rootMessage(error));
                        send(player, "database-error");
                        return;
                    }
                    switch (submission.status()) {
                        case DUPLICATE -> send(player, "duplicate");
                        case COOLDOWN -> send(player, "cooldown", Map.of("seconds", Long.toString(submission.waitSeconds())));
                        case CREATED -> {
                            send(player, "submitted");
                            Map<String, String> placeholders = Map.of(
                                    "player", smallCaps(targetName),
                                    "reporter", smallCaps(player.getName()),
                                    "reason", reason.isBlank() ? NO_REASON : smallCaps(reason),
                                    "total", Long.toString(submission.total()));
                            for (Player staff : Bukkit.getOnlinePlayers()) {
                                if (staff.hasPermission("advancedreports.view")) send(staff, "staff-notification", placeholders);
                            }
                        }
                    }
                }));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("report") || args.length != 1) return List.of();
        String start = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(start)) matches.add(player.getName());
        }
        return matches;
    }

    boolean isReady() { return ready; }
    void send(CommandSender recipient, String key) { send(recipient, key, Map.of()); }

    void send(CommandSender recipient, String key, Map<String, String> values) {
        String template = getConfig().getString("messages." + key, "<red>Missing message: " + key + "</red>");
        String prefix = getConfig().getString("messages.prefix", "");
        TagResolver.Builder resolver = TagResolver.builder();
        values.forEach((name, value) -> {
            if (name.equals("reason")) {
                String colorPath = value.equals(NO_REASON) ? "display.colors.muted" : "display.colors.text";
                TextColor color = TextColor.fromHexString(getConfig().getString(colorPath,
                        value.equals(NO_REASON) ? "#5C5C5C" : "#FFFFFF"));
                resolver.resolver(Placeholder.component(name, Component.text(value,
                        color == null ? TextColor.color(value.equals(NO_REASON) ? 0x5C5C5C : 0xFFFFFF) : color)));
            } else resolver.resolver(Placeholder.unparsed(name, value));
        });
        recipient.sendMessage(MINI.deserialize(prefix + template, resolver.build()));
    }

    Component noReasonComponent() {
        String muted = getConfig().getString("display.colors.muted", "#5C5C5C");
        TextColor color = TextColor.fromHexString(muted);
        return Component.text(NO_REASON, color == null ? TextColor.color(0x5C5C5C) : color);
    }

    String smallCaps(String input) {
        StringBuilder output = new StringBuilder();
        input.codePoints().forEach(codePoint -> output.append(SMALL_CAPS.getOrDefault(
                Character.toLowerCase(codePoint), new String(Character.toChars(codePoint)))));
        return output.toString();
    }

    DateTimeFormatter dateFormatter() {
        String pattern = getConfig().getString("display.date-format", "dd.MM.yyyy HH:mm");
        String zone = getConfig().getString("display.time-zone", "Europe/Berlin");
        try {
            return DateTimeFormatter.ofPattern(pattern, Locale.GERMAN).withZone(ZoneId.of(zone));
        } catch (IllegalArgumentException exception) {
            return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMAN).withZone(ZoneId.of("Europe/Berlin"));
        }
    }

    void runSync(Runnable task) {
        if (!isEnabled()) return;
        Bukkit.getScheduler().runTask(this, task);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) root = root.getCause();
        if (root instanceof CompletionException && root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
