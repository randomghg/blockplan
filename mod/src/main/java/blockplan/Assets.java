package blockplan;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packt Texturen, Blockzustände und Blockmodelle direkt aus dem laufenden Minecraft in eine Zip.
 * Die Website lädt diese Zip, damit niemand mehr die .jar suchen muss.
 */
final class Assets {
	private static final String[] DIRS = {
			"assets/minecraft/blockstates",
			"assets/minecraft/models/block",
			"assets/minecraft/textures/block"
	};
	private static final String[] FILES = {"assets/minecraft/lang/en_us.json"};
	private static volatile byte[] zip;

	private Assets() {}

	static String mcVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	/** null, wenn die Dateien nicht gefunden wurden (z. B. auf einem reinen Server ohne Client-Dateien). */
	static synchronized byte[] zip() {
		if (zip != null) return zip.length == 0 ? null : zip;
		try {
			Optional<ModContainer> mc = FabricLoader.getInstance().getModContainer("minecraft");
			if (mc.isEmpty()) { zip = new byte[0]; return null; }
			zip = build(mc.get().getRootPaths());
			if (zip.length == 0) {
				BlockplanMod.LOG.warn("Blockplan: keine Texturen im Spiel gefunden, die Website braucht dann die .jar");
				return null;
			}
			BlockplanMod.LOG.info("Blockplan: Texturen und Modelle für die Website bereit ({} KB)", zip.length / 1024);
			return zip;
		} catch (IOException | RuntimeException e) {
			BlockplanMod.LOG.error("Blockplan: Texturen konnten nicht gelesen werden", e);
			zip = new byte[0];
			return null;
		}
	}

	/** Leeres Array, wenn nichts gefunden wurde. */
	static byte[] build(List<Path> roots) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream(8 << 20);
		int count = 0;
		java.util.Set<String> seen = new java.util.HashSet<>();
		try (ZipOutputStream out = new ZipOutputStream(bos)) {
			out.setLevel(Deflater.BEST_SPEED);
			for (Path root : roots) {
				for (String d : DIRS) {
					Path dir = root.resolve(d);
					if (!Files.isDirectory(dir)) continue;
					try (Stream<Path> walk = Files.walk(dir)) {
						for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
							String rel = root.relativize(f).toString().replace('\\', '/');
							if (!rel.endsWith(".png") && !rel.endsWith(".json")) continue;
							if (rel.startsWith("/")) rel = rel.substring(1);
							if (!seen.add(rel)) continue;
							out.putNextEntry(new ZipEntry(rel));
							out.write(Files.readAllBytes(f));
							out.closeEntry();
							count++;
						}
					}
				}
				for (String name : FILES) {
					Path f = root.resolve(name);
					if (!Files.isRegularFile(f) || !seen.add(name)) continue;
					out.putNextEntry(new ZipEntry(name));
					out.write(Files.readAllBytes(f));
					out.closeEntry();
				}
			}
		}
		return count == 0 ? new byte[0] : bos.toByteArray();
	}
}
