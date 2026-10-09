package com.italixcs.advancedreports;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class ReportGui implements Listener {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int FILTER_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    enum Type { HOME, PLAYER, ACTIONS, CONFIRM }

    static final class Menu implements InventoryHolder {
        final Type type;
        final int page;
        final ReportRepository.Sort sort;
        final ReportRepository.Sort homeSort;
        final UUID targetId;
        final String targetName;
        final ReportRepository.Report report;
        final long reportId;
        final long reportNumber;
        final String reason;
        final String reporterName;
        final long createdAt;
        long total;
        final int returnPage;
        final ReportRepository.Sort returnSort;
        final List<ReportRepository.Target> targets = new ArrayList<>();
        final List<ReportRepository.Report> reports = new ArrayList<>();
        Inventory inventory;

        Menu(Type type, int page, ReportRepository.Sort sort, ReportRepository.Sort homeSort,
             UUID targetId, String targetName, ReportRepository.Report report, int returnPage,
             ReportRepository.Sort returnSort) {
            this.type = type;
            this.page = page;
            this.sort = sort;
            this.homeSort = homeSort;
            this.targetId = targetId;
            this.targetName = targetName;
            this.report = report;
            this.reportId = report == null ? 0 : report.id();
            this.reportNumber = report == null ? 0 : report.number();
            this.reason = report == null ? "" : report.reason();
            this.reporterName = report == null ? "" : report.reporterName();
            this.createdAt = report == null ? 0 : report.createdAt();
            this.returnPage = returnPage;
            this.returnSort = returnSort;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private final AdvancedReportsPlugin plugin;
    private final ReportRepository repository;
    private final Map<UUID, CompletableFuture<PlayerProfile>> skins = new ConcurrentHashMap<>();
    private final Map<Inventory, Map<Integer, UUID>> skinApplied = new WeakHashMap<>();
    private final java.util.Set<UUID> refreshing = ConcurrentHashMap.newKeySet();
    private BukkitTask refreshTask;

    ReportGui(AdvancedReportsPlugin plugin, ReportRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    void startRefreshTask() {
        int interval = Math.max(20, plugin.getConfig().getInt("display.refresh-ticks", 40));
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu
                        && (menu.type == Type.HOME || menu.type == Type.PLAYER)
                        && refreshing.add(player.getUniqueId())) {
                    refresh(player, menu);
                }
            }
        }, interval, interval);
    }

    void stopRefreshTask() {
        if (refreshTask != null) refreshTask.cancel();
    }

    void openHome(Player player, int page, ReportRepository.Sort sort) {
        if (!plugin.isReady()) {
            plugin.send(player, "starting");
            return;
        }
        repository.targets(sort, Math.max(0, page), PAGE_SIZE).whenComplete((result, error) -> plugin.runSync(() -> {
            if (!player.isOnline()) return;
            if (error != null) {
                failed(player, error);
                return;
            }
            int safePage = result.total() == 0 ? 0 : Math.min(Math.max(0, page), (int) ((result.total() - 1) / PAGE_SIZE));
            if (safePage != page) {
                openHome(player, safePage, sort);
                return;
            }
            Menu menu = new Menu(Type.HOME, safePage, sort, sort, null, null, null, 0, sort);
            menu.targets.addAll(result.items());
            menu.total = result.total();
            String start = plugin.getConfig().getString("display.colors.title-start", "#FD0000");
            String end = plugin.getConfig().getString("display.colors.title-end", "#FF2829");
            menu.inventory = Bukkit.createInventory(menu, 54, MINI.deserialize(
                    "<gradient:" + start + ":" + end + ">ᴀᴅᴠᴀɴᴄᴇᴅ ʀᴇᴘᴏʀᴛꜱ</gradient>"));
            drawHome(menu, result.total());
            player.openInventory(menu.inventory);
        }));
    }

    void openPlayer(Player player, UUID targetId, String targetName, int page,
                    ReportRepository.Sort sort, ReportRepository.Sort homeSort) {
        repository.reports(targetId, sort, Math.max(0, page), PAGE_SIZE).whenComplete((result, error) -> plugin.runSync(() -> {
            if (!player.isOnline()) return;
            if (error != null) {
                failed(player, error);
                return;
            }
            int safePage = result.total() == 0 ? 0 : Math.min(Math.max(0, page), (int) ((result.total() - 1) / PAGE_SIZE));
            if (safePage != page) {
                openPlayer(player, targetId, targetName, safePage, sort, homeSort);
                return;
            }
            Menu menu = new Menu(Type.PLAYER, safePage, sort, homeSort, targetId, targetName,
                    null, 0, sort);
            menu.reports.addAll(result.items());
            menu.total = result.total();
            menu.inventory = Bukkit.createInventory(menu, 54, Component.text(
                    plugin.smallCaps(targetName) + " | ʀᴇᴘᴏʀᴛꜱ: " + result.total(), color("display.colors.player-title", "#E22400")));
            drawPlayer(menu, result.total());
            player.openInventory(menu.inventory);
        }));
    }

    private void openActions(Player player, Menu origin, ReportRepository.Report report) {
        Menu menu = new Menu(Type.ACTIONS, 0, origin.sort, origin.homeSort, report.targetId(),
                report.targetName(), report, origin.page, origin.sort);
        menu.inventory = Bukkit.createInventory(menu, 27, Component.text(plugin.smallCaps(report.targetName()),
                color("display.colors.player-title", "#E22400")));
        menu.inventory.setItem(11, item(Material.ENDER_PEARL, "ᴛᴇʟᴇᴘᴏʀᴛ ᴛᴏ ᴘʟᴀʏᴇʀ", List.of()));
        menu.inventory.setItem(12, item(Material.ENDER_EYE, "ᴛᴇʟᴇᴘᴏʀᴛ ʜᴇʀᴇ", List.of()));
        menu.inventory.setItem(13, item(Material.BOOK, "ʀᴇᴀꜱᴏɴ", reasonLore(report.reason())));
        menu.inventory.setItem(14, playerHead(report.targetId(), report.targetName(), List.of(
                gray("-------------"), white("ᴄʟɪᴄᴋ ᴛᴏ ᴄᴏᴘʏ ɴᴀᴍᴇ"))));
        menu.inventory.setItem(15, item(Material.RED_DYE, "ᴅᴇʟᴇᴛᴇ ʀᴇᴘᴏʀᴛ", List.of()));
        menu.inventory.setItem(18, item(Material.ARROW, "ʙᴀᴄᴋ ᴘᴀɢᴇ", List.of()));
        menu.inventory.setItem(26, item(Material.BARRIER, "ʀᴇᴛᴜʀɴ ᴛᴏ ʜᴏᴍᴇ", List.of()));
        requestSkin(menu, 14, report.targetId(), report.targetName());
        player.openInventory(menu.inventory);
    }

    private void openConfirm(Player player, Menu actions) {
        Menu menu = new Menu(Type.CONFIRM, 0, actions.sort, actions.homeSort, actions.targetId,
                actions.targetName, actions.report, actions.returnPage, actions.returnSort);
        menu.inventory = Bukkit.createInventory(menu, 27, Component.text("ᴄᴏɴꜰɪʀᴍᴀᴛɪᴏɴ",
                color("display.colors.player-title", "#E22400")));
        menu.inventory.setItem(12, item(Material.BARRIER, "ɢᴏ ʙᴀᴄᴋ", List.of()));
        menu.inventory.setItem(14, item(Material.GREEN_DYE, "ᴅᴇʟᴇᴛᴇ ɪᴛ", List.of()));
        player.openInventory(menu.inventory);
    }

    private void drawHome(Menu menu, long total) {
        Inventory inventory = menu.inventory;
        inventory.clear();
        menu.total = total;
        for (int index = 0; index < menu.targets.size(); index++) {
            ReportRepository.Target target = menu.targets.get(index);
            List<Component> lore = List.of(
                    gray("-------------"),
                    white("ꜰɪʟᴛᴇʀᴇᴅ ᴄᴀᴛᴇɢᴏʀʏ:"),
                    Component.empty(),
                    white("- " + homeSortName(menu.sort)),
                    gray("--------------"),
                    white("ᴛᴏᴛᴀʟ ʀᴇᴘᴏʀᴛꜱ:"),
                    white("- " + target.count()),
                    Component.empty());
            inventory.setItem(index, playerHead(target.id(), target.name(), lore));
            requestSkin(menu, index, target.id(), target.name());
        }
        if (total == 0) inventory.setItem(22, item(Material.KNOWLEDGE_BOOK, "ɴᴏ ʀᴇᴘᴏʀᴛꜱ", List.of()));
        inventory.setItem(PREVIOUS_SLOT, menu.page == 0
                ? item(Material.BARRIER, "ᴇxɪᴛ", List.of())
                : pageArrow(false, menu.page, menu.page + 1));
        inventory.setItem(FILTER_SLOT, filter(menu.sort, false));
        inventory.setItem(NEXT_SLOT, (menu.page + 1L) * PAGE_SIZE >= total
                ? item(Material.BARRIER, "ᴇxɪᴛ", List.of())
                : pageArrow(true, menu.page + 2, menu.page + 1));
    }

    private void drawPlayer(Menu menu, long total) {
        Inventory inventory = menu.inventory;
        inventory.clear();
        menu.total = total;
        for (int index = 0; index < menu.reports.size(); index++) {
            ReportRepository.Report report = menu.reports.get(index);
            List<Component> lore = reportLore(report);
            inventory.setItem(index, item(Material.BOOK, "ʀᴇᴘᴏʀᴛ ɴᴜᴍʙᴇʀ: " + report.number(), lore));
        }
        if (total == 0) inventory.setItem(22, item(Material.KNOWLEDGE_BOOK, "ɴᴏ ʀᴇᴘᴏʀᴛꜱ", List.of()));
        inventory.setItem(PREVIOUS_SLOT, menu.page == 0
                ? item(Material.BARRIER, "ᴇxɪᴛ", List.of())
                : pageArrow(false, menu.page, menu.page + 1));
        inventory.setItem(FILTER_SLOT, filter(menu.sort, true));
        inventory.setItem(NEXT_SLOT, (menu.page + 1L) * PAGE_SIZE >= total
                ? item(Material.BARRIER, "ᴇxɪᴛ", List.of())
                : pageArrow(true, menu.page + 2, menu.page + 1));
    }

    private void refresh(Player player, Menu menu) {
        if (menu.type == Type.HOME) {
            repository.targets(menu.sort, menu.page, PAGE_SIZE).whenComplete((result, error) -> plugin.runSync(() -> {
                refreshing.remove(player.getUniqueId());
                if (error != null || !stillOpen(player, menu)) return;
                int lastPage = result.total() == 0 ? 0 : (int) ((result.total() - 1) / PAGE_SIZE);
                if (menu.page > lastPage) {
                    openHome(player, lastPage, menu.sort);
                    return;
                }
                menu.targets.clear();
                menu.targets.addAll(result.items());
                menu.total = result.total();
                drawHome(menu, result.total());
            }));
        } else {
            repository.reports(menu.targetId, menu.sort, menu.page, PAGE_SIZE).whenComplete((result, error) -> plugin.runSync(() -> {
                refreshing.remove(player.getUniqueId());
                if (error != null || !stillOpen(player, menu)) return;
                int lastPage = result.total() == 0 ? 0 : (int) ((result.total() - 1) / PAGE_SIZE);
                if (menu.page > lastPage) {
                    openPlayer(player, menu.targetId, menu.targetName, lastPage, menu.sort, menu.homeSort);
                    return;
                }
                if (result.total() != menu.total) {
                    openPlayer(player, menu.targetId, menu.targetName, menu.page, menu.sort, menu.homeSort);
                    return;
                }
                menu.reports.clear();
                menu.reports.addAll(result.items());
                menu.total = result.total();
                drawPlayer(menu, result.total());
            }));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= menu.inventory.getSize()) return;
        int slot = event.getRawSlot();
        if (!plugin.isReady()) return;

        if (menu.type == Type.CONFIRM) {
            if (slot == 12) openActions(player, menu, menu.report);
            else if (slot == 14) delete(player, menu);
            return;
        }
        if (menu.type == Type.ACTIONS) {
            if (slot == 11 || slot == 12) teleport(player, menu, slot == 12);
            else if (slot == 14) {
                if (!player.hasPermission("advancedreports.actions")) plugin.send(player, "no-permission");
                else copyName(player, menu.targetName);
            }
            else if (slot == 15) {
                if (!player.hasPermission("advancedreports.delete")) plugin.send(player, "no-permission");
                else openConfirm(player, menu);
            } else if (slot == 18) openPlayer(player, menu.targetId, menu.targetName,
                    menu.returnPage, menu.returnSort, menu.homeSort);
            else if (slot == 26) openHome(player, 0, menu.homeSort);
            return;
        }

        if (slot < PAGE_SIZE) {
            if (menu.type == Type.HOME && slot < menu.targets.size()) {
                ReportRepository.Target target = menu.targets.get(slot);
                openPlayer(player, target.id(), target.name(), 0, ReportRepository.Sort.NEWEST, menu.sort);
            } else if (menu.type == Type.PLAYER && slot < menu.reports.size()) {
                if (!player.hasPermission("advancedreports.actions")) {
                    plugin.send(player, "no-permission");
                    return;
                }
                ReportRepository.Report selected = menu.reports.get(slot);
                repository.report(selected.id()).whenComplete((report, error) -> plugin.runSync(() -> {
                    if (!player.isOnline()) return;
                    if (error != null) failed(player, error);
                    else if (report == null) plugin.send(player, "report-not-found");
                    else openActions(player, menu, report);
                }));
            }
            return;
        }

        if (menu.type == Type.HOME || menu.type == Type.PLAYER) {
            if (slot == PREVIOUS_SLOT) {
                if (menu.page > 0) {
                    if (menu.type == Type.HOME) openHome(player, menu.page - 1, menu.sort);
                    else openPlayer(player, menu.targetId, menu.targetName, menu.page - 1, menu.sort, menu.homeSort);
                } else if (menu.type == Type.PLAYER) openHome(player, 0, menu.homeSort);
                else player.closeInventory();
            } else if (slot == FILTER_SLOT) {
                ReportRepository.Sort next = nextSort(menu.sort, menu.type == Type.PLAYER);
                if (menu.type == Type.HOME) openHome(player, 0, next);
                else openPlayer(player, menu.targetId, menu.targetName, 0, next, menu.homeSort);
            } else if (slot == NEXT_SLOT) {
                if ((menu.page + 1L) * PAGE_SIZE >= menu.total) player.closeInventory();
                else if (menu.type == Type.HOME) openHome(player, menu.page + 1, menu.sort);
                else openPlayer(player, menu.targetId, menu.targetName, menu.page + 1, menu.sort, menu.homeSort);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    private void delete(Player player, Menu menu) {
        if (!player.hasPermission("advancedreports.delete")) {
            plugin.send(player, "no-permission");
            return;
        }
        repository.delete(menu.reportId, menu.targetId).whenComplete((result, error) -> plugin.runSync(() -> {
            if (!player.isOnline()) return;
            if (error != null) {
                failed(player, error);
                return;
            }
            if (!result.deleted()) {
                plugin.send(player, "report-not-found");
            } else {
                plugin.send(player, "delete-success", Map.of("number", Long.toString(menu.reportNumber)));
            }
            openPlayer(player, menu.targetId, menu.targetName, menu.returnPage,
                    menu.returnSort, menu.homeSort);
        }));
    }

    private void teleport(Player staff, Menu menu, boolean bringTarget) {
        if (!staff.hasPermission("advancedreports.actions")) {
            plugin.send(staff, "no-permission");
            return;
        }
        Player target = Bukkit.getPlayer(menu.targetId);
        if (target == null) {
            plugin.send(staff, "player-offline");
            return;
        }
        if (bringTarget) {
            target.teleport(staff.getLocation());
            plugin.send(staff, "teleported-here", Map.of("player", plugin.smallCaps(target.getName())));
        } else {
            staff.teleport(target.getLocation());
            plugin.send(staff, "teleported", Map.of("player", plugin.smallCaps(target.getName())));
        }
    }

    private void copyName(Player player, String realName) {
        player.closeInventory();
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String template = plugin.getConfig().getString("messages.copy-name", "<white>Click to copy <player>.</white>");
        Component name = white(plugin.smallCaps(realName)).clickEvent(ClickEvent.copyToClipboard(realName));
        player.sendMessage(MINI.deserialize(prefix + template, Placeholder.component("player", name)));
    }

    private ItemStack filter(ReportRepository.Sort sort, boolean playerScreen) {
        List<String> options = playerScreen ? List.of("ʀᴇᴄᴇɴᴛ ʀᴇᴘᴏʀᴛꜱ", "ᴏʟᴅᴇꜱᴛ ʀᴇᴘᴏʀᴛꜱ")
                : List.of("ᴍᴏꜱᴛ ʀᴇᴘᴏʀᴛꜱ", "ʟᴇᴀꜱᴛ ʀᴇᴘᴏʀᴛꜱ", "ʀᴇᴄᴇɴᴛ ʀᴇᴘᴏʀᴛꜱ", "ᴏʟᴅᴇꜱᴛ ʀᴇᴘᴏʀᴛꜱ");
        String selected = playerScreen
                ? (sort == ReportRepository.Sort.PLAYER_OLDEST ? "ᴏʟᴅᴇꜱᴛ ʀᴇᴘᴏʀᴛꜱ" : "ʀᴇᴄᴇɴᴛ ʀᴇᴘᴏʀᴛꜱ")
                : homeSortName(sort);
        List<Component> lore = new ArrayList<>();
        lore.add(gray("- - - - - - - - -"));
        for (String option : options) lore.add(option.equals(selected) ? green(option) : white(option));
        return item(Material.HOPPER, "ᴄᴀᴛᴇɢᴏʀʏ ꜰɪʟᴛᴇʀ", lore);
    }

    private ItemStack pageArrow(boolean next, int page, int currentPage) {
        String label = next ? "ɴᴇxᴛ ᴘᴀɢᴇ" : "ʙᴀᴄᴋ ᴘᴀɢᴇ";
        String direction = next ? "ɴᴇxᴛ" : "ᴘʀᴇᴠɪᴏᴜꜱ";
        return item(Material.ARROW, label, List.of(
                gray(direction + " ᴘᴀɢᴇ:"), gray(Integer.toString(page)),
                gray("ᴄᴜʀʀᴇɴᴛ ᴘᴀɢᴇ:"), gray(Integer.toString(currentPage))));
    }

    private List<Component> reportLore(ReportRepository.Report report) {
        List<Component> lore = new ArrayList<>();
        lore.add(gray("- " + report.number()));
        lore.add(gray("------------------"));
        lore.add(white("ʀᴇᴀꜱᴏɴ:"));
        lore.add(reasonComponent(report.reason()));
        lore.add(gray("------------------"));
        lore.add(white("ᴘᴇʀꜱᴏɴ ᴡʜᴏ ʀᴇᴘᴏʀᴛᴇᴅ"));
        lore.add(white("- " + plugin.smallCaps(report.reporterName())));
        lore.add(gray("------------------"));
        lore.add(white("ᴅᴀᴛᴇ ᴡʜᴇɴ ʀᴇᴘᴏʀᴛᴇᴅ"));
        lore.add(white("- " + plugin.dateFormatter().format(Instant.ofEpochMilli(report.createdAt()))));
        return lore;
    }

    private List<Component> reasonLore(String reason) {
        return List.of(gray("------------------"), white("ʀᴇᴀꜱᴏɴ:"), reasonComponent(reason));
    }

    private Component reasonComponent(String reason) {
        return reason == null || reason.isBlank() ? plugin.noReasonComponent() : white(plugin.smallCaps(reason));
    }

    private ItemStack playerHead(UUID id, String name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.displayName(nonItalic(white(plugin.smallCaps(name))));
        meta.lore(lore.stream().map(this::nonItalic).toList());
        item.setItemMeta(meta);
        return item;
    }

    private void requestSkin(Menu menu, int slot, UUID id, String name) {
        if (id.equals(skinApplied.getOrDefault(menu.inventory, Map.of()).get(slot))) return;
        CompletableFuture<PlayerProfile> profile;
        try {
            profile = skins.computeIfAbsent(id, unused -> Bukkit.createProfile(id, name).update());
        } catch (IllegalArgumentException exception) {
            return;
        }
        profile.thenAccept(value -> plugin.runSync(() -> {
            if (!(menu.inventory.getHolder() instanceof Menu) || menu.inventory.getItem(slot) == null) return;
            ItemStack head = menu.inventory.getItem(slot);
            if (head.getType() != Material.PLAYER_HEAD
                    || !stillOpenHolder(menu) || !targetAt(menu, slot, id)) return;
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setPlayerProfile(value);
            head.setItemMeta(meta);
            menu.inventory.setItem(slot, head);
            skinApplied.computeIfAbsent(menu.inventory, unused -> new HashMap<>()).put(slot, id);
        }));
    }

    private boolean targetAt(Menu menu, int slot, UUID id) {
        if (menu.type == Type.ACTIONS) return slot == 14 && menu.targetId.equals(id);
        return menu.type == Type.HOME && slot < menu.targets.size() && menu.targets.get(slot).id().equals(id);
    }

    private boolean stillOpenHolder(Menu expected) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getOpenInventory().getTopInventory() == expected.inventory) return true;
        }
        return false;
    }

    private boolean stillOpen(Player player, Menu expected) {
        return player.getOpenInventory().getTopInventory() == expected.inventory;
    }

    private ItemStack item(Material material, String name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        Component displayName = switch (material) {
            case BARRIER, RED_DYE -> Component.text(name, color("display.colors.danger", "#FF2021"));
            case GREEN_DYE -> green(name);
            default -> white(name);
        };
        meta.displayName(nonItalic(displayName));
        if (!lore.isEmpty()) meta.lore(lore.stream().map(this::nonItalic).toList());
        item.setItemMeta(meta);
        return item;
    }

    private Component nonItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private Component white(String text) { return Component.text(text, color("display.colors.text", "#FFFFFF")); }
    private Component green(String text) { return Component.text(text, color("display.colors.active", "#3DFF7C")); }
    private Component gray(String text) { return Component.text(text, color("display.colors.muted", "#5C5C5C")); }

    private TextColor color(String path, String fallback) {
        TextColor color = TextColor.fromHexString(plugin.getConfig().getString(path, fallback));
        return color == null ? TextColor.fromHexString(fallback) : color;
    }

    private String homeSortName(ReportRepository.Sort sort) {
        return switch (sort) {
            case LEAST -> "ʟᴇᴀꜱᴛ ʀᴇᴘᴏʀᴛꜱ";
            case RECENT -> "ʀᴇᴄᴇɴᴛ ʀᴇᴘᴏʀᴛꜱ";
            case OLDEST -> "ᴏʟᴅᴇꜱᴛ ʀᴇᴘᴏʀᴛꜱ";
            default -> "ᴍᴏꜱᴛ ʀᴇᴘᴏʀᴛꜱ";
        };
    }

    private ReportRepository.Sort nextSort(ReportRepository.Sort current, boolean playerScreen) {
        if (playerScreen) return current == ReportRepository.Sort.NEWEST
                ? ReportRepository.Sort.PLAYER_OLDEST : ReportRepository.Sort.NEWEST;
        return switch (current) {
            case MOST -> ReportRepository.Sort.LEAST;
            case LEAST -> ReportRepository.Sort.RECENT;
            case RECENT -> ReportRepository.Sort.OLDEST;
            default -> ReportRepository.Sort.MOST;
        };
    }

    private void failed(Player player, Throwable error) {
        plugin.getLogger().warning("Report GUI database operation failed: " + error.getMessage());
        plugin.send(player, "database-error");
    }
}
