package com.codezyy.spectatorcamera;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Pushes the live camera/player data OUT to a relay service (see /relay).
 * Outbound HTTPS only, so it works on shared hosts that do not let you open ports.
 */
public class RelayPusher {

    private final SpectatorCameraPlugin plugin;
    private final Camera camera;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private BukkitTask task;
    private volatile long lastWarn;
    private volatile boolean failing;

    public RelayPusher(SpectatorCameraPlugin plugin, Camera camera) {
        this.plugin = plugin;
        this.camera = camera;
    }

    public void start() {
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("relay.enabled", false)) return;
        String url = c.getString("relay.url", "").trim().replaceAll("/+$", "");
        String key = c.getString("relay.key", "").trim();
        if (url.isEmpty() || key.isEmpty()) {
            plugin.getLogger().warning("relay.enabled is true but relay.url or relay.key is empty.");
            return;
        }
        long ticks = Math.max(2L, c.getLong("relay.interval-ms", 500L) / 50L);
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> push(url, key), 40L, ticks);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    private void push(String url, String key) {
        try {
            String body = "{\"state\":" + camera.getStateJson()
                    + ",\"overlay\":" + WebPreview.overlayJson(plugin.getConfig(), true) + "}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/push"))
                    .timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/json")
                    .header("X-Relay-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((r, e) -> {
                boolean ok = e == null && r.statusCode() / 100 == 2;
                if (ok) {
                    if (failing) {
                        failing = false;
                        plugin.getLogger().info("Relay connected.");
                    }
                    return;
                }
                failing = true;
                long now = System.currentTimeMillis();
                if (now - lastWarn > 60_000) {
                    lastWarn = now;
                    plugin.getLogger().warning("Relay push failed ("
                            + (e != null ? e.getClass().getSimpleName() : "HTTP " + r.statusCode())
                            + "). Check relay.url and relay.key.");
                }
            });
        } catch (Exception ex) {
            long now = System.currentTimeMillis();
            if (now - lastWarn > 60_000) {
                lastWarn = now;
                plugin.getLogger().warning("Relay push error: " + ex.getMessage());
            }
        }
    }
}
