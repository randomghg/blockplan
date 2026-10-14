package blockplan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.Deflater;

/** Vergleicht die Overworld mit dem Plan. Alles hier läuft auf dem Server-Thread, außer den snapshot-Methoden. */
public final class Tracker {
	public static final int UNKNOWN = 0, MISSING = 1, CORRECT = 2, WRONG = 3;
	private static final String[] DIRS = {"north", "east", "south", "west"};
	private static final Set<String> IMPORTANT = Set.of("facing", "half", "type", "axis", "hinge", "rotation", "face", "attachment", "hanging", "orientation");
	private static final Set<String> EMPTY = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:water", "minecraft:lava",
			"minecraft:seagrass", "minecraft:tall_seagrass", "minecraft:kelp", "minecraft:kelp_plant", "minecraft:short_grass", "minecraft:tall_grass", "minecraft:bubble_column");

	/** Natürliche Blöcke aus der Weltgenerierung. Stehen sie an einer Stelle, die noch niemand angefasst hat,
	 *  zählt das als „fehlt noch“ statt „falsch“ (Meeresboden, Höhlen, Untergrund). */
	private static final Set<String> NATURAL = Set.of("stone", "deepslate", "tuff", "granite", "diorite", "andesite", "calcite",
			"dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium", "gravel", "sand", "red_sand", "suspicious_sand",
			"suspicious_gravel", "clay", "sandstone", "red_sandstone", "mud", "moss_block", "dripstone_block", "pointed_dripstone",
			"smooth_basalt", "amethyst_block", "budding_amethyst", "magma_block", "obsidian", "bedrock", "cobblestone",
			"mossy_cobblestone", "cobbled_deepslate", "sea_pickle", "glow_lichen", "prismarine", "prismarine_bricks", "dark_prismarine",
			"sea_lantern", "wet_sponge", "packed_ice", "ice", "blue_ice", "snow_block", "powder_snow", "lava");
	static boolean natural(String id) {
		String n = id.startsWith("minecraft:") ? id.substring(10) : id;
		return NATURAL.contains(n) || n.endsWith("_ore") || n.startsWith("infested_") || n.endsWith("_coral_block") || n.endsWith("_coral") || n.endsWith("_coral_fan");
	}
	/** Stellen, an denen ein Spieler gebaut oder abgebaut hat. Dort wird streng geprüft. */
	static java.util.BitSet touched = new java.util.BitSet();
	static volatile boolean touchedDirty;

	public record Anchor(int x, int y, int z, int rot) {}

	static volatile Plan plan;
	static volatile Anchor anchor;
	private static byte[] status = new byte[0];
	private static final int[] counts = new int[4];
	static volatile int version;
	private static int scanPos;
	static final Set<UUID> muted = new HashSet<>();
	private static final List<Object[]> pending = new ArrayList<>();
	// Schnappschüsse für den Webserver (anderer Thread)
	static volatile String stateJson = "{}";
	static volatile byte[] statusSnapshot = new byte[0];
	static volatile int statusSnapshotVersion = -1;
	static volatile String wrongJson = "[]";

	static void setPlan(Plan p) {
		plan = p;
		status = new byte[p == null ? 0 : p.cells.length];
		counts[0] = status.length; counts[1] = counts[2] = counts[3] = 0;
		touched = new java.util.BitSet(status.length); touchedDirty = true;
		scanPos = 0; version++;
	}

	static void setAnchor(Anchor a) {
		anchor = a;
		if (plan != null) setPlan(plan);
	}

	/* ---- Koordinaten: Plan (lokal) <-> Welt, Drehung im Uhrzeigersinn in 90°-Schritten ---- */
	static void worldPos(Plan p, Anchor a, int cell, BlockPos.MutableBlockPos out) {
		int lx = cell % p.sx, t = cell / p.sx, lz = t % p.sz, ly = t / p.sz;
		int x, z;
		switch (a.rot & 3) {
			case 1 -> { x = p.sz - 1 - lz; z = lx; }
			case 2 -> { x = p.sx - 1 - lx; z = p.sz - 1 - lz; }
			case 3 -> { x = lz; z = p.sx - 1 - lx; }
			default -> { x = lx; z = lz; }
		}
		out.set(a.x + x, a.y + ly, a.z + z);
	}

	/** Raster-Index der Welt-Position, oder -1 außerhalb. */
	static int cellOf(Plan p, Anchor a, BlockPos pos) {
		int dx = pos.getX() - a.x, ly = pos.getY() - a.y, dz = pos.getZ() - a.z, lx, lz;
		switch (a.rot & 3) {
			case 1 -> { lx = dz; lz = p.sz - 1 - dx; }
			case 2 -> { lx = p.sx - 1 - dx; lz = p.sz - 1 - dz; }
			case 3 -> { lx = p.sx - 1 - dz; lz = dx; }
			default -> { lx = dx; lz = dz; }
		}
		if (lx < 0 || ly < 0 || lz < 0 || lx >= p.sx || ly >= p.sy || lz >= p.sz) return -1;
		return (ly * p.sz + lz) * p.sx + lx;
	}

	static String rotValueT(String k,String v,int r){ return rotValue(k,v,r); }
	private static String rotValue(String key, String v, int rot) {
		rot &= 3;
		if (rot == 0) return v;
		switch (key) {
			case "facing", "attachment_facing" -> {
				for (int i = 0; i < 4; i++) if (DIRS[i].equals(v)) return DIRS[(i + rot) % 4];
				return v;
			}
			case "axis" -> { return (rot & 1) == 1 ? (v.equals("x") ? "z" : v.equals("z") ? "x" : v) : v; }
			case "rotation" -> {
				try { return String.valueOf((Integer.parseInt(v) + 4 * rot) % 16); } catch (NumberFormatException e) { return v; }
			}
			default -> { return v; }
		}
	}

	static String idOf(BlockState s) {
		return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
	}

	static Map<String, String> propsOf(BlockState s) {
		Map<String, String> m = new HashMap<>();
		for (Property<?> p : s.getProperties()) m.put(p.getName(), valueName(s, p));
		return m;
	}

	private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> p) {
		return p.getName(s.getValue(p));
	}

	static int evaluate(Plan p, int i, BlockState s, int rot) {
		String id = idOf(s);
		if (EMPTY.contains(id) && !id.equals(p.blockId[p.pal[i]])) return MISSING;
		if (!id.equals(p.blockId[p.pal[i]])) return (!touched.get(i) && natural(id)) ? MISSING : WRONG;
		Map<String, String> want = p.props[p.pal[i]];
		if (want.isEmpty()) return CORRECT;
		Map<String, String> have = null;
		for (Map.Entry<String, String> e : want.entrySet()) {
			if (!IMPORTANT.contains(e.getKey())) continue;
			if (have == null) have = propsOf(s);
			String h = have.get(e.getKey());
			if (h != null && !h.equals(rotValue(e.getKey(), e.getValue(), rot))) return WRONG;
		}
		return CORRECT;
	}

	static String describe(Plan p, int i, int rot) {
		String id = p.blockId[p.pal[i]];
		StringBuilder sb = new StringBuilder(nice(id));
		Map<String, String> want = p.props[p.pal[i]];
		List<String> parts = new ArrayList<>();
		for (String k : new String[]{"facing", "half", "type", "axis", "hinge"}) {
			String v = want.get(k);
			if (v != null) parts.add(k + "=" + rotValue(k, v, rot));
		}
		if (!parts.isEmpty()) sb.append(" [").append(String.join(", ", parts)).append(']');
		return sb.toString();
	}

	static String nice(String id) {
		String s = id.replace("minecraft:", "").replace('_', ' ');
		StringBuilder b = new StringBuilder();
		for (String w : s.split(" ")) if (!w.isEmpty()) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
		return b.toString().trim();
	}

	private static void setStatus(int i, int st) {
		int o = status[i];
		if (o != st) { counts[o]--; counts[st]++; status[i] = (byte) st; version++; }
	}

	/* ---- Ereignisse ---- */
	static void queueCheck(ServerPlayer player, BlockPos a, BlockPos b) {
		pending.add(new Object[]{player, a.immutable(), b.immutable(), 2});
	}

	static void onBreak(ServerPlayer player, BlockPos pos) {
		pending.add(new Object[]{player, pos.immutable(), null, 1});
	}

	static void tick(MinecraftServer server) {
		Plan p = plan; Anchor a = anchor;
		ServerLevel lvl = server.overworld();
		if (p != null && a != null && !pending.isEmpty()) {
			List<Object[]> todo = new ArrayList<>(pending);
			pending.clear();
			for (Object[] e : todo) {
				int delay = (int) e[3];
				if (delay > 1) { e[3] = delay - 1; pending.add(e); continue; }
				ServerPlayer pl = (ServerPlayer) e[0];
				if (pl.level() != lvl) continue;
				for (int k = 1; k <= 2; k++) if (e[k] != null) checkAt(pl, lvl, p, a, (BlockPos) e[k], e[2] == null);
			}
		}
		if (p != null && a != null) {
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			int n = p.cells.length;
			for (int k = 0; k < Math.min(40000, n); k++) {
				int i = scanPos;
				scanPos = (scanPos + 1) % n;
				worldPos(p, a, p.cells[i], m);
				if (!lvl.isLoaded(m)) continue;
				setStatus(i, evaluate(p, i, lvl.getBlockState(m), a.rot));
			}
		}
		if (server.getTickCount() % 10 == 0) snapshot(server, p, a);
	}

	private static void checkAt(ServerPlayer pl, ServerLevel lvl, Plan p, Anchor a, BlockPos pos, boolean broke) {
		int cell = cellOf(p, a, pos);
		if (cell < 0) return;
		int i = p.find(cell);
		BlockState s = lvl.getBlockState(pos);
		boolean quiet = muted.contains(pl.getUUID());
		String where = pos.getX() + " " + pos.getY() + " " + pos.getZ();
		if (i < 0) {
			if (!broke && !EMPTY.contains(idOf(s)) && !quiet)
				pl.sendOverlayMessage(Component.literal("Laut Plan gehört hier kein Block hin (" + where + ")").withStyle(ChatFormatting.YELLOW));
			return;
		}
		int before = status[i];
		if (!touched.get(i)) { touched.set(i); touchedDirty = true; }
		int st = evaluate(p, i, s, a.rot);
		setStatus(i, st);
		if (quiet) return;
		if (st == WRONG) {
			pl.sendOverlayMessage(Component.literal("✗ Falsch bei " + where + ": hier gehört " + describe(p, i, a.rot) + " hin").withStyle(ChatFormatting.RED));
			lvl.playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.4f, 1.6f);
		} else if (st == CORRECT && !broke) {
			pl.sendOverlayMessage(Component.literal("✓ " + nice(p.blockId[p.pal[i]]) + " richtig · " + percent() + " fertig").withStyle(ChatFormatting.GREEN));
		} else if (broke && before == WRONG) {
			pl.sendOverlayMessage(Component.literal("Falschen Block entfernt · hier gehört " + describe(p, i, a.rot) + " hin").withStyle(ChatFormatting.GOLD));
		}
	}

	static String percent() {
		int n = status.length;
		return n == 0 ? "0 %" : String.format(java.util.Locale.GERMANY, "%.2f %%", counts[CORRECT] * 100.0 / n);
	}

	/** Was gehört an diese Stelle? */
	static Component lookup(ServerLevel lvl, BlockPos pos) {
		Plan p = plan; Anchor a = anchor;
		if (p == null) return Component.literal("Kein Plan geladen. Öffne http://localhost:8765 und sende einen Plan.").withStyle(ChatFormatting.RED);
		if (a == null) return Component.literal("Noch kein Platz festgelegt: /blockplan anker hier").withStyle(ChatFormatting.RED);
		int cell = cellOf(p, a, pos);
		if (cell < 0) return Component.literal("Diese Stelle liegt außerhalb des Plans.").withStyle(ChatFormatting.GRAY);
		int i = p.find(cell);
		if (i < 0) return Component.literal("Hier ist laut Plan Luft oder Wasser.").withStyle(ChatFormatting.GRAY);
		int st = evaluate(p, i, lvl.getBlockState(pos), a.rot);
		String txt = "Plan: " + describe(p, i, a.rot) + " · " + (st == CORRECT ? "✓ richtig" : st == WRONG ? "✗ falsch" : "fehlt noch");
		return Component.literal(txt).withStyle(st == CORRECT ? ChatFormatting.GREEN : st == WRONG ? ChatFormatting.RED : ChatFormatting.YELLOW);
	}

	static String summary() {
		Plan p = plan; Anchor a = anchor;
		if (p == null) return "Kein Plan geladen. Öffne http://localhost:8765, lade den Bau und klicke „An Minecraft senden“.";
		String s = "Plan „" + p.name + "“ (" + p.sx + "×" + p.sy + "×" + p.sz + ", " + status.length + " Blöcke). ";
		if (a == null) return s + "Noch kein Platz festgelegt: /blockplan anker hier";
		return s + "Ecke " + a.x + " " + a.y + " " + a.z + ", gedreht " + (a.rot * 90) + "°. Richtig: " + counts[CORRECT] + ", falsch: " + counts[WRONG] + ", fehlt: " + counts[MISSING] + ", nicht geladen: " + counts[UNKNOWN] + " · " + percent();
	}

	/* ---- Schnappschüsse für die Website ---- */
	private static void snapshot(MinecraftServer server, Plan p, Anchor a) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		o.addProperty("version", version);
		if (p != null) {
			JsonObject jp = new JsonObject();
			jp.addProperty("name", p.name); jp.addProperty("sx", p.sx); jp.addProperty("sy", p.sy); jp.addProperty("sz", p.sz);
			jp.addProperty("req", p.cells.length); jp.addProperty("bytes", p.raw.length);
			o.add("plan", jp);
		}
		if (a != null) {
			JsonObject ja = new JsonObject();
			ja.addProperty("x", a.x); ja.addProperty("y", a.y); ja.addProperty("z", a.z); ja.addProperty("rot", a.rot);
			o.add("anchor", ja);
		}
		JsonObject c = new JsonObject();
		c.addProperty("unknown", counts[UNKNOWN]); c.addProperty("missing", counts[MISSING]); c.addProperty("correct", counts[CORRECT]); c.addProperty("wrong", counts[WRONG]);
		o.add("counts", c);
		JsonArray pls = new JsonArray();
		for (ServerPlayer pl : server.getPlayerList().getPlayers()) {
			JsonObject j = new JsonObject();
			j.addProperty("name", pl.getName().getString());
			j.addProperty("x", pl.getX()); j.addProperty("y", pl.getY()); j.addProperty("z", pl.getZ());
			j.addProperty("yaw", pl.getYRot());
			j.addProperty("overworld", pl.level() == server.overworld());
			pls.add(j);
		}
		o.add("players", pls);
		stateJson = o.toString();
		if (p != null && a != null && statusSnapshotVersion != version && server.getTickCount() % 40 == 0) {
			Deflater d = new Deflater(1);
			d.setInput(status); d.finish();
			byte[] out = new byte[Math.max(1024, status.length / 4 + 1024)];
			java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
			while (!d.finished()) { int r = d.deflate(out); bos.write(out, 0, r); }
			d.end();
			statusSnapshot = bos.toByteArray();
			statusSnapshotVersion = version;
			JsonArray w = new JsonArray();
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			ServerLevel lvl = server.overworld();
			for (int i = 0; i < status.length && w.size() < 300; i++) {
				if (status[i] != WRONG) continue;
				worldPos(p, a, p.cells[i], m);
				JsonObject j = new JsonObject();
				j.addProperty("cell", p.cells[i]);
				j.addProperty("x", m.getX()); j.addProperty("y", m.getY()); j.addProperty("z", m.getZ());
				j.addProperty("want", describe(p, i, a.rot));
				j.addProperty("have", lvl.isLoaded(m) ? nice(idOf(lvl.getBlockState(m))) : "?");
				w.add(j);
			}
			wrongJson = w.toString();
		}
	}
}
