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
 * The bright deep-sky objects: galaxies, nebulae and clusters that are actually findable by eye or
 * with binoculars (assets/starradiance/sky/deepsky.dat).
 *
 * <p>Format: {@code NDS1} + int32 count + per object its id, display name and constellation
 * abbreviation as length-prefixed UTF-8, then an int32 type and the float32 right ascension,
 * declination, visual magnitude and major/minor axis in arc minutes (J2000).
 *
 * <p>Built by {@code tools/build_deepsky.py}, which also checks every position against the star
 * catalogue. Optional at runtime: without it the sky simply has no galaxies.
 */
public final class DeepSkyCatalog {
	public static final Identifier RESOURCE = Identifier.of("starradiance", "sky/deepsky.dat");

	public static final int GALAXY = 0;
	public static final int OPEN_CLUSTER = 1;
	public static final int GLOBULAR_CLUSTER = 2;
	public static final int NEBULA = 3;
	public static final int PLANETARY_NEBULA = 4;
	public static final int SUPERNOVA_REMNANT = 5;
	/** Translation key suffixes for the types, indexed like the constants above. */
	public static final String[] TYPE_KEYS = {
		"galaxy", "open", "globular", "nebula", "planetary", "remnant"
	};

	public final int count;
	public final String[] id;
	public final String[] name;
	public final String[] constellation;
	public final int[] type;
	public final float[] raDeg;
	public final float[] decDeg;
	public final float[] magnitude;
	public final float[] majorArcmin;
	public final float[] minorArcmin;

	private static DeepSkyCatalog cached;
	private static boolean failed;

	private DeepSkyCatalog(int count, String[] id, String[] name, String[] constellation, int[] type,
			float[] raDeg, float[] decDeg, float[] magnitude, float[] majorArcmin, float[] minorArcmin) {
		this.count = count;
		this.id = id;
		this.name = name;
		this.constellation = constellation;
		this.type = type;
		this.raDeg = raDeg;
		this.decDeg = decDeg;
		this.magnitude = magnitude;
		this.majorArcmin = majorArcmin;
		this.minorArcmin = minorArcmin;
	}

	/** Returns the table, or null when the asset is missing/corrupt or this is not the client. */
	public static DeepSkyCatalog get() {
		if (cached != null) {
			return cached;
		}
		if (failed) {
			return null;
		}
		DeepSkyCatalog loaded = load();
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

	private static DeepSkyCatalog load() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.getResourceManager() == null) {
			return null;
		}
		Optional<Resource> resource = client.getResourceManager().getResource(RESOURCE);
		if (resource.isEmpty()) {
			StarRadiance.LOGGER.warn("deepsky.dat missing; the sky will have no galaxies or nebulae");
			return null;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(resource.get().getInputStream()))) {
			byte[] magic = new byte[4];
			in.readFully(magic);
			if (magic[0] != 'N' || magic[1] != 'D' || magic[2] != 'S' || magic[3] != '1') {
				StarRadiance.LOGGER.warn("deepsky.dat has an invalid header");
				return null;
			}
			int count = in.readInt();
			if (count <= 0 || count > 100_000) {
				StarRadiance.LOGGER.warn("deepsky.dat has an unreasonable count: {}", count);
				return null;
			}
			String[] id = new String[count];
			String[] name = new String[count];
			String[] constellation = new String[count];
			int[] type = new int[count];
			float[] ra = new float[count];
			float[] dec = new float[count];
			float[] mag = new float[count];
			float[] major = new float[count];
			float[] minor = new float[count];
			for (int i = 0; i < count; i++) {
				id[i] = readString(in);
				name[i] = readString(in);
				constellation[i] = readString(in);
				type[i] = in.readInt();
				ra[i] = in.readFloat();
				dec[i] = in.readFloat();
				mag[i] = in.readFloat();
				major[i] = in.readFloat();
				minor[i] = in.readFloat();
			}
			StarRadiance.LOGGER.info("Loaded {} deep-sky objects from deepsky.dat", count);
			return new DeepSkyCatalog(count, id, name, constellation, type, ra, dec, mag, major, minor);
		} catch (IOException | RuntimeException exception) {
			StarRadiance.LOGGER.warn("Failed to read deepsky.dat", exception);
			return null;
		}
	}

	private static String readString(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0 || length > 1 << 16) {
			throw new IOException("unreasonable string length " + length);
		}
		byte[] bytes = new byte[length];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/** Index of an object by id (e.g. "M31"), or -1. */
	public int indexOf(String objectId) {
		for (int i = 0; i < count; i++) {
			if (id[i].equalsIgnoreCase(objectId)) {
				return i;
			}
		}
		return -1;
	}

	/** Colour that stands for a deep-sky type, shared by the chart symbols and their labels. */
	public static int colorOf(int type) {
		switch (type) {
			case GALAXY:
				return 0xFFE6DCC4;
			case OPEN_CLUSTER:
				return 0xFFCFE0FF;
			case GLOBULAR_CLUSTER:
				return 0xFFFFE3AE;
			case NEBULA:
				return 0xFF8FD8E0;
			case PLANETARY_NEBULA:
				return 0xFF9FE8D8;
			case SUPERNOVA_REMNANT:
				return 0xFFBFC8FF;
			default:
				return 0xFFFFFFFF;
		}
	}
}
