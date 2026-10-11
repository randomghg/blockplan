package blockplan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Bauplan, wie ihn die Website hochlädt.
 * Format: 4 Byte Länge (big endian) + JSON-Kopf + zlib-komprimiertes Raster (uint16 little endian, Index = (y*sz+z)*sx+x).
 * Gespeichert werden nur die Zellen, die gebaut werden müssen (req = 1), sortiert nach Index.
 */
public final class Plan {
	public final String name;
	public final int sx, sy, sz;
	public final String[] blockId;
	public final Map<String, String>[] props;
	public final int[] cells;
	public final short[] pal;
	public final byte[] raw;

	private Plan(String name, int sx, int sy, int sz, String[] blockId, Map<String, String>[] props, int[] cells, short[] pal, byte[] raw) {
		this.name = name; this.sx = sx; this.sy = sy; this.sz = sz;
		this.blockId = blockId; this.props = props; this.cells = cells; this.pal = pal; this.raw = raw;
	}

	/** Blöcke, die in neueren Minecraft-Versionen anders heißen (alte Welten haben noch die alten Namen). */
	private static final Map<String, String> RENAMES = Map.of(
			"minecraft:chain", "minecraft:iron_chain",
			"minecraft:grass", "minecraft:short_grass");

	static String rename(String id) { return RENAMES.getOrDefault(id, id); }

	@SuppressWarnings("unchecked")
	public static Plan read(byte[] data) throws IOException {
		if (data.length < 8) throw new IOException("Plan-Datei ist leer");
		int hl = ByteBuffer.wrap(data, 0, 4).getInt();
		if (hl <= 0 || hl > data.length - 4) throw new IOException("Plan-Kopf ist kaputt");
		JsonObject h = JsonParser.parseString(new String(data, 4, hl, StandardCharsets.UTF_8)).getAsJsonObject();
		int sx = h.get("sx").getAsInt(), sy = h.get("sy").getAsInt(), sz = h.get("sz").getAsInt();
		String name = h.has("name") ? h.get("name").getAsString() : "Plan";
		JsonArray pa = h.getAsJsonArray("palette"), ra = h.getAsJsonArray("req");
		int n = pa.size();
		String[] ids = new String[n];
		Map<String, String>[] props = new Map[n];
		boolean[] req = new boolean[n];
		for (int i = 0; i < n; i++) {
			String s = pa.get(i).getAsString();
			int b = s.indexOf('[');
			ids[i] = rename(b < 0 ? s : s.substring(0, b));
			Map<String, String> m = new HashMap<>();
			if (b >= 0) {
				for (String kv : s.substring(b + 1, s.lastIndexOf(']')).split(",")) {
					int e = kv.indexOf('=');
					if (e > 0) m.put(kv.substring(0, e).trim(), kv.substring(e + 1).trim());
				}
			}
			props[i] = m;
			req[i] = ra != null && i < ra.size() && ra.get(i).getAsInt() == 1;
		}
		long total = (long) sx * sy * sz;
		int[] cells = new int[1 << 16];
		short[] pal = new short[1 << 16];
		int count = 0;
		try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(data, 4 + hl, data.length - 4 - hl), new Inflater(), 1 << 16)) {
			byte[] buf = new byte[1 << 16];
			long cell = 0;
			int carry = -1;
			int r;
			while ((r = in.read(buf)) > 0) {
				int i = 0;
				if (carry >= 0) {
					int v = carry | ((buf[0] & 0xff) << 8);
					if (v < n && req[v]) { if (count == cells.length) { cells = Arrays.copyOf(cells, count * 2); pal = Arrays.copyOf(pal, count * 2); } cells[count] = (int) cell; pal[count++] = (short) v; }
					cell++; carry = -1; i = 1;
				}
				for (; i + 1 < r; i += 2) {
					int v = (buf[i] & 0xff) | ((buf[i + 1] & 0xff) << 8);
					if (v < n && req[v]) { if (count == cells.length) { cells = Arrays.copyOf(cells, count * 2); pal = Arrays.copyOf(pal, count * 2); } cells[count] = (int) cell; pal[count++] = (short) v; }
					cell++;
				}
				if (i < r) carry = buf[i] & 0xff;
			}
			if (cell != total) throw new IOException("Plan-Raster hat " + cell + " statt " + total + " Zellen");
		}
		return new Plan(name, sx, sy, sz, ids, props, Arrays.copyOf(cells, count), Arrays.copyOf(pal, count), data);
	}

	/** Position in der Liste der zu bauenden Zellen, oder negativ. */
	public int find(int cell) {
		return Arrays.binarySearch(cells, cell);
	}
}
