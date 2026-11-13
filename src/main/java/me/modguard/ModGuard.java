package me.modguard;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ModGuard extends JavaPlugin implements Listener {

    private static class Session {
        final List<Item> pending;
        final Set<String> found = new LinkedHashSet<>();
        CommandSender reporter;
        final Location loc;
        final Vector3i pos;
        int index = 0;
        int attempts = 0;
        List<Item> batch = List.of();
        volatile boolean awaiting = false;
        BukkitTask timeout;

        Session(List<Item> pending, Location loc) {
            this.pending = pending;
            this.loc = loc;
            this.pos = new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }
    }

    private record Item(String name, String key, boolean translate) {}

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private boolean debug;
    private String detectMode = "both";
    private PacketListenerAbstract packetListener;

    private String kickMessage;
    private int checkDelay;
    private boolean skipBedrock;
    private boolean brandEnabled;
    private List<String> blockedBrands;
    private boolean keybindEnabled;
    private boolean kickNoResponse;
    private int retries;
    private boolean channelEnabled;
    private List<String> blockedChannels = new ArrayList<>();
    private int responseTimeout;
    private final Map<String, String> blockedKeybinds = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);

        packetListener = new PacketListenerAbstract(PacketListenerPriority.HIGH) {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;

                UUID id = null;
                if (event.getUser() != null) id = event.getUser().getUUID();
                if (id == null && event.getPlayer() instanceof Player bp) id = bp.getUniqueId();

                Session s = id == null ? null : sessions.get(id);
                if (debug) {
                    getLogger().info("[debug] UPDATE_SIGN received. uuid=" + id
                            + " hasSession=" + (s != null)
                            + " awaiting=" + (s != null && s.awaiting));
                }
                if (id == null || s == null || !s.awaiting) return;

                WrapperPlayClientUpdateSign w = new WrapperPlayClientUpdateSign(event);
                if (!w.getBlockPosition().equals(s.pos)) {
                    if (debug) getLogger().info("[debug] UPDATE_SIGN position mismatch: got "
                            + w.getBlockPosition() + " expected " + s.pos);
                    return;
                }

                event.setCancelled(true);
                String[] lines = w.getTextLines();
                final UUID fid = id;
                getServer().getScheduler().runTask(ModGuard.this, () -> handleResponse(fid, lines));
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);
        getLogger().info("ModGuard enabled. PacketEvents listener registered.");
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
        sessions.clear();
    }

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();
        kickMessage = c.getString("kick-message", "&cRestricted mods: {mods}");
        checkDelay = Math.max(1, c.getInt("check-delay-ticks", 15));
        skipBedrock = c.getBoolean("skip-bedrock", true);
        brandEnabled = c.getBoolean("brand-check.enabled", true);
        blockedBrands = new ArrayList<>();
        for (String b : c.getStringList("brand-check.blocked-brands")) blockedBrands.add(b.toLowerCase());
        keybindEnabled = c.getBoolean("keybind-check.enabled", true);
        kickNoResponse = c.getBoolean("keybind-check.kick-on-no-response", false);
        retries = Math.max(0, c.getInt("keybind-check.retries", 1));
        channelEnabled = c.getBoolean("channel-check.enabled", true);
        blockedChannels = new ArrayList<>();
        for (String ch : c.getStringList("channel-check.blocked-channels")) blockedChannels.add(ch.toLowerCase());
        responseTimeout = Math.max(20, c.getInt("keybind-check.response-timeout-ticks", 80));
        debug = c.getBoolean("debug", false);
        detectMode = c.getString("keybind-check.detect-mode", "both").toLowerCase();
        blockedKeybinds.clear();
        var sec = c.getConfigurationSection("keybind-check.blocked-keybinds");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                String key = sec.getString(name);
                if (key != null && !key.isBlank()) blockedKeybinds.put(name, key.trim());
            }
        }
        getLogger().info("Loaded " + blockedKeybinds.size() + " blocked mod keys, mode=" + detectMode);
    }

    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (debug) getLogger().info(p.getName() + " joined, mod check scheduled in " + checkDelay + " ticks");
        getServer().getScheduler().runTaskLater(this, () -> runChecks(p), checkDelay);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        endSession(e.getPlayer().getUniqueId(), e.getPlayer());
    }

    private boolean exempt(Player p) {
        if (p.hasPermission("modguard.bypass")) return true;
        return skipBedrock && p.getUniqueId().getMostSignificantBits() == 0L;
    }

    private void runChecks(Player p) { runChecks(p, null); }

    private void runChecks(Player p, CommandSender reporter) {
        try {
            runChecksInner(p, reporter);
        } catch (Throwable t) {
            getLogger().severe("Mod check crashed for " + p.getName() + ": " + t);
            t.printStackTrace();
            if (reporter != null) reporter.sendMessage("Check crashed: " + t);
        }
    }

    private void runChecksInner(Player p, CommandSender reporter) {
        if (!p.isOnline()) return;
        if (exempt(p)) {
            String msg = p.getName() + " is exempt from checks (op / modguard.bypass permission / bedrock).";
            if (debug) getLogger().info(msg);
            if (reporter != null) reporter.sendMessage(msg);
            return;
        }

        // Layer 1: brand
        if (brandEnabled) {
            String brand = p.getClientBrandName();
            if (brand != null) {
                String lower = brand.toLowerCase();
                for (String bad : blockedBrands) {
                    if (lower.contains(bad)) {
                        kick(p, List.of(brand));
                        return;
                    }
                }
            }
        }

        // Layer 1b: plugin channels the client registered
        if (channelEnabled) {
            List<String> hits = new ArrayList<>();
            for (String ch : p.getListeningPluginChannels()) {
                String lower = ch.toLowerCase();
                for (String bad : blockedChannels) {
                    if (lower.contains(bad)) { hits.add(ch); break; }
                }
            }
            if (!hits.isEmpty()) {
                kick(p, hits);
                return;
            }
        }
        if (debug || reporter != null) {
            String info = p.getName() + " brand=" + p.getClientBrandName()
                    + " channels=" + p.getListeningPluginChannels();
            if (debug) getLogger().info(info);
            if (reporter != null) reporter.sendMessage(info);
        }

        // Layer 2: keybind detection
        if (keybindEnabled && !blockedKeybinds.isEmpty()) {
            startKeybindCheck(p, reporter);
        }
    }

    // ------------------------------------------------------------------
    // Keybind check
    // ------------------------------------------------------------------

    private void startKeybindCheck(Player p, CommandSender reporter) {
        if (sessions.containsKey(p.getUniqueId())) return;
        Location loc = p.getLocation();
        loc.setY(p.getWorld().getMinHeight());

        List<Item> items = new ArrayList<>();
        for (Map.Entry<String, String> e : blockedKeybinds.entrySet()) {
            if (!detectMode.equals("translation")) items.add(new Item(e.getKey(), e.getValue(), false));
            if (!detectMode.equals("keybind")) items.add(new Item(e.getKey(), e.getValue(), true));
        }
        Session s = new Session(items, loc);
        s.reporter = reporter;
        sessions.put(p.getUniqueId(), s);
        if (debug) getLogger().info("Starting mod check for " + p.getName() + " (" + items.size() + " tests)");
        sendBatch(p, s);
    }

    private void sendBatch(Player p, Session s) {
        int end = Math.min(s.index + 4, s.pending.size());
        s.batch = new ArrayList<>(s.pending.subList(s.index, end));

        BlockData data = Material.OAK_SIGN.createBlockData();
        Sign sign = (Sign) data.createBlockState();
        SignSide side = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) {
            if (i < s.batch.size()) {
                Item it = s.batch.get(i);
                side.line(i, it.translate() ? Component.translatable(it.key()) : Component.keybind(it.key()));
            } else side.line(i, Component.empty());
        }

        try {
            p.sendBlockChange(s.loc, data);
            p.sendBlockUpdate(s.loc, sign);
        } catch (Throwable t) {
            getLogger().severe("Failed to send test sign to " + p.getName() + ": " + t);
            if (s.reporter != null) s.reporter.sendMessage("Failed to send test sign: " + t);
            endSession(p.getUniqueId(), p);
            return;
        }

        // open the editor, then close it so the client sends back the resolved text
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline() || sessions.get(p.getUniqueId()) != s) return;
            s.awaiting = true;
            if (debug) getLogger().info("[debug] Opening sign editor for " + p.getName() + " at " + s.pos);
            PacketEvents.getAPI().getPlayerManager().sendPacket(p,
                    new WrapperPlayServerOpenSignEditor(s.pos, true));
            getServer().getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline() || sessions.get(p.getUniqueId()) != s) return;
                if (debug) getLogger().info("[debug] Closing sign editor for " + p.getName());
                PacketEvents.getAPI().getPlayerManager().sendPacket(p,
                        new WrapperPlayServerCloseWindow(0));
            }, 2L);
        }, 2L);

        s.timeout = getServer().getScheduler().runTaskLater(this, () -> {
            if (sessions.get(p.getUniqueId()) != s || !s.awaiting) return;
            s.awaiting = false;
            if (s.attempts < retries) {
                s.attempts++;
                if (debug) getLogger().info("[debug] No answer from " + p.getName() + ", retry " + s.attempts + "/" + retries);
                sendBatch(p, s);
                return;
            }
            getLogger().warning(p.getName() + " did not answer the mod check (no sign update received).");
            if (s.reporter != null) s.reporter.sendMessage(p.getName() + " did not answer the check (client ignored it).");
            endSession(p.getUniqueId(), p);
            if (kickNoResponse && p.isOnline()) kick(p, List.of("unverifiable client (mod check blocked)"));
        }, responseTimeout);
    }

    private void handleResponse(UUID id, String[] lines) {
        Session s = sessions.get(id);
        Player p = getServer().getPlayer(id);
        if (s == null || p == null || !s.awaiting) return;
        s.awaiting = false;
        s.attempts = 0;
        if (s.timeout != null) s.timeout.cancel();

        for (int i = 0; i < s.batch.size() && i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            Item it = s.batch.get(i);
            // Not installed -> client echoes the key id. Installed -> a real label.
            boolean hit = !line.isEmpty() && !line.equals(it.key());
            if (hit) s.found.add(it.name().replaceAll("\\s*\\(.*\\)$", ""));
            String msg = (it.translate() ? "[translate] " : "[keybind] ") + it.key() + " -> \"" + line + "\"" + (hit ? "  ** DETECTED **" : "");
            if (debug) getLogger().info(p.getName() + " " + msg);
            if (s.reporter != null) s.reporter.sendMessage(msg);
        }

        s.index += s.batch.size();
        if (s.index < s.pending.size()) {
            sendBatch(p, s);
            return;
        }

        List<String> found = new ArrayList<>(s.found);
        CommandSender rep = s.reporter;
        endSession(id, p);
        if (rep != null) rep.sendMessage(found.isEmpty() ? "Check finished: nothing detected." : "Check finished: detected " + found);
        if (!found.isEmpty()) kick(p, found);
    }

    /** Remove the session and restore the real block at the fake sign location. */
    private void endSession(UUID id, Player p) {
        Session s = sessions.remove(id);
        if (s == null) return;
        if (s.timeout != null) s.timeout.cancel();
        if (p != null && p.isOnline()) {
            p.sendBlockChange(s.loc, s.loc.getBlock().getBlockData());
        }
    }

    private void kick(Player p, List<String> mods) {
        String msg = kickMessage.replace("{mods}", String.join(", ", mods));
        Component c = LegacyComponentSerializer.legacyAmpersand().deserialize(msg);
        getLogger().info(p.getName() + " kicked for restricted mods: " + String.join(", ", mods));
        p.kick(c);
    }

    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            sender.sendMessage("ModGuard config reloaded.");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
            Player t = getServer().getPlayerExact(args[1]);
            if (t == null) {
                sender.sendMessage("Player not found.");
                return true;
            }
            sender.sendMessage("Brand of " + t.getName() + ": " + t.getClientBrandName());
            sender.sendMessage("Check started...");
            runChecks(t, sender);
            return true;
        }
        sender.sendMessage("Usage: /modguard reload | /modguard check <player>");
        return true;
    }
}
