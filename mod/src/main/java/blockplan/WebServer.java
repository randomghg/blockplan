package blockplan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Kleiner Webserver nur für diesen PC: liefert die Website und die Live-Daten. */
public final class WebServer {
	public static final int PORT = 8765;
	private static final long MAX_UPLOAD = 400L * 1024 * 1024;
	private static HttpServer http;

	static synchronized void start(MinecraftServer server) {
		if (http != null) return;
		try {
			http = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
			http.setExecutor(Executors.newFixedThreadPool(3, r -> { Thread t = new Thread(r, "blockplan-web"); t.setDaemon(true); return t; }));
			http.createContext("/", ex -> {
				if (!ex.getRequestURI().getPath().equals("/") && !ex.getRequestURI().getPath().equals("/index.html")) { send(ex, 404, "text/plain", bytes("Nicht gefunden")); return; }
				java.nio.file.Path custom = BlockplanMod.dir == null ? null : BlockplanMod.dir.resolve("index.html");
				if (custom != null && java.nio.file.Files.exists(custom)) { send(ex, 200, "text/html; charset=utf-8", java.nio.file.Files.readAllBytes(custom)); return; }
				try (InputStream in = WebServer.class.getResourceAsStream("/assets/blockplan/web/index.html")) {
					if (in == null) { send(ex, 500, "text/plain", bytes("Website fehlt in der Mod")); return; }
					send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
				}
			});
			http.createContext("/api/assets", ex -> {
				byte[] z = Assets.zip();
				if (z == null) { send(ex, 404, "text/plain", bytes("Keine Texturen im Spiel gefunden")); return; }
				send(ex, 200, "application/zip", z);
			});
			http.createContext("/api/info", ex -> {
				JsonObject o = new JsonObject();
				o.addProperty("mod", BlockplanMod.version());
				o.addProperty("mc", Assets.mcVersion());
				o.addProperty("update", Updater.lastResult);
				send(ex, 200, "application/json", bytes(o.toString()));
			});
			http.createContext("/api/state", ex -> send(ex, 200, "application/json", bytes(Tracker.stateJson)));
			http.createContext("/api/wrong", ex -> send(ex, 200, "application/json", bytes(Tracker.wrongJson)));
			http.createContext("/api/status", ex -> {
				ex.getResponseHeaders().add("X-Version", String.valueOf(Tracker.statusSnapshotVersion));
				send(ex, 200, "application/octet-stream", Tracker.statusSnapshot);
			});
			http.createContext("/api/plan", ex -> {
				if ("POST".equals(ex.getRequestMethod())) {
					byte[] body = readBody(ex);
					if (body == null) { send(ex, 413, "text/plain", bytes("Plan zu groß")); return; }
					try {
						Plan p = Plan.read(body);
						server.execute(() -> { Tracker.setPlan(p); BlockplanMod.savePlan(body); });
						send(ex, 200, "application/json", bytes("{\"ok\":true,\"req\":" + p.cells.length + "}"));
					} catch (Exception e) {
						BlockplanMod.LOG.error("Blockplan: Plan-Upload fehlgeschlagen", e);
						send(ex, 400, "text/plain", bytes("Plan konnte nicht gelesen werden: " + e.getMessage()));
					}
					return;
				}
				Plan p = Tracker.plan;
				if (p == null) { send(ex, 404, "text/plain", bytes("Kein Plan")); return; }
				send(ex, 200, "application/octet-stream", p.raw);
			});
			http.createContext("/api/anchor", ex -> {
				if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", bytes("Nur POST")); return; }
				try {
					byte[] body = readBody(ex);
					JsonObject o = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
					Tracker.Anchor a = new Tracker.Anchor(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), Math.floorMod(o.get("rot").getAsInt(), 4));
					server.execute(() -> BlockplanMod.setAnchor(a));
					send(ex, 200, "application/json", bytes("{\"ok\":true}"));
				} catch (Exception e) {
					send(ex, 400, "text/plain", bytes("Ungültige Position"));
				}
			});
			http.start();
			BlockplanMod.LOG.info("Blockplan: Website läuft auf http://localhost:{}", PORT);
		} catch (IOException e) {
			BlockplanMod.LOG.error("Blockplan: Webserver konnte nicht starten (Port {} belegt?)", PORT, e);
			http = null;
		}
	}

	static synchronized void stop() {
		if (http != null) { http.stop(0); http = null; }
	}

	private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

	private static byte[] readBody(HttpExchange ex) throws IOException {
		try (InputStream in = ex.getRequestBody()) {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] buf = new byte[1 << 16];
			int r;
			while ((r = in.read(buf)) > 0) {
				bos.write(buf, 0, r);
				if (bos.size() > MAX_UPLOAD) return null;
			}
			return bos.toByteArray();
		}
	}

	private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
		ex.getResponseHeaders().set("Content-Type", type);
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
		if (body.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(body); }
		ex.close();
	}
}
