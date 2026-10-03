package com.xiaoshi.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xiaoshi.StarRadiance;
import com.xiaoshi.astro.SkyContext;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** JSON configuration for StarRadiance, stored at config/starradiance.json. */
public final class StarRadianceConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public boolean darkerNights = true;
	public double nightDarkness = 1.0; // 0..1 extra darkness applied at night
	public double maxRenderMagnitude = 10.0; // stars brighter than this are rendered (catalog has all)
	public boolean realisticMoon = true; // textured UV sphere lit by the sun (real phases)
	public boolean normalMappedMoon = true; // LOLA-derived normal map for crater relief
	public double moonTextureQuality = 4.0; // 2 / 4 / 8 — the highest shipped resolution is 4
	public boolean eclipseEffects = true; // dim the sun during a solar eclipse, redden the moon during a lunar one
	public boolean customSkyWithShaders = true; // render our own sky while an Iris shaderpack is active
	/** How the game clock maps onto a real calendar date. */
	public enum EpochMode {
		/** Freeze the real UTC instant of world creation into the save (per world). */
		WORLD_CREATION,
		/** Use {@link #epochUtc} as the real instant of game tick 0, identical for every world. */
		FIXED_DATE
	}

	public EpochMode epochMode = EpochMode.WORLD_CREATION;
	/** ISO-8601 UTC instant used when {@link #epochMode} is FIXED_DATE, e.g. 2026-01-01T00:00:00Z. */
	public String epochUtc = "2026-01-01T00:00:00Z";
	/** Observer longitude in degrees east; latitude still comes from world Z. */
	public double longitudeDeg = 116.4;
	public SkyContext.ClockMode clockMode = SkyContext.ClockMode.LOCAL_MEAN_SOLAR;
	public boolean atmosphericRefraction = true;
	/**
	 * How strongly a risen Sun washes the Moon out. 0 keeps the Moon equally crisp day and night;
	 * 0.6 blends it noticeably into the bright sky, like the daytime Moon in reality.
	 */
	public double daytimeMoonFade = 0.6;
	/**
	 * Extra brightness applied to the moon while a shaderpack is active. Packs tone-map and bloom an
	 * HDR buffer, and their own celestial bodies sit around 3-5 in that buffer, so the real LROC
	 * albedo (max ~1) reads as a faint grey disc without a boost.
	 */
	public double shaderMoonBrightness = 4.0;
	/**
	 * Minimum fraction of the Moon's disc that stays lit. 0 = a physically dark new Moon (invisible
	 * for a couple of days each month); a small value keeps a thin crescent at all times so the Moon
	 * can be found every day.
	 */
	public double moonMinIllumination = 0.08;
	/**
	 * Apparent size of the Sun and Moon as a multiple of their true angular diameter. The real Sun
	 * and Moon are only ~0.53° across; 18x draws them about 9.5° wide, which is the size most players
	 * find readable. Eclipses stay geometrically correct because their shape comes from the real
	 * relative geometry, not from the drawn size.
	 */
	public double sunMoonScale = 18.0;

	private static StarRadianceConfig instance = new StarRadianceConfig();

	public static StarRadianceConfig get() {
		return instance;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("starradiance.json");
	}

	public static void load() {
		Path file = path();
		if (!Files.exists(file)) {
			save();
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			StarRadianceConfig loaded = GSON.fromJson(reader, StarRadianceConfig.class);
			if (loaded != null) {
				instance = loaded;
			}
		} catch (IOException | RuntimeException exception) {
			StarRadiance.LOGGER.warn("Failed to read starradiance.json; using defaults", exception);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(path().getParent());
			try (Writer writer = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException exception) {
			StarRadiance.LOGGER.warn("Failed to write starradiance.json", exception);
		}
	}
}
