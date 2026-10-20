package dev.hosttools;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class HostTools extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private static final String[] COMMANDS = {
            "hshout", "hscreen", "hall", "hbar", "hmute", "hunmute", "hmutelist", "hwarn", "hwarns", "hunwarn",
            "hdm", "hsc", "hclearchat", "hslow", "hfreeze", "hcountdown", "hheal", "hfeed", "hfly", "hvanish", "hhelp"
    };
    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    // ---- state ----
    private boolean globalMute = false;
    private int slowSeconds = 0;
    private final Map<UUID, String> muted = new HashMap<>();        // uuid -> reason
    private final Map<UUID, String> mutedNames = new HashMap<>();   // uuid -> last known name
    private final Map<UUID, List<String>> warns = new HashMap<>();
    private final Map<UUID, String> warnNames = new HashMap<>();
    private final Set<UUID> frozen = new HashSet<>();
    private final Set<UUID> vanished = new HashSet<>();
    private final Map<UUID, Long> lastChat = new HashMap<>();
    private BukkitTask countdownTask = null;
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        for (String name : COMMANDS) {
            if (getCommand(name) != null) {
                getCommand(name).setExecutor(this);
                getCommand(name).setTabCompleter(this);
            }
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("HostTools включён. Все команды начинаются с 'h' (см. /hhelp). Только для операторов.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // =====================================================================
    // helpers
    // =====================================================================
    /** Russian plural: pl(n, "игрок", "игрока", "игроков"). */
    private static String pl(int n, String one, String few, String many) {
        int m100 = Math.abs(n) % 100;
        int m10 = Math.abs(n) % 10;
        if (m100 >= 11 && m100 <= 14) return many;
        if (m10 == 1) return one;
        if (m10 >= 2 && m10 <= 4) return few;
        return many;
    }

    private static Component c(String legacy) {
        return AMP.deserialize(legacy);
    }

    private Component prefix() {
        return c(getConfig().getString("prefix", "&c&l[UHC] &r"));
    }

    private void say(CommandSender s, String legacy) {
        s.sendMessage(prefix().append(c(legacy)));
    }

    private Sound snd(String key, float pitch) {
        String id = getConfig().getString("sounds." + key, "minecraft:block.note_block.bell");
        return Sound.sound(Key.key(id), Sound.Source.MASTER, 1.0f, pitch);
    }

    private void ding(Audience a) {
        a.playSound(snd("ding", 1.2f));
    }

    private boolean bypass(Player p) {
        return p.hasPermission("hosttools.bypass");
    }

    private void notifyStaff(String legacy) {
        Component msg = prefix().append(c(legacy));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("hosttools.use")) p.sendMessage(msg);
        }
        Bukkit.getConsoleSender().sendMessage(msg);
    }

    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private String joinArgs(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private void screen(Audience a, String title, String subtitle) {
        Title t = Title.title(
                c(title),
                c(subtitle),
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofMillis(800)));
        a.showTitle(t);
        ding(a);
    }

    private List<Player> targets(CommandSender sender, String arg) {
        List<Player> list = new ArrayList<>();
        if (arg.equalsIgnoreCase("all")) {
            list.addAll(Bukkit.getOnlinePlayers());
        } else {
            Player p = Bukkit.getPlayerExact(arg);
            if (p == null) {
                say(sender, "&cИгрока нет на сервере: &f" + arg);
            } else {
                list.add(p);
            }
        }
        return list;
    }

    // =====================================================================
    // data
    // =====================================================================
    private void loadData() {
        muted.clear(); mutedNames.clear(); warns.clear(); warnNames.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        globalMute = y.getBoolean("global-mute", false);
        slowSeconds = y.getInt("slow-seconds", 0);
        ConfigurationSection ms = y.getConfigurationSection("muted");
        if (ms != null) {
            for (String k : ms.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    muted.put(id, ms.getString(k + ".reason", ""));
                    mutedNames.put(id, ms.getString(k + ".name", k));
                } catch (IllegalArgumentException ignored) { }
            }
        }
        ConfigurationSection ws = y.getConfigurationSection("warns");
        if (ws != null) {
            for (String k : ws.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    warns.put(id, new ArrayList<>(ws.getStringList(k + ".list")));
                    warnNames.put(id, ws.getString(k + ".name", k));
                } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("global-mute", globalMute);
        y.set("slow-seconds", slowSeconds);
        for (Map.Entry<UUID, String> e : muted.entrySet()) {
            y.set("muted." + e.getKey() + ".reason", e.getValue());
            y.set("muted." + e.getKey() + ".name", mutedNames.getOrDefault(e.getKey(), e.getKey().toString()));
        }
        for (Map.Entry<UUID, List<String>> e : warns.entrySet()) {
            y.set("warns." + e.getKey() + ".list", e.getValue());
            y.set("warns." + e.getKey() + ".name", warnNames.getOrDefault(e.getKey(), e.getKey().toString()));
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Не удалось сохранить data.yml: " + ex.getMessage());
        }
    }

    // =====================================================================
    // commands
    // =====================================================================
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("hosttools.use")) {
            sender.sendMessage(c("&cТолько для операторов."));
            return true;
        }
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "hshout": return cmdShout(sender, args);
            case "hscreen": return cmdScreen(sender, args);
            case "hall": return cmdAll(sender, args);
            case "hbar": return cmdBar(sender, args);
            case "hmute": return cmdMute(sender, args);
            case "hunmute": return cmdUnmute(sender, args);
            case "hmutelist": return cmdMuteList(sender);
            case "hwarn": return cmdWarn(sender, args);
            case "hwarns": return cmdWarns(sender, args);
            case "hunwarn": return cmdUnwarn(sender, args);
            case "hdm": return cmdDm(sender, args);
            case "hsc": return cmdStaffChat(sender, args);
            case "hclearchat": return cmdClearChat(sender);
            case "hslow": return cmdSlow(sender, args);
            case "hfreeze": return cmdFreeze(sender, args);
            case "hcountdown": return cmdCountdown(sender, args);
            case "hheal": return cmdHeal(sender, args);
            case "hfeed": return cmdFeed(sender, args);
            case "hfly": return cmdFly(sender, args);
            case "hvanish": return cmdVanish(sender, args);
            case "hhelp": return cmdHelp(sender);
            default: return false;
        }
    }

    private boolean cmdShout(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&cИспользование: /hshout <сообщение>"); return true; }
        broadcastBanner(joinArgs(a, 0));
        ding(Bukkit.getServer());
        return true;
    }

    private void broadcastBanner(String text) {
        Audience all = Bukkit.getServer();
        all.sendMessage(Component.empty());
        all.sendMessage(c("&6&l━━━━━━━ &e&l📢 ОБЪЯВЛЕНИЕ &6&l━━━━━━━"));
        all.sendMessage(c("&f&l  " + text));
        all.sendMessage(c("&6&l━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
        all.sendMessage(Component.empty());
    }

    private boolean cmdScreen(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&cИспользование: /hscreen <заголовок> [| подзаголовок]"); return true; }
        String full = joinArgs(a, 0);
        String title = full;
        String sub = "";
        int bar = full.indexOf('|');
        if (bar >= 0) {
            title = full.substring(0, bar).trim();
            sub = full.substring(bar + 1).trim();
        }
        screen(Bukkit.getServer(), "&e&l" + title, "&f" + sub);
        return true;
    }

    private boolean cmdAll(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&cИспользование: /hall <сообщение>"); return true; }
        String text = joinArgs(a, 0);
        broadcastBanner(text);
        screen(Bukkit.getServer(), "&e&l" + text, "");
        return true;
    }

    private boolean cmdBar(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&cИспользование: /hbar <сообщение>"); return true; }
        final Component msg = c(joinArgs(a, 0));
        ding(Bukkit.getServer());
        new BukkitRunnable() {
            int n = 0;
            @Override
            public void run() {
                Bukkit.getServer().sendActionBar(msg);
                if (++n >= 5) cancel();
            }
        }.runTaskTimer(this, 0L, 20L);
        return true;
    }

    private boolean cmdMute(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&cИспользование: /hmute <игрок|all> [причина]"); return true; }
        if (a[0].equalsIgnoreCase("all")) {
            globalMute = true;
            saveData();
            Audience all = Bukkit.getServer();
            all.sendMessage(prefix().append(c("&cХост замутил чат.")));
            screen(all, "&c&lЧАТ ЗАМУЧЕН", "&7Сейчас писать может только хост");
            return true;
        }
        OfflinePlayer t = resolve(a[0]);
        if (t == null) { say(s, "&cИгрок не найден: &f" + a[0]); return true; }
        if (s instanceof Player && ((Player) s).getUniqueId().equals(t.getUniqueId())) {
            say(s, "&cНельзя замутить самого себя.");
            return true;
        }
        String reason = a.length > 1 ? joinArgs(a, 1) : "Причина не указана";
        muted.put(t.getUniqueId(), reason);
        mutedNames.put(t.getUniqueId(), t.getName() == null ? a[0] : t.getName());
        saveData();
        Player online = t.getPlayer();
        if (online != null) {
            online.sendMessage(prefix().append(c("&cВы получили мут. &7Причина: &f" + reason)));
            screen(online, "&c&lВЫ В МУТЕ", "&7" + reason);
        }
        notifyStaff("&e" + mutedNames.get(t.getUniqueId()) + " &7получил мут от &e" + s.getName() + " &7(" + reason + ")");
        return true;
    }

    private boolean cmdUnmute(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&cИспользование: /hunmute <игрок|all>"); return true; }
        if (a[0].equalsIgnoreCase("all")) {
            globalMute = false;
            saveData();
            Audience all = Bukkit.getServer();
            all.sendMessage(prefix().append(c("&aХост снял мут с чата.")));
            screen(all, "&a&lМУТ С ЧАТА СНЯТ", "&7Можно снова писать");
            return true;
        }
        OfflinePlayer t = resolve(a[0]);
        if (t == null) { say(s, "&cИгрок не найден: &f" + a[0]); return true; }
        if (muted.remove(t.getUniqueId()) == null) {
            say(s, "&e" + a[0] + " &7не в муте.");
            return true;
        }
        saveData();
        Player online = t.getPlayer();
        if (online != null) {
            online.sendMessage(prefix().append(c("&aС вас снят мут.")));
            screen(online, "&a&lМУТ СНЯТ", "&7Можно снова писать");
        }
        notifyStaff("&e" + a[0] + " &7больше не в муте. Мут снял: &e" + s.getName());
        return true;
    }

    private boolean cmdMuteList(CommandSender s) {
        say(s, "&7Мут чата: " + (globalMute ? "&cВКЛ" : "&aВЫКЛ") + "&7, слоумод: &f" + (slowSeconds > 0 ? slowSeconds + " сек" : "выкл"));
        if (muted.isEmpty()) {
            say(s, "&7Игроков в муте нет.");
        } else {
            for (Map.Entry<UUID, String> e : muted.entrySet()) {
                say(s, "&e" + mutedNames.getOrDefault(e.getKey(), e.getKey().toString()) + " &7- " + e.getValue());
            }
        }
        return true;
    }

    private boolean cmdWarn(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&cИспользование: /hwarn <игрок> <причина>"); return true; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) { say(s, "&cИгрока нет на сервере: &f" + a[0]); return true; }
        String reason = joinArgs(a, 1);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        List<String> list = warns.computeIfAbsent(t.getUniqueId(), k -> new ArrayList<>());
        list.add(stamp + " | " + s.getName() + " | " + reason);
        warnNames.put(t.getUniqueId(), t.getName());
        saveData();
        int count = list.size();
        t.sendMessage(Component.empty());
        t.sendMessage(prefix().append(c("&c&l⚠ ПРЕДУПРЕЖДЕНИЕ #" + count + " &r&7- &f" + reason)));
        t.sendMessage(Component.empty());
        t.showTitle(Title.title(
                c("&c&l⚠ ПРЕДУПРЕЖДЕНИЕ ⚠"),
                c("&f" + reason),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofMillis(800))));
        t.playSound(snd("warn", 1.0f));
        notifyStaff("&e" + t.getName() + " &7получил предупреждение от &e" + s.getName() + " &7(#" + count + "): &f" + reason);
        int kickAt = getConfig().getInt("auto-kick-warnings", 0);
        if (kickAt > 0 && count >= kickAt) {
            t.kick(c("&cВы получили " + count + " " + pl(count, "предупреждение", "предупреждения", "предупреждений") + ".\n&7Последнее: &f" + reason));
            notifyStaff("&e" + t.getName() + " &7автоматически кикнут (" + count + " " + pl(count, "предупреждение", "предупреждения", "предупреждений") + ")");
        }
        return true;
    }

    private boolean cmdWarns(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&cИспользование: /hwarns <игрок>"); return true; }
        OfflinePlayer t = resolve(a[0]);
        if (t == null) { say(s, "&cИгрок не найден: &f" + a[0]); return true; }
        List<String> list = warns.get(t.getUniqueId());
        if (list == null || list.isEmpty()) {
            say(s, "&7У &e" + a[0] + " &7нет предупреждений.");
            return true;
        }
        say(s, "&7У &e" + a[0] + " &7— &c" + list.size() + " &7" + pl(list.size(), "предупреждение", "предупреждения", "предупреждений") + ":");
        int i = 1;
        for (String w : list) {
            s.sendMessage(c("  &c" + i++ + ". &7" + w));
        }
        return true;
    }

    private boolean cmdUnwarn(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&cИспользование: /hunwarn <игрок>"); return true; }
        OfflinePlayer t = resolve(a[0]);
        if (t == null) { say(s, "&cИгрок не найден: &f" + a[0]); return true; }
        if (warns.remove(t.getUniqueId()) == null) {
            say(s, "&7У &e" + a[0] + " &7нет предупреждений.");
            return true;
        }
        saveData();
        say(s, "&aВсе предупреждения сняты с игрока &e" + a[0]);
        return true;
    }

    private boolean cmdDm(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&cИспользование: /hdm <игрок> <сообщение>"); return true; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) { say(s, "&cИгрока нет на сервере: &f" + a[0]); return true; }
        String text = joinArgs(a, 1);
        t.sendMessage(c("&c&l[UHC → вам] &f" + text));
        t.sendActionBar(c("&c&l[UHC] &f" + text));
        ding(t);
        say(s, "&7Отправлено игроку &e" + t.getName() + "&7: &f" + text);
        return true;
    }

    private boolean cmdStaffChat(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&cИспользование: /hsc <сообщение>"); return true; }
        Component msg = c("&8[&cСтафф&8] &e" + s.getName() + "&7: &f" + joinArgs(a, 0));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("hosttools.use")) {
                p.sendMessage(msg);
                p.playSound(snd("tick", 1.6f));
            }
        }
        Bukkit.getConsoleSender().sendMessage(msg);
        return true;
    }

    private boolean cmdClearChat(CommandSender s) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (bypass(p)) continue;
            for (int i = 0; i < 120; i++) p.sendMessage(Component.empty());
        }
        Bukkit.getServer().sendMessage(prefix().append(c("&7Хост очистил чат.")));
        return true;
    }

    private boolean cmdSlow(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&cИспользование: /hslow <секунды|off>  (сейчас: " + (slowSeconds > 0 ? slowSeconds + " сек" : "выкл") + ")"); return true; }
        if (a[0].equalsIgnoreCase("off")) {
            slowSeconds = 0;
            saveData();
            Bukkit.getServer().sendMessage(prefix().append(c("&aСлоумод выключен.")));
            return true;
        }
        int n;
        try {
            n = Integer.parseInt(a[0]);
        } catch (NumberFormatException e) {
            say(s, "&cЭто не число: &f" + a[0]);
            return true;
        }
        if (n < 1 || n > 3600) { say(s, "&cУкажите от 1 до 3600 секунд или off."); return true; }
        slowSeconds = n;
        saveData();
        Bukkit.getServer().sendMessage(prefix().append(c("&eСлоумод: писать в чат можно раз в &f" + n + " " + pl(n, "секунду", "секунды", "секунд") + "&e.")));
        return true;
    }

    private boolean cmdFreeze(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&cИспользование: /hfreeze <игрок|all|off>"); return true; }
        if (a[0].equalsIgnoreCase("off")) {
            for (UUID id : new ArrayList<>(frozen)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) screen(p, "&a&lВЫ РАЗМОРОЖЕНЫ", "&7Можно снова двигаться");
            }
            frozen.clear();
            say(s, "&aВсе игроки разморожены.");
            return true;
        }
        if (a[0].equalsIgnoreCase("all")) {
            int n = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (bypass(p)) continue;
                frozen.add(p.getUniqueId());
                screen(p, "&b&lВЫ ЗАМОРОЖЕНЫ", "&7Ожидайте указаний хоста");
                n++;
            }
            say(s, "&bЗаморожено игроков: &f" + n + "&b. Чтобы разморозить всех, используйте /hfreeze off.");
            return true;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) { say(s, "&cИгрока нет на сервере: &f" + a[0]); return true; }
        if (frozen.remove(t.getUniqueId())) {
            screen(t, "&a&lВЫ РАЗМОРОЖЕНЫ", "&7Можно снова двигаться");
            say(s, "&aИгрок разморожен: &e" + t.getName());
        } else {
            frozen.add(t.getUniqueId());
            screen(t, "&b&lВЫ ЗАМОРОЖЕНЫ", "&7Ожидайте указаний хоста");
            say(s, "&bИгрок заморожен: &e" + t.getName());
        }
        return true;
    }

    private boolean cmdCountdown(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&cИспользование: /hcountdown <секунды|cancel> [текст в конце]"); return true; }
        if (a[0].equalsIgnoreCase("cancel")) {
            if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
            say(s, "&7Отсчёт отменён.");
            return true;
        }
        int sec;
        try {
            sec = Integer.parseInt(a[0]);
        } catch (NumberFormatException e) {
            say(s, "&cЭто не число: &f" + a[0]);
            return true;
        }
        if (sec < 1 || sec > 600) { say(s, "&cУкажите от 1 до 600 секунд."); return true; }
        final int total = sec;
        final String endText = a.length > 1 ? joinArgs(a, 1) : "СТАРТ!";
        if (countdownTask != null) countdownTask.cancel();
        countdownTask = new BukkitRunnable() {
            int left = total;
            @Override
            public void run() {
                Audience all = Bukkit.getServer();
                if (left <= 0) {
                    all.showTitle(Title.title(c("&a&l" + endText), Component.empty(),
                            Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500))));
                    all.playSound(snd("go", 1.0f));
                    countdownTask = null;
                    cancel();
                    return;
                }
                String color = left <= 3 ? "&c" : (left <= 5 ? "&e" : "&a");
                all.showTitle(Title.title(c(color + "&l" + left), Component.empty(),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)));
                all.playSound(snd("tick", left <= 3 ? 1.5f : 1.0f));
                left--;
            }
        }.runTaskTimer(this, 0L, 20L);
        return true;
    }

    @SuppressWarnings("deprecation")
    private void heal(Player p) {
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
    }

    private boolean cmdHeal(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&cИспользование: /hheal <игрок|all>"); return true; }
        List<Player> list = targets(s, a[0]);
        for (Player p : list) {
            heal(p);
            p.sendActionBar(c("&aХост вас вылечил."));
        }
        if (!list.isEmpty()) say(s, "&aВылечено игроков: &f" + list.size() + "&a.");
        return true;
    }

    private boolean cmdFeed(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&cИспользование: /hfeed <игрок|all>"); return true; }
        List<Player> list = targets(s, a[0]);
        for (Player p : list) {
            p.setFoodLevel(20);
            p.setSaturation(20f);
            p.sendActionBar(c("&aХост вас накормил."));
        }
        if (!list.isEmpty()) say(s, "&aНакормлено игроков: &f" + list.size() + "&a.");
        return true;
    }

    private Player selfOrTarget(CommandSender s, String[] a) {
        if (a.length > 0) {
            Player t = Bukkit.getPlayerExact(a[0]);
            if (t == null) say(s, "&cИгрока нет на сервере: &f" + a[0]);
            return t;
        }
        if (s instanceof Player) return (Player) s;
        say(s, "&cКонсоль должна указать игрока.");
        return null;
    }

    private boolean cmdFly(CommandSender s, String[] a) {
        Player t = selfOrTarget(s, a);
        if (t == null) return true;
        boolean on = !t.getAllowFlight();
        t.setAllowFlight(on);
        if (on) t.setFlying(true);
        say(s, "&7Режим полёта для &e" + t.getName() + "&7: " + (on ? "&aВКЛ" : "&cВЫКЛ"));
        return true;
    }

    private boolean cmdVanish(CommandSender s, String[] a) {
        Player t = selfOrTarget(s, a);
        if (t == null) return true;
        if (vanished.remove(t.getUniqueId())) {
            for (Player o : Bukkit.getOnlinePlayers()) o.showPlayer(this, t);
            say(s, "&7Ваниш для &e" + t.getName() + "&7: &cВЫКЛ");
        } else {
            vanished.add(t.getUniqueId());
            applyVanish(t);
            say(s, "&7Ваниш для &e" + t.getName() + "&7: &aВКЛ &7(скрыт от всех, кроме операторов)");
        }
        return true;
    }

    private void applyVanish(Player t) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(t)) continue;
            if (bypass(o)) o.showPlayer(this, t);
            else o.hidePlayer(this, t);
        }
    }

    private boolean cmdHelp(CommandSender s) {
        say(s, "&e&lHostTools &7- только для операторов");
        String[][] rows = {
                {"/hshout <сообщение>", "громкое объявление в чат + звук"},
                {"/hscreen <заголовок> [| подзаголовок]", "крупный текст по центру экрана + звук"},
                {"/hall <сообщение>", "объявление в чат И текст на экран"},
                {"/hbar <сообщение>", "текст над хотбаром на 5 сек"},
                {"/hmute <игрок|all> [причина]", "замутить игрока / весь чат"},
                {"/hunmute <игрок|all>", "снять мут"},
                {"/hmutelist", "кто в муте"},
                {"/hwarn <игрок> <причина>", "предупреждение на экране + в чате + звук, с записью"},
                {"/hwarns <игрок>", "посмотреть предупреждения"},
                {"/hunwarn <игрок>", "снять предупреждения"},
                {"/hdm <игрок> <сообщение>", "личное сообщение от хоста"},
                {"/hsc <сообщение>", "чат только для операторов"},
                {"/hclearchat", "очистить чат для неоператоров"},
                {"/hslow <секунды|off>", "слоумод в чате"},
                {"/hfreeze <игрок|all|off>", "заморозить игроков"},
                {"/hcountdown <секунды|cancel> [текст]", "крупный отсчёт на экране"},
                {"/hheal, /hfeed <игрок|all>", "вылечить / накормить"},
                {"/hfly [игрок]", "вкл/выкл полёт"},
                {"/hvanish [игрок]", "скрыться от неоператоров"},
        };
        for (String[] r : rows) s.sendMessage(c("  &6" + r[0] + " &8- &7" + r[1]));
        return true;
    }

    // =====================================================================
    // tab completion
    // =====================================================================
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("hosttools.use")) return out;
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (args.length != 1) return out;
        String cur = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        switch (name) {
            case "hmute": case "hunmute": case "hheal": case "hfeed":
                options.add("all");
                addNames(options);
                break;
            case "hfreeze":
                options.add("all");
                options.add("off");
                addNames(options);
                break;
            case "hwarn": case "hwarns": case "hunwarn": case "hdm": case "hfly": case "hvanish":
                addNames(options);
                break;
            case "hslow":
                options.add("off"); options.add("5"); options.add("10"); options.add("30");
                break;
            case "hcountdown":
                options.add("cancel"); options.add("3"); options.add("5"); options.add("10"); options.add("30");
                break;
            default:
                break;
        }
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(cur)) out.add(o);
        return out;
    }

    private void addNames(List<String> options) {
        for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
    }

    // =====================================================================
    // listeners
    // =====================================================================
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (muted.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            p.sendMessage(prefix().append(c("&cВы в муте. &7Причина: &f" + muted.get(p.getUniqueId()))));
            return;
        }
        if (bypass(p)) return;
        if (globalMute) {
            e.setCancelled(true);
            p.sendMessage(prefix().append(c("&cЧат сейчас в муте.")));
            return;
        }
        if (slowSeconds > 0) {
            long now = System.currentTimeMillis();
            Long last = lastChat.get(p.getUniqueId());
            if (last != null && now - last < slowSeconds * 1000L) {
                long wait = (slowSeconds * 1000L - (now - last) + 999) / 1000;
                e.setCancelled(true);
                p.sendMessage(prefix().append(c("&eСлоумод: подождите &f" + wait + " " + pl((int) wait, "секунду", "секунды", "секунд") + " &eперед следующим сообщением.")));
                return;
            }
            lastChat.put(p.getUniqueId(), now);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        boolean blocked = muted.containsKey(p.getUniqueId()) || (globalMute && !bypass(p));
        if (!blocked) return;
        String msg = e.getMessage();
        if (msg.startsWith("/")) msg = msg.substring(1);
        String first = msg.split(" ")[0].toLowerCase(Locale.ROOT);
        if (first.startsWith("minecraft:")) first = first.substring("minecraft:".length());
        for (String b : getConfig().getStringList("muted-blocked-commands")) {
            if (first.equalsIgnoreCase(b)) {
                e.setCancelled(true);
                p.sendMessage(prefix().append(c("&cНельзя использовать эту команду, пока вы в муте.")));
                return;
            }
        }
    }

    private boolean isFrozen(Player p) {
        return frozen.contains(p.getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!isFrozen(e.getPlayer())) return;
        if (e.getTo() == null) return;
        if (e.getFrom().getX() != e.getTo().getX()
                || e.getFrom().getY() != e.getTo().getY()
                || e.getFrom().getZ() != e.getTo().getZ()) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player && isFrozen((Player) e.getDamager())) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player joined = e.getPlayer();
        // hide vanished staff from the player who just joined (unless he is staff)
        if (!bypass(joined)) {
            for (UUID id : vanished) {
                Player v = Bukkit.getPlayer(id);
                if (v != null) joined.hidePlayer(this, v);
            }
        }
        // a muted player is reminded
        if (muted.containsKey(joined.getUniqueId())) {
            joined.sendMessage(prefix().append(c("&cВы в муте. &7Причина: &f" + muted.get(joined.getUniqueId()))));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastChat.remove(id);
        frozen.remove(id);
        vanished.remove(id);
    }
}
