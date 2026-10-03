package com.xiaoshi.sky;

import com.mojang.serialization.Codec;
import com.xiaoshi.StarRadiance;
import com.xiaoshi.astro.AstroTime;
import com.xiaoshi.astro.SkyContext;
import com.xiaoshi.config.StarRadianceConfig;
import java.time.Instant;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * The per-world real-time epoch: the UTC instant that game tick 0 corresponds to.
 *
 * <p>Stored as a persistent, client-synced Fabric data attachment on the world, so a save keeps its
 * own calendar and the client can compute the sky without talking to the server every frame.
 */
public final class WorldEpoch {
	public static final AttachmentType<Long> EPOCH_UTC_MILLIS = AttachmentRegistry.<Long>create(
		Identifier.of("starradiance", "epoch_utc"),
		builder -> builder
			.persistent(Codec.LONG)
			.syncWith(PacketCodecs.VAR_LONG, AttachmentSyncPredicate.all()));

	/** Milliseconds of one game tick in the real-time mapping (24000 ticks = 86400 s). */
	public static final long MILLIS_PER_TICK = 3600L;

	private WorldEpoch() {
	}

	/** Forces class initialisation so the attachment is registered. */
	public static void register() {
	}

	public static Long get(World world) {
		return world.getAttached(EPOCH_UTC_MILLIS);
	}

	/** Returns the world epoch, creating it from the current clock on first load. */
	public static long ensure(World world) {
		Long existing = get(world);
		if (existing != null) {
			return existing;
		}
		long epoch = System.currentTimeMillis() - world.getTimeOfDay() * MILLIS_PER_TICK;
		world.setAttached(EPOCH_UTC_MILLIS, epoch);
		StarRadiance.LOGGER.info("StarRadiance: world calendar starts at real UTC {}",
			Instant.ofEpochMilli(epoch));
		return epoch;
	}

	/** Sky context for a world, taking the epoch from the save when it has one. */
	public static SkyContext contextFor(World world) {
		StarRadianceConfig cfg = StarRadianceConfig.get();
		double epochJulianDate;
		Long stored = get(world);
		if (cfg.epochMode == StarRadianceConfig.EpochMode.FIXED_DATE) {
			epochJulianDate = AstroTime.julianDateFromEpochMillis(parseIsoUtc(cfg.epochUtc));
		} else if (stored != null) {
			epochJulianDate = AstroTime.julianDateFromEpochMillis(stored);
		} else if (world.isClient()) {
			// The server will sync its epoch shortly; meanwhile keep the sky where the clock is.
			long provisional = System.currentTimeMillis() - world.getTimeOfDay() * MILLIS_PER_TICK;
			epochJulianDate = AstroTime.julianDateFromEpochMillis(provisional);
		} else {
			epochJulianDate = AstroTime.julianDateFromEpochMillis(ensure(world));
		}
		epochJulianDate = SkyContext.alignEpochToGameClock(epochJulianDate);
		return new SkyContext(epochJulianDate, cfg.longitudeDeg, cfg.clockMode, cfg.atmosphericRefraction);
	}

	/**
	 * Game tick of day (0..23999) whose local mean solar time equals the real local time of the given
	 * instant. Minecraft's day cycle starts at 06:00 local time, hence the six-hour shift.
	 */
	public static long localTickOfDay(long epochMillis, double longitudeDeg, SkyContext.ClockMode mode) {
		long utcMillis = Math.floorMod(epochMillis, 86400000L);
		double hours = utcMillis / 3600000.0;
		if (mode == SkyContext.ClockMode.LOCAL_MEAN_SOLAR) {
			hours += longitudeDeg / 15.0;
		}
		double fraction = hours / 24.0 - 0.25;
		fraction -= Math.floor(fraction);
		return Math.min(Celestial.TICKS_PER_DAY - 1L, Math.round(fraction * Celestial.TICKS_PER_DAY));
	}

	private static long parseIsoUtc(String text) {
		try {
			return Instant.parse(text.trim()).toEpochMilli();
		} catch (RuntimeException exception) {
			StarRadiance.LOGGER.warn("StarRadiance: invalid epochUtc '{}', falling back to J2000", text);
			return (long) ((AstroTime.J2000 - AstroTime.UNIX_EPOCH_JD) * 86400000.0);
		}
	}
}
