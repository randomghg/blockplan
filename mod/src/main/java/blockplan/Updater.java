package blockplan;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * Holt die neueste Website aus dem GitHub-Repo und legt sie nach config/blockplan/index.html.
 * Abschalten: eine leere Datei config/blockplan/kein-update anlegen.
 */
final class Updater {
	static final String URL = "https://raw.githubusercontent.com/randomghg/blockplan/main/web/index.html";
	static volatile String lastResult = "noch nicht geprüft";

	private Updater() {}

	static boolean disabled() {
		return BlockplanMod.dir != null && Files.exists(BlockplanMod.dir.resolve("kein-update"));
	}

	/** Läuft im Hintergrund, blockiert das Spiel nicht. */
	static CompletableFuture<String> check() {
		if (disabled()) { lastResult = "Auto-Update ist aus (Datei kein-update)"; return CompletableFuture.completedFuture(lastResult); }
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpRequest req = HttpRequest.newBuilder(URI.create(URL + "?t=" + System.currentTimeMillis()))
				.timeout(Duration.ofSeconds(20)).header("Cache-Control", "no-cache").GET().build();
		return client.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).handle((res, err) -> {
			if (err != null) return lastResult = "kein Internet oder GitHub nicht erreichbar";
			if (res.statusCode() != 200) return lastResult = "GitHub antwortet mit " + res.statusCode();
			byte[] body = res.body();
			String text = new String(body, StandardCharsets.UTF_8);
			// nur eine vollständige Seite übernehmen
			if (body.length < 50_000 || !text.contains("<html") || !text.contains("</html>") || !text.contains("blockplan"))
				return lastResult = "Datei auf GitHub sieht unvollständig aus, behalte die alte";
			try {
				Files.createDirectories(BlockplanMod.dir);
				Path target = BlockplanMod.dir.resolve("index.html");
				if (Files.exists(target) && Arrays.equals(Files.readAllBytes(target), body))
					return lastResult = "Website ist aktuell";
				Path tmp = BlockplanMod.dir.resolve("index.html.neu");
				Files.write(tmp, body);
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				BlockplanMod.LOG.info("Blockplan: neue Website von GitHub geladen ({} KB)", body.length / 1024);
				return lastResult = "neue Website geladen, Seite im Browser neu laden (F5)";
			} catch (Exception e) {
				BlockplanMod.LOG.error("Blockplan: Website-Update fehlgeschlagen", e);
				return lastResult = "konnte nicht speichern: " + e.getMessage();
			}
		});
	}
}
