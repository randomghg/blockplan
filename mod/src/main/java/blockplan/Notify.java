package blockplan;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/**
 * Zeigt die ✓/✗-Meldungen an. Standard: als Leiste oben (Bossbar), damit sie nicht mit
 * anderen Mods kollidiert, die die Anzeige über der Hotbar benutzen (z. B. ein Timer).
 */
final class Notify {
	private Notify() {}

	enum Mode { OBEN, UNTEN, CHAT }
	static Mode mode = Mode.OBEN;
	private static final int SHOW_TICKS = 60; // 3 Sekunden

	private record Shown(ServerBossEvent bar, int[] left) {}
	private static final Map<UUID, Shown> bars = new HashMap<>();

	static void show(ServerPlayer p, Component msg, ChatFormatting color) {
		switch (mode) {
			case UNTEN -> p.sendOverlayMessage(msg);
			case CHAT -> p.sendSystemMessage(msg);
			default -> {
				Shown s = bars.get(p.getUUID());
				if (s == null) {
					ServerBossEvent b = new ServerBossEvent(UUID.randomUUID(), msg, barColor(color), BossEvent.BossBarOverlay.PROGRESS);
					b.addPlayer(p);
					s = new Shown(b, new int[]{0});
					bars.put(p.getUUID(), s);
				}
				s.bar().setName(msg);
				s.bar().setColor(barColor(color));
				s.bar().setProgress(Math.max(0f, Math.min(1f, Tracker.fraction())));
				s.bar().setVisible(true);
				s.left()[0] = SHOW_TICKS;
			}
		}
	}

	static void tick(MinecraftServer server) {
		Iterator<Map.Entry<UUID, Shown>> it = bars.entrySet().iterator();
		while (it.hasNext()) {
			Shown s = it.next().getValue();
			if (--s.left()[0] <= 0) { s.bar().removeAllPlayers(); it.remove(); }
		}
	}

	static void clear() { for (Shown s : bars.values()) s.bar().removeAllPlayers(); bars.clear(); }

	private static BossEvent.BossBarColor barColor(ChatFormatting c) {
		return switch (c) {
			case GREEN -> BossEvent.BossBarColor.GREEN;
			case RED -> BossEvent.BossBarColor.RED;
			case GOLD, YELLOW -> BossEvent.BossBarColor.YELLOW;
			default -> BossEvent.BossBarColor.WHITE;
		};
	}

	/* ---- Einstellung merken (config\blockplan\anzeige.txt) ---- */
	static void load(Path dir) {
		try {
			Path f = dir.resolve("anzeige.txt");
			if (Files.exists(f)) mode = Mode.valueOf(Files.readString(f, StandardCharsets.UTF_8).trim().toUpperCase());
		} catch (Exception e) { mode = Mode.OBEN; }
	}

	static void save(Path dir) {
		try { Files.createDirectories(dir); Files.writeString(dir.resolve("anzeige.txt"), mode.name().toLowerCase(), StandardCharsets.UTF_8); }
		catch (Exception ignored) {}
	}
}
