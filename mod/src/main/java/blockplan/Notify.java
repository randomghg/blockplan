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
 * Zeigt die ✓/✗-Meldungen an. Standard: über der Hotbar. Damit ein Timer an derselben Stelle
 * die Meldung nicht sofort überschreibt, wird sie 2 Sekunden lang jeden Tick neu gesendet;
 * danach erscheint der Timer wieder. Alternativ: Leiste oben oder Chat.
 */
public final class Notify {
	private Notify() {}

	enum Mode { OBEN, UNTEN, CHAT }
	static Mode mode = Mode.UNTEN;
	private static final int HOLD_TICKS = 40; // 2 Sekunden über der Hotbar halten
	private record Held(ServerPlayer player, Component msg, int[] left) {}
	private static final Map<UUID, Held> held = new HashMap<>();
	private static final int SHOW_TICKS = 60; // 3 Sekunden

	private record Shown(ServerBossEvent bar, int[] left) {}
	private static final Map<UUID, Shown> bars = new HashMap<>();

	/**
	 * Für andere Mods (z. B. den Survival-Timer): true, solange gerade eine Blockplan-Meldung
	 * über der Hotbar dieses Spielers steht. Dann sollen sie die Anzeige kurz in Ruhe lassen.
	 */
	public static boolean busy(UUID player) {
		return mode == Mode.UNTEN && held.containsKey(player);
	}

	static void show(ServerPlayer p, Component msg, ChatFormatting color) {
		switch (mode) {
			case UNTEN -> { p.sendOverlayMessage(msg); held.put(p.getUUID(), new Held(p, msg, new int[]{HOLD_TICKS})); }
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
		Iterator<Map.Entry<UUID, Held>> ih = held.entrySet().iterator();
		while (ih.hasNext()) {
			Held h = ih.next().getValue();
			if (h.player().isRemoved() || --h.left()[0] <= 0) { ih.remove(); continue; }
			h.player().sendOverlayMessage(h.msg());
		}
		Iterator<Map.Entry<UUID, Shown>> it = bars.entrySet().iterator();
		while (it.hasNext()) {
			Shown s = it.next().getValue();
			if (--s.left()[0] <= 0) { s.bar().removeAllPlayers(); it.remove(); }
		}
	}

	static void clear() { held.clear(); for (Shown s : bars.values()) s.bar().removeAllPlayers(); bars.clear(); }

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
		} catch (Exception e) { mode = Mode.UNTEN; }
	}

	static void save(Path dir) {
		try { Files.createDirectories(dir); Files.writeString(dir.resolve("anzeige.txt"), mode.name().toLowerCase(), StandardCharsets.UTF_8); }
		catch (Exception ignored) {}
	}
}
