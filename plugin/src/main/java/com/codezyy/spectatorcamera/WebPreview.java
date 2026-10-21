package com.codezyy.spectatorcamera;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

public class WebPreview {

    private final SpectatorCameraPlugin plugin;
    private final Camera camera;
    private HttpServer server;

    public WebPreview(SpectatorCameraPlugin plugin, Camera camera) {
        this.plugin = plugin;
        this.camera = camera;
    }

    public void start() throws IOException {
        String host = plugin.getConfig().getString("web.host", "0.0.0.0");
        int port = plugin.getConfig().getInt("web.port", 8765);
        server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/api/state", ex -> {
            if (!authorized(ex)) { send(ex, 401, "text/plain", "Unauthorized"); return; }
            send(ex, 200, "application/json", camera.getStateJson());
        });
        server.createContext("/api/overlay", ex -> {
            if (!authorized(ex)) { send(ex, 401, "text/plain", "Unauthorized"); return; }
            send(ex, 200, "application/json", overlayJson(plugin.getConfig(), false));
        });
        server.createContext("/overlay/", ex -> {
            if (!authorized(ex)) { send(ex, 401, "text/plain", "Unauthorized"); return; }
            serveOverlayFile(ex);
        });
        server.createContext("/", ex -> {
            if (!authorized(ex)) { send(ex, 401, "text/plain", "Unauthorized. Open /?token=YOURTOKEN"); return; }
            send(ex, 200, "text/html; charset=utf-8", PAGE);
        });

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private boolean authorized(HttpExchange ex) {
        String token = plugin.getConfig().getString("web.token", "");
        if (token == null || token.isEmpty()) return true;
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return false;
        for (String part : q.split("&")) {
            if (part.equals("token=" + token)) return true;
        }
        return false;
    }

    private void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    // ---------- overlay config ----------

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static String resolve(String v, boolean remote) {
        if (v == null || v.isBlank()) return "";
        if (v.startsWith("http://") || v.startsWith("https://")) return v;
        if (remote) return ""; // local files cannot be served through the relay, use an https URL
        return "overlay/" + URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String overlayJson(FileConfiguration c, boolean remote) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"enabled\":").append(c.getBoolean("overlay.enabled", true)).append(",");
        sb.append("\"text\":\"").append(esc(c.getString("overlay.text", ""))).append("\",");
        sb.append("\"textPos\":\"").append(esc(c.getString("overlay.text-position", "bottom"))).append("\",");
        sb.append("\"image\":\"").append(esc(resolve(c.getString("overlay.image", ""), remote))).append("\",");
        sb.append("\"imagePos\":\"").append(esc(c.getString("overlay.image-position", "top-left"))).append("\",");
        sb.append("\"imageWidth\":").append(c.getInt("overlay.image-width", 160)).append(",");
        sb.append("\"video\":\"").append(esc(resolve(c.getString("overlay.video", ""), remote))).append("\",");
        sb.append("\"videoPos\":\"").append(esc(c.getString("overlay.video-position", "top-right"))).append("\",");
        sb.append("\"videoWidth\":").append(c.getInt("overlay.video-width", 220));
        sb.append("}");
        return sb.toString();
    }

    // ---------- overlay files (plugins/SpectatorCamera/overlay/) ----------

    private void serveOverlayFile(HttpExchange ex) throws IOException {
        String name = ex.getRequestURI().getPath().substring("/overlay/".length());
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            send(ex, 400, "text/plain", "Bad file name");
            return;
        }
        Path dir = plugin.getDataFolder().toPath().resolve("overlay").toAbsolutePath().normalize();
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain", "Not found");
            return;
        }
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        String type;
        switch (ext) {
            case "png" -> type = "image/png";
            case "jpg", "jpeg" -> type = "image/jpeg";
            case "gif" -> type = "image/gif";
            case "webp" -> type = "image/webp";
            case "mp4" -> type = "video/mp4";
            case "webm" -> type = "video/webm";
            default -> type = null;
        }
        if (type == null) {
            send(ex, 415, "text/plain", "Unsupported file type");
            return;
        }
        if (Files.size(file) > 80L * 1024 * 1024) {
            send(ex, 413, "text/plain", "File too large (max 80 MB)");
            return;
        }
        byte[] data = Files.readAllBytes(file);
        int total = data.length;
        if (total == 0) {
            send(ex, 404, "text/plain", "Empty file");
            return;
        }

        int start = 0, end = total - 1, code = 200;
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            try {
                String[] p = range.substring(6).split("-", 2);
                if (p[0].isEmpty()) {
                    int n = Integer.parseInt(p[1].trim());
                    start = Math.max(0, total - n);
                } else {
                    start = Integer.parseInt(p[0].trim());
                    if (p.length > 1 && !p[1].isEmpty()) end = Math.min(Integer.parseInt(p[1].trim()), total - 1);
                }
                if (start > end || start >= total) {
                    ex.getResponseHeaders().set("Content-Range", "bytes */" + total);
                    ex.sendResponseHeaders(416, -1);
                    ex.close();
                    return;
                }
                code = 206;
            } catch (RuntimeException e) {
                start = 0;
                end = total - 1;
                code = 200;
            }
        }

        int len = end - start + 1;
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
        ex.getResponseHeaders().set("Cache-Control", "max-age=60");
        if (code == 206) ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + total);
        ex.sendResponseHeaders(code, len);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data, start, len);
        }
    }

    // ---------- page ----------

    private static final String PAGE = """
            <!doctype html>
            <html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>SpectatorCamera Live</title>
            <style>
              html,body{margin:0;height:100%;background:#0d1117;color:#e6edf3;font-family:system-ui,sans-serif;overflow:hidden}
              #bar{height:56px;box-sizing:border-box;padding:10px 12px;font-size:14px;display:flex;gap:10px;flex-wrap:wrap;align-items:center}
              #bar b{color:#58a6ff}
              button{background:#21262d;color:#e6edf3;border:1px solid #30363d;border-radius:6px;padding:4px 10px;font-size:16px}
              canvas{display:block;width:100vw;height:calc(100vh - 56px)}
              body.clean #bar{display:none}
              body.clean canvas{height:100vh}
              #ovt{position:fixed;left:50%;transform:translateX(-50%);max-width:90vw;padding:10px 18px;background:rgba(0,0,0,.55);
                   border-radius:10px;font-size:clamp(16px,4vw,34px);font-weight:700;text-align:center;display:none}
              #ovt.bottom{bottom:6%}
              #ovt.top{top:4%}
              #ovi,#ovv{position:fixed;display:none;border-radius:8px;object-fit:contain}
            </style></head><body>
            <div id="bar"><span id="info">connecting...</span>
              <button id="zi">+</button><button id="zo">-</button></div>
            <canvas id="c"></canvas>
            <div id="ovt"></div><img id="ovi" alt=""><video id="ovv" autoplay loop muted playsinline></video>
            <script>
            const c=document.getElementById('c'),x=c.getContext('2d'),info=document.getElementById('info');
            const ovt=document.getElementById('ovt'),ovi=document.getElementById('ovi'),ovv=document.getElementById('ovv');
            const params=new URLSearchParams(location.search);
            if(params.get('clean')==='1')document.body.classList.add('clean');
            let scale=4,state=null,ov=null;
            function fit(){c.width=c.clientWidth*devicePixelRatio;c.height=c.clientHeight*devicePixelRatio}
            addEventListener('resize',fit);fit();
            document.getElementById('zi').onclick=()=>scale=Math.min(24,scale*1.4);
            document.getElementById('zo').onclick=()=>scale=Math.max(0.5,scale/1.4);

            function fill(t){
              const s=state||{players:[]};
              return t.split('{target}').join(s.target&&s.target!=='-'?s.target:'patrolling')
                      .split('{mode}').join(s.now||'')
                      .split('{players}').join(String((s.players||[]).length));
            }
            function src(u){return /^https?:/.test(u)?u:u+location.search}
            function pos(el,p){
              p=p||'top-left';const m='16px';
              el.style.top=el.style.bottom=el.style.left=el.style.right='auto';
              if(p.indexOf('top')===0)el.style.top=m;else el.style.bottom=m;
              if(p.indexOf('left')>=0)el.style.left=m;else el.style.right=m;
            }
            function applyOv(){
              if(!ov||!ov.enabled){ovt.style.display=ovi.style.display=ovv.style.display='none';return}
              pos(ovi,ov.imagePos);pos(ovv,ov.videoPos);
              ovi.style.width=ov.imageWidth+'px';ovv.style.width=ov.videoWidth+'px';
              const is=ov.image?src(ov.image):'';
              if(is&&ovi.getAttribute('src')!==is)ovi.src=is;
              ovi.style.display=is?'block':'none';
              const vs=ov.video?src(ov.video):'';
              if(vs&&ovv.getAttribute('src')!==vs){ovv.src=vs;ovv.play().catch(()=>{})}
              ovv.style.display=vs?'block':'none';
              ovt.className=ov.textPos==='top'?'top':'bottom';
            }
            async function loadOv(){
              try{ov=await (await fetch('api/overlay'+location.search)).json();applyOv()}catch(e){}
              setTimeout(loadOv,10000);
            }
            async function poll(){
              try{const r=await fetch('api/state'+location.search);state=await r.json();
                info.innerHTML='<b>'+state.now+'</b> | mode '+state.mode+' | target '+state.target+' | viewers '+state.viewers;
                if(ov&&ov.enabled&&ov.text){ovt.textContent=fill(ov.text);ovt.style.display='block'}else{ovt.style.display='none'}
              }catch(e){info.textContent='offline'}
              setTimeout(poll,500);
            }
            function draw(){
              const W=c.width,H=c.height;x.fillStyle='#0d1117';x.fillRect(0,0,W,H);
              if(state&&state.cam){
                const s=scale*devicePixelRatio,cx=state.cam.x,cz=state.cam.z;
                x.strokeStyle='#21262d';x.lineWidth=1;
                const step=16*s;
                const ox=(W/2-(cx*s)%step+step)%step,oy=(H/2-(cz*s)%step+step)%step;
                for(let gx=ox;gx<W;gx+=step){x.beginPath();x.moveTo(gx,0);x.lineTo(gx,H);x.stroke()}
                for(let gy=oy;gy<H;gy+=step){x.beginPath();x.moveTo(0,gy);x.lineTo(W,gy);x.stroke()}
                x.font=(12*devicePixelRatio)+'px system-ui';
                for(const p of state.players){
                  if(p.world!==state.cam.world)continue;
                  const px=W/2+(p.x-cx)*s,py=H/2+(p.z-cz)*s;
                  x.fillStyle=p.name===state.target?'#f0883e':'#3fb950';
                  x.beginPath();x.arc(px,py,5*devicePixelRatio,0,7);x.fill();
                  x.fillStyle='#e6edf3';x.fillText(p.name,px+8*devicePixelRatio,py+4*devicePixelRatio);
                }
                const a=(state.cam.yaw)*Math.PI/180,dx=-Math.sin(a),dz=Math.cos(a);
                x.save();x.translate(W/2,H/2);
                x.fillStyle='rgba(88,166,255,0.18)';x.beginPath();x.moveTo(0,0);
                const L=60*devicePixelRatio,f=0.6;
                x.lineTo((dx*Math.cos(f)-dz*Math.sin(f))*L,(dx*Math.sin(f)+dz*Math.cos(f))*L);
                x.lineTo((dx*Math.cos(-f)-dz*Math.sin(-f))*L,(dx*Math.sin(-f)+dz*Math.cos(-f))*L);
                x.closePath();x.fill();
                x.fillStyle='#58a6ff';x.beginPath();x.arc(0,0,6*devicePixelRatio,0,7);x.fill();
                x.restore();
                x.fillStyle='#8b949e';
                x.fillText('cam '+state.cam.x+', '+state.cam.y+', '+state.cam.z+'  ('+state.cam.world+')',10,H-10);
              }
              requestAnimationFrame(draw);
            }
            loadOv();poll();draw();
            </script></body></html>
            """;
}
