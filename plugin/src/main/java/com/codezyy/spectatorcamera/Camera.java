package com.codezyy.spectatorcamera;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class Camera {

    public enum Mode { AUTO, FOLLOW, ORBIT, CINEMATIC, PATROL }

    // {angle from player's facing (0=front,180=behind), distance, height, spin per tick}
    private static final double[][] SHOTS = {
            {180, 6, 2, 0},
            {0, 5, 1.5, 0},
            {90, 7, 2, 0},
            {180, 4, 11, 0},
            {0, 9, 3, 1.2}
    };

    private final SpectatorCameraPlugin plugin;
    private final Set<UUID> viewers = new LinkedHashSet<>();
    private final Map<UUID, GameMode> oldMode = new HashMap<>();
    private final Map<UUID, Location> oldLoc = new HashMap<>();
    private final Random rnd = new Random();

    private Mode mode = Mode.AUTO;
    private UUID manualTarget, currentTarget;
    private Location cam;
    private float camYaw, camPitch;
    private double orbit, spin;
    private long switchAt, shotAt;
    private int shot, patrolIdx, tickCount;
    private String effLabel = "PATROL";
    private String targetName = "-";
    private BukkitTask task;
    private volatile String stateJson = "{}";

    public Camera(SpectatorCameraPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        try {
            mode = Mode.valueOf(plugin.getConfig().getString("default-mode", "AUTO").toUpperCase());
        } catch (IllegalArgumentException ignored) {
            mode = Mode.AUTO;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (UUID id : new ArrayList<>(viewers)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) leave(p);
        }
    }

    // ---------- viewers ----------

    public boolean join(Player p) {
        if (viewers.contains(p.getUniqueId())) return false;
        oldMode.put(p.getUniqueId(), p.getGameMode());
        oldLoc.put(p.getUniqueId(), p.getLocation());
        viewers.add(p.getUniqueId());
        p.setGameMode(GameMode.SPECTATOR);
        return true;
    }

    public boolean leave(Player p) {
        if (!viewers.remove(p.getUniqueId())) return false;
        GameMode gm = oldMode.remove(p.getUniqueId());
        Location loc = oldLoc.remove(p.getUniqueId());
        if (loc != null) p.teleport(loc);
        p.setGameMode(gm != null ? gm : GameMode.SURVIVAL);
        return true;
    }

    public boolean isViewer(Player p) {
        return viewers.contains(p.getUniqueId());
    }

    // ---------- controls ----------

    public void setMode(Mode m) {
        mode = m;
        if (m != Mode.AUTO && m != Mode.PATROL) {
            // keep current target
        }
    }

    public Mode getMode() {
        return mode;
    }

    public void setManualTarget(Player p) {
        manualTarget = p == null ? null : p.getUniqueId();
        currentTarget = manualTarget;
        if (p != null && mode == Mode.PATROL) mode = Mode.AUTO;
    }

    public void nextTarget() {
        manualTarget = null;
        switchAt = 0;
    }

    public String status() {
        return "mode=" + mode + " (now " + effLabel + "), target=" + targetName + ", viewers=" + viewers.size();
    }

    public String getStateJson() {
        return stateJson;
    }

    // ---------- main loop ----------

    private List<Player> candidates() {
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (viewers.contains(p.getUniqueId()) || plugin.isCameraAccount(p)) continue;
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            list.add(p);
        }
        return list;
    }

    private Player pickTarget(List<Player> cands, long now) {
        if (cands.isEmpty()) return null;
        if (manualTarget != null) {
            Player m = Bukkit.getPlayer(manualTarget);
            if (m != null && cands.contains(m)) return m;
            manualTarget = null;
        }
        Player cur = currentTarget == null ? null : Bukkit.getPlayer(currentTarget);
        if (cur != null && cands.contains(cur) && now < switchAt) return cur;
        int idx = cur == null ? -1 : cands.indexOf(cur);
        Player next = cands.get((idx + 1) % cands.size());
        currentTarget = next.getUniqueId();
        switchAt = now + plugin.getConfig().getLong("switch-seconds", 25) * 1000L;
        return next;
    }

    private void tick() {
        tickCount++;
        viewers.removeIf(id -> Bukkit.getPlayer(id) == null);

        FileConfiguration c = plugin.getConfig();
        long now = System.currentTimeMillis();
        List<Player> cands = candidates();

        Mode eff = mode == Mode.AUTO ? (cands.isEmpty() ? Mode.PATROL : Mode.CINEMATIC) : mode;
        Player target = null;
        if (eff != Mode.PATROL) {
            target = pickTarget(cands, now);
            if (target == null) eff = Mode.PATROL;
        }
        effLabel = eff.name();
        targetName = target == null ? "-" : target.getName();

        Location desired;
        Location look;
        boolean direct = false;

        if (eff == Mode.PATROL) {
            List<Location> pts = points(c);
            if (pts.isEmpty()) {
                World w = Bukkit.getWorlds().get(0);
                Location sp = w.getSpawnLocation();
                orbit += 0.6;
                double r = c.getDouble("patrol.fallback-radius", 30);
                double rad = Math.toRadians(orbit);
                desired = sp.clone().add(Math.cos(rad) * r, 25, Math.sin(rad) * r);
                look = sp;
            } else {
                Location p = pts.get(patrolIdx % pts.size());
                double speed = c.getDouble("patrol.speed", 0.6);
                if (cam == null || cam.getWorld() != p.getWorld()) {
                    cam = p.clone();
                    desired = cam;
                    look = p.clone().add(0, -2, 10);
                } else {
                    Vector to = p.toVector().subtract(cam.toVector());
                    double dist = to.length();
                    if (dist <= speed + 1) {
                        patrolIdx++;
                        desired = cam.clone();
                        look = cam.clone().add(0, -2, 0).add(to);
                    } else {
                        to.normalize();
                        desired = cam.clone().add(to.clone().multiply(speed));
                        look = desired.clone().add(to.getX() * 10, to.getY() * 10 - 2, to.getZ() * 10);
                    }
                }
                direct = true;
            }
        } else {
            Location eye = target.getEyeLocation();
            double ang = 180, dist = 5, h = 2;
            switch (eff) {
                case FOLLOW -> {
                    ang = 180;
                    dist = c.getDouble("follow.distance", 5);
                    h = c.getDouble("follow.height", 2);
                }
                case ORBIT -> {
                    orbit += c.getDouble("orbit.speed-degrees", 1.5);
                    ang = orbit;
                    dist = c.getDouble("orbit.radius", 8);
                    h = c.getDouble("orbit.height", 3);
                }
                default -> {
                    if (now > shotAt) {
                        shot = (shot + 1 + rnd.nextInt(2)) % SHOTS.length;
                        shotAt = now + c.getLong("cinematic.shot-seconds", 8) * 1000L;
                        spin = 0;
                    }
                    double[] s = SHOTS[shot];
                    spin += s[3];
                    ang = s[0] + spin;
                    dist = s[1];
                    h = s[2];
                }
            }
            double yawRad = Math.toRadians(target.getLocation().getYaw());
            Vector facing = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
            Vector offset = facing.rotateAroundY(Math.toRadians(ang)).multiply(dist);
            desired = eye.clone().add(offset).add(0, h, 0);

            // pull the camera in front of walls
            Vector dir = desired.toVector().subtract(eye.toVector());
            double len = dir.length();
            if (len > 0.5) {
                dir.normalize();
                RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, dir, len, FluidCollisionMode.NEVER, true);
                if (hit != null && hit.getHitPosition() != null) {
                    desired = hit.getHitPosition().subtract(dir.clone().multiply(0.5)).toLocation(eye.getWorld());
                }
            }
            look = eye;
        }

        // smoothing
        double smooth = c.getDouble("smoothing", 0.12);
        if (cam == null || cam.getWorld() != desired.getWorld() || cam.distanceSquared(desired) > 90000) {
            cam = desired.clone();
        } else if (direct) {
            cam = desired.clone();
        } else {
            cam = cam.clone().add(desired.toVector().subtract(cam.toVector()).multiply(smooth));
        }

        Vector d = look.toVector().subtract(cam.toVector());
        float ty = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        float tp = (float) -Math.toDegrees(Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ())));
        camYaw += wrap(ty - camYaw) * 0.2f;
        camPitch += (tp - camPitch) * 0.2f;
        cam.setYaw(camYaw);
        cam.setPitch(camPitch);

        // move viewers
        int interval = Math.max(1, c.getInt("teleport-interval-ticks", 2));
        if (!viewers.isEmpty() && tickCount % interval == 0) {
            for (UUID id : viewers) {
                Player v = Bukkit.getPlayer(id);
                if (v != null) v.teleport(cam);
            }
        }

        if (tickCount % 10 == 0) stateJson = buildState();
    }

    private static float wrap(float a) {
        while (a > 180) a -= 360;
        while (a < -180) a += 360;
        return a;
    }

    private List<Location> points(FileConfiguration c) {
        List<Location> out = new ArrayList<>();
        for (String s : c.getStringList("patrol.points")) {
            String[] p = s.split(";");
            if (p.length != 4) continue;
            World w = Bukkit.getWorld(p[0]);
            if (w == null) continue;
            try {
                out.add(new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3])));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public void addPoint(Location l) {
        List<String> list = new ArrayList<>(plugin.getConfig().getStringList("patrol.points"));
        list.add(l.getWorld().getName() + ";" + Math.round(l.getX()) + ";" + Math.round(l.getY()) + ";" + Math.round(l.getZ()));
        plugin.getConfig().set("patrol.points", list);
        plugin.saveConfig();
    }

    public void clearPoints() {
        plugin.getConfig().set("patrol.points", new ArrayList<String>());
        plugin.saveConfig();
        patrolIdx = 0;
    }

    // ---------- web state ----------

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String buildState() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"mode\":\"").append(mode).append("\",");
        sb.append("\"now\":\"").append(effLabel).append("\",");
        sb.append("\"target\":\"").append(esc(targetName)).append("\",");
        sb.append("\"viewers\":").append(viewers.size()).append(",");
        if (cam != null && cam.getWorld() != null) {
            sb.append("\"cam\":{\"world\":\"").append(esc(cam.getWorld().getName()))
                    .append("\",\"x\":").append(String.format(Locale.ROOT, "%.1f", cam.getX()))
                    .append(",\"y\":").append(String.format(Locale.ROOT, "%.1f", cam.getY()))
                    .append(",\"z\":").append(String.format(Locale.ROOT, "%.1f", cam.getZ()))
                    .append(",\"yaw\":").append(String.format(Locale.ROOT, "%.1f", cam.getYaw())).append("},");
        }
        sb.append("\"players\":[");
        boolean first = true;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (viewers.contains(p.getUniqueId()) || plugin.isCameraAccount(p)) continue;
            Location l = p.getLocation();
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"name\":\"").append(esc(p.getName())).append("\",\"world\":\"")
                    .append(esc(l.getWorld().getName())).append("\",\"x\":")
                    .append(String.format(Locale.ROOT, "%.1f", l.getX())).append(",\"z\":")
                    .append(String.format(Locale.ROOT, "%.1f", l.getZ())).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
