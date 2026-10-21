package com.codezyy.spectatorcamera;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;

public class SpectatorCameraPlugin extends JavaPlugin implements Listener, TabExecutor {

    private Camera camera;
    private WebPreview web;
    private RelayPusher relay;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        new java.io.File(getDataFolder(), "overlay").mkdirs();
        camera = new Camera(this);
        camera.start();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("spectatorcamera").setExecutor(this);
        getCommand("spectatorcamera").setTabCompleter(this);
        startWeb();
        startRelay();
        // enroll camera accounts that are already online (e.g. after /reload)
        for (Player p : Bukkit.getOnlinePlayers()) enroll(p);
    }

    @Override
    public void onDisable() {
        if (web != null) web.stop();
        if (relay != null) relay.stop();
        if (camera != null) camera.shutdown();
    }

    private void startWeb() {
        if (web != null) {
            web.stop();
            web = null;
        }
        if (!getConfig().getBoolean("web.enabled", true)) return;
        try {
            web = new WebPreview(this, camera);
            web.start();
            getLogger().info("Live preview on port " + getConfig().getInt("web.port", 8765));
            getLogger().info("Watch link: " + watchLink(false));
            getLogger().info("Clean link (OBS / streamer): " + watchLink(true));
        } catch (Exception e) {
            getLogger().warning("Could not start web preview: " + e.getMessage());
        }
    }

    private void startRelay() {
        if (relay != null) {
            relay.stop();
            relay = null;
        }
        relay = new RelayPusher(this, camera);
        relay.start();
        if (getConfig().getBoolean("relay.enabled", false)) {
            getLogger().info("Relay watch link: " + watchLink(false));
        }
    }

    private boolean relayOn() {
        return getConfig().getBoolean("relay.enabled", false) && !getConfig().getString("relay.url", "").isBlank();
    }

    // ---------- camera accounts (bots) ----------

    private static String norm(String name) {
        String n = name.toLowerCase();
        while (n.startsWith(".")) n = n.substring(1); // Floodgate prefix
        return n.replace(' ', '_');
    }

    public boolean isCameraAccount(Player p) {
        String me = norm(p.getName());
        for (String s : getConfig().getStringList("camera-accounts")) {
            if (norm(s).equals(me)) return true;
        }
        return false;
    }

    private void enroll(Player p) {
        if (!getConfig().getBoolean("auto-enroll", true)) return;
        if (isCameraAccount(p)) {
            camera.join(p);
            if (getConfig().getBoolean("hide-camera-accounts", true)) {
                for (Player other : Bukkit.getOnlinePlayers()) {
                    if (!other.equals(p)) other.hidePlayer(this, p);
                }
            }
        } else if (getConfig().getBoolean("hide-camera-accounts", true)) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(p) && isCameraAccount(other)) p.hidePlayer(this, other);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) enroll(p);
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // restore gamemode/location so the player isn't stuck in spectator next login
        camera.leave(e.getPlayer());
    }

    // ---------- commands (work from console too) ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        boolean admin = sender.hasPermission("spectatorcamera.admin"); // console has all permissions

        switch (sub) {
            case "join", "leave" -> {
                Player p = null;
                if (args.length > 1) {
                    if (!admin) return msg(sender, "No permission.");
                    p = Bukkit.getPlayerExact(args[1]);
                    if (p == null) return msg(sender, "Player not online.");
                } else if (sender instanceof Player self) {
                    if (!self.hasPermission("spectatorcamera.use")) return msg(sender, "No permission.");
                    p = self;
                }
                if (p == null) return msg(sender, "From console: /cam " + sub + " <player>");
                if (sub.equals("join"))
                    return msg(sender, camera.join(p) ? p.getName() + " is now a camera viewer." : "Already a viewer.");
                return msg(sender, camera.leave(p) ? p.getName() + " left the camera." : "Not a viewer.");
            }
            case "status" -> {
                return msg(sender, camera.status());
            }
            case "link" -> {
                if (!sender.hasPermission("spectatorcamera.link")) return msg(sender, "No permission.");
                sendLinks(sender);
                return true;
            }
        }

        if (!admin) return msg(sender, "No permission.");

        switch (sub) {
            case "follow" -> {
                if (args.length < 2) return msg(sender, "Usage: /cam follow <player>");
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) return msg(sender, "Player not online.");
                camera.setManualTarget(t);
                if (camera.getMode() == Camera.Mode.PATROL) camera.setMode(Camera.Mode.AUTO);
                return msg(sender, "Following " + t.getName() + ".");
            }
            case "mode" -> {
                if (args.length < 2) return msg(sender, "Modes: auto, follow, orbit, cinematic, patrol");
                try {
                    camera.setMode(Camera.Mode.valueOf(args[1].toUpperCase()));
                } catch (IllegalArgumentException ex) {
                    return msg(sender, "Modes: auto, follow, orbit, cinematic, patrol");
                }
                return msg(sender, "Mode set to " + camera.getMode() + ".");
            }
            case "next" -> {
                camera.nextTarget();
                return msg(sender, "Switching target.");
            }
            case "addpoint" -> {
                if (sender instanceof Player p && args.length == 1) {
                    camera.addPoint(p.getLocation());
                    return msg(sender, "Patrol point added.");
                }
                if (args.length != 5) return msg(sender, "Console: /cam addpoint <world> <x> <y> <z>");
                World w = Bukkit.getWorld(args[1]);
                if (w == null) return msg(sender, "Unknown world.");
                try {
                    camera.addPoint(new Location(w, Double.parseDouble(args[2]),
                            Double.parseDouble(args[3]), Double.parseDouble(args[4])));
                } catch (NumberFormatException ex) {
                    return msg(sender, "x y z must be numbers.");
                }
                return msg(sender, "Patrol point added.");
            }
            case "clearpoints" -> {
                camera.clearPoints();
                return msg(sender, "Patrol points cleared.");
            }
            case "account" -> {
                return accountCmd(sender, args);
            }
            case "reload" -> {
                reloadConfig();
                startWeb();
                startRelay();
                for (Player p : Bukkit.getOnlinePlayers()) enroll(p);
                return msg(sender, "Config reloaded.");
            }
            default -> {
                return msg(sender, "/cam <join|leave|follow|mode|next|addpoint|clearpoints|account|status|reload>");
            }
        }
    }

    private boolean accountCmd(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase() : "list";
        List<String> list = new ArrayList<>(getConfig().getStringList("camera-accounts"));
        switch (action) {
            case "add" -> {
                if (args.length < 3) return msg(sender, "Usage: /cam account add <name>");
                if (list.stream().noneMatch(s -> norm(s).equals(norm(args[2])))) list.add(args[2]);
                getConfig().set("camera-accounts", list);
                saveConfig();
                for (Player p : Bukkit.getOnlinePlayers()) enroll(p);
                return msg(sender, "Camera accounts: " + list);
            }
            case "remove" -> {
                if (args.length < 3) return msg(sender, "Usage: /cam account remove <name>");
                list.removeIf(s -> norm(s).equals(norm(args[2])));
                getConfig().set("camera-accounts", list);
                saveConfig();
                return msg(sender, "Camera accounts: " + list + " (use /cam leave <name> if it is online)");
            }
            default -> {
                return msg(sender, "Camera accounts: " + list);
            }
        }
    }

    // ---------- browser watch link ----------

    public String baseUrl() {
        String pub = getConfig().getString("web.public-url", "");
        if (pub != null && !pub.isBlank()) return pub.replaceAll("/+$", "");
        String ip = Bukkit.getIp();
        if (ip == null || ip.isBlank() || ip.equals("0.0.0.0")) ip = "YOUR_SERVER_IP";
        return "http://" + ip + ":" + getConfig().getInt("web.port", 8765);
    }

    public String watchLink(boolean clean) {
        boolean viaRelay = relayOn();
        String base = viaRelay ? getConfig().getString("relay.url", "").trim().replaceAll("/+$", "") : baseUrl();
        String token = getConfig().getString(viaRelay ? "relay.view-token" : "web.token", "");
        boolean hasToken = token != null && !token.isEmpty();
        StringBuilder sb = new StringBuilder(base).append("/");
        if (hasToken) sb.append("?token=").append(token);
        if (clean) sb.append(hasToken ? "&" : "?").append("clean=1");
        return sb.toString();
    }

    private void sendLinks(CommandSender sender) {
        if (!getConfig().getBoolean("web.enabled", true) && !relayOn()) {
            msg(sender, "The web preview is disabled in config.yml.");
            return;
        }
        String watch = watchLink(false);
        sender.sendMessage(Component.text("[SpectatorCamera] Watch live: ", NamedTextColor.GRAY)
                .append(Component.text(watch, NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(watch))));
        if (sender.hasPermission("spectatorcamera.admin")) {
            sender.sendMessage(Component.text("[SpectatorCamera] Clean page (OBS / streamer): " + watchLink(true),
                    NamedTextColor.GRAY));
        }
    }

    private boolean msg(CommandSender s, String m) {
        s.sendMessage("[SpectatorCamera] " + m);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("join", "leave", "status", "follow", "mode", "next", "addpoint",
                    "clearpoints", "account", "link", "reload"))
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            for (Camera.Mode m : Camera.Mode.values())
                if (m.name().toLowerCase().startsWith(args[1].toLowerCase())) out.add(m.name().toLowerCase());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("account")) {
            for (String s : List.of("add", "remove", "list"))
                if (s.startsWith(args[1].toLowerCase())) out.add(s);
        } else if (args.length == 2 && List.of("follow", "join", "leave").contains(args[0].toLowerCase())) {
            for (Player p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
        }
        return out;
    }
}
