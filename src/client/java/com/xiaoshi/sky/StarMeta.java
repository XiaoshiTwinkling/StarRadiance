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
 * The per-star side table for the star chart (assets/starradiance/sky/stars_meta.dat).
 *
 * <p>Format: {@code NWSM} + int32 star count + int32 constellation count + the IAU ids
 * + int32 string count + the string pool + per star five int32 fields
 * (constellation index, proper name, designation, spectral type, distance in 0.01 ly).
 * Index -1 means "not available"; the string fields index into the pool.
 *
 * <p>Built by {@code tools/build_star_catalog.py} from HYG v4.2 alongside {@code stars.dat}, with
 * one row per star in the same order, so {@link StarCatalog} indices address this table directly.
 * The file is optional: a missing or corrupt table only disables the star-chart info panel.
 */
public final class StarMeta {
	public static final Identifier META_RESOURCE = Identifier.of("starradiance", "sky/stars_meta.dat");

	public final int count;
	/** IAU constellation abbreviation per constellation index (alphabetical). */
	public final String[] constellations;
	/** Per star: index into {@link #constellations}, or -1. */
	public final int[] constellation;
	/** Per star: proper name (empty when the catalogue has none). */
	public final String[] name;
	/** Per star: Bayer/Flamsteed designation, e.g. {@code 伪 CMa}. */
	public final String[] designation;
	/** Per star: spectral type, e.g. {@code A1V}. */
	public final String[] spectral;
	/** Per star: distance in 0.01 ly, or -1 when unknown. */
	public final int[] distanceCentily;

	private static StarMeta cached;
	private static boolean failed;

	private StarMeta(int count, String[] constellations, int[] constellation, String[] name,
			String[] designation, String[] spectral, int[] distanceCentily) {
		this.count = count;
		this.constellations = constellations;
		this.constellation = constellation;
		this.name = name;
		this.designation = designation;
		this.spectral = spectral;
		this.distanceCentily = distanceCentily;
	}

	/** Returns the table, or null when the asset is missing/corrupt or this is not the client. */
	public static StarMeta get() {
		if (cached != null) {
			return cached;
		}
		if (failed) {
			return null;
		}
		StarMeta loaded = load();
		if (loaded == null) {
			failed = true;
			return null;
		}
		cached = loaded;
		return cached;
	}

	/** Drops the cached table so the next {@link #get()} re-reads the resource. */
	public static void invalidate() {
		cached = null;
		failed = false;
	}

	private static StarMeta load() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.getResourceManager() == null) {
			return null;
		}
		Optional<Resource> resource = client.getResourceManager().getResource(META_RESOURCE);
		if (resource.isEmpty()) {
			StarRadiance.LOGGER.warn("stars_meta.dat missing; the star chart will not show star data");
			return null;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(resource.get().getInputStream()))) {
			byte[] magic = new byte[4];
			in.readFully(magic);
			if (magic[0] != 'N' || magic[1] != 'W' || magic[2] != 'S' || magic[3] != 'M') {
				StarRadiance.LOGGER.warn("stars_meta.dat has an invalid header");
				return null;
			}
			int count = in.readInt();
			if (count <= 0 || count > 5_000_000) {
				StarRadiance.LOGGER.warn("stars_meta.dat has an unreasonable count: {}", count);
				return null;
			}
			String[] constellations = new String[in.readInt()];
			for (int i = 0; i < constellations.length; i++) {
				constellations[i] = readString(in);
			}
			String[] pool = new String[in.readInt()];
			for (int i = 0; i < pool.length; i++) {
				pool[i] = readString(in);
			}
			int[] constellation = new int[count];
			int[] name = new int[count];
			int[] designation = new int[count];
			int[] spectral = new int[count];
			int[] distanceCentily = new int[count];
			for (int i = 0; i < count; i++) {
				constellation[i] = in.readInt();
				name[i] = in.readInt();
				designation[i] = in.readInt();
				spectral[i] = in.readInt();
				distanceCentily[i] = in.readInt();
			}
			StarRadiance.LOGGER.info("Loaded star metadata for {} stars ({} constellations, {} strings)",
				count, constellations.length, pool.length);
			return new StarMeta(count, constellations, constellation, intern(pool, name),
				intern(pool, designation), intern(pool, spectral), distanceCentily);
		} catch (IOException | RuntimeException exception) {
			StarRadiance.LOGGER.warn("Failed to read stars_meta.dat", exception);
			return null;
		}
	}

	/** Resolves pool indices into shared strings (the pool already de-duplicates). */
	private static String[] intern(String[] pool, int[] indices) {
		String[] out = new String[indices.length];
		for (int i = 0; i < indices.length; i++) {
			int index = indices[i];
			out[i] = index >= 0 && index < pool.length ? pool[index] : "";
		}
		return out;
	}

	private static String readString(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0 || length > 1 << 20) {
			throw new IOException("unreasonable string length " + length);
		}
		byte[] bytes = new byte[length];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/** Constellation index for a star, or -1. */
	public int constellationOf(int star) {
		return star >= 0 && star < count ? constellation[star] : -1;
	}
}
