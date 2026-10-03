package com.xiaoshi.sky;

import com.xiaoshi.StarRadiance;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

/**
 * The traditional constellation figures (assets/starradiance/sky/constellations.dat).
 *
 * <p>Format: {@code NWCL} + int32 id count + the IAU abbreviations + one int32 polyline count per
 * id + per polyline an int32 vertex count followed by the vertices as float32 (RA, Dec) pairs in
 * degrees, J2000. The id list matches {@link StarMeta#constellations}, so a star's constellation
 * index addresses both tables.
 *
 * <p>Derived from the d3-celestial project (BSD 3-Clause, see constellations.NOTICE.txt) by
 * {@code tools/build_constellations.py}. Optional at runtime: without it the chart just has no
 * constellation overlay.
 */
public final class ConstellationCatalog {
	public static final Identifier LINES_RESOURCE =
		Identifier.of("starradiance", "sky/constellations.dat");

	public final String[] ids;
	/** Per constellation, per polyline: {ra0, dec0, ra1, dec1, ...} in degrees (J2000). */
	public final float[][][] lines;

	private static ConstellationCatalog cached;
	private static boolean failed;

	private ConstellationCatalog(String[] ids, float[][][] lines) {
		this.ids = ids;
		this.lines = lines;
	}

	/** Returns the figures, or null when the asset is missing/corrupt or there is no client. */
	public static ConstellationCatalog get() {
		if (cached != null) {
			return cached;
		}
		if (failed) {
			return null;
		}
		ConstellationCatalog loaded = load();
		if (loaded == null) {
			failed = true;
			return null;
		}
		cached = loaded;
		return cached;
	}

	/** Drops the cached figures so the next {@link #get()} re-reads the resource. */
	public static void invalidate() {
		cached = null;
		failed = false;
	}

	private static ConstellationCatalog load() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.getResourceManager() == null) {
			return null;
		}
		Optional<Resource> resource = client.getResourceManager().getResource(LINES_RESOURCE);
		if (resource.isEmpty()) {
			StarRadiance.LOGGER.warn("constellations.dat missing; the star chart will not link stars");
			return null;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(resource.get().getInputStream()))) {
			byte[] magic = new byte[4];
			in.readFully(magic);
			if (magic[0] != 'N' || magic[1] != 'W' || magic[2] != 'C' || magic[3] != 'L') {
				StarRadiance.LOGGER.warn("constellations.dat has an invalid header");
				return null;
			}
			int idCount = in.readInt();
			if (idCount <= 0 || idCount > 4096) {
				StarRadiance.LOGGER.warn("constellations.dat has an unreasonable id count: {}", idCount);
				return null;
			}
			String[] ids = new String[idCount];
			for (int i = 0; i < idCount; i++) {
				byte[] bytes = new byte[in.readInt()];
				in.readFully(bytes);
				ids[i] = new String(bytes, StandardCharsets.UTF_8);
			}
			int[] polylineCounts = new int[idCount];
			for (int i = 0; i < idCount; i++) {
				polylineCounts[i] = in.readInt();
			}
			float[][][] lines = new float[idCount][][];
			int drawn = 0;
			for (int i = 0; i < idCount; i++) {
				lines[i] = new float[polylineCounts[i]][];
				for (int p = 0; p < polylineCounts[i]; p++) {
					int vertices = in.readInt();
					float[] points = new float[vertices * 2];
					for (int v = 0; v < vertices; v++) {
						points[v * 2] = in.readFloat();
						points[v * 2 + 1] = in.readFloat();
					}
					lines[i][p] = points;
					drawn += vertices;
				}
			}
			StarRadiance.LOGGER.info("Loaded {} constellations ({} vertices) from constellations.dat",
				idCount, drawn);
			return new ConstellationCatalog(ids, lines);
		} catch (IOException | RuntimeException exception) {
			StarRadiance.LOGGER.warn("Failed to read constellations.dat", exception);
			return null;
		}
	}

	/** Index of an IAU abbreviation, or -1. */
	public int indexOf(String id) {
		for (int i = 0; i < ids.length; i++) {
			if (ids[i].equalsIgnoreCase(id)) {
				return i;
			}
		}
		return -1;
	}
}
