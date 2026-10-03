package com.xiaoshi.astro;

/**
 * Everything the ephemeris needs besides the world clock: the per-world epoch (the real UTC instant
 * that game tick 0 corresponds to), the observer's longitude, how the game clock should be read and
 * whether atmospheric refraction is applied.
 *
 * <p>Set on world load; the sky renderer reads it through {@link #get()}.
 */
public final class SkyContext {
	public enum ClockMode {
		/** The game clock is local mean solar time at {@code longitudeDeg} (tick 6000 = local noon). */
		LOCAL_MEAN_SOLAR,
		/** The game clock is UTC. */
		UTC
	}

	private static volatile SkyContext current = new SkyContext(AstroTime.J2000, 116.4, ClockMode.LOCAL_MEAN_SOLAR, true);

	private final double epochJulianDateUtc;
	private final double longitudeDeg;
	private final ClockMode clockMode;
	private final boolean refraction;

	public SkyContext(double epochJulianDateUtc, double longitudeDeg, ClockMode clockMode, boolean refraction) {
		this.epochJulianDateUtc = epochJulianDateUtc;
		this.longitudeDeg = longitudeDeg;
		this.clockMode = clockMode;
		this.refraction = refraction;
	}

	public static SkyContext get() {
		return current;
	}

	public static void set(SkyContext context) {
		if (context != null && !context.sameAs(current)) {
			current = context;
		}
	}

	private boolean sameAs(SkyContext other) {
		return other != null
			&& Double.compare(epochJulianDateUtc, other.epochJulianDateUtc) == 0
			&& Double.compare(longitudeDeg, other.longitudeDeg) == 0
			&& clockMode == other.clockMode
			&& refraction == other.refraction;
	}

	public static SkyContext withEpoch(long epochMillis) {
		SkyContext base = current;
		return new SkyContext(AstroTime.julianDateFromEpochMillis(epochMillis), base.longitudeDeg,
			base.clockMode, base.refraction);
	}

	/**
	 * Shifts an epoch by less than a day so that the local mean solar hour at game tick 0 is 06:00,
	 * which is the Minecraft day-cycle convention (tick 0 = sunrise, 6000 = local noon, 12000 =
	 * sunset, 18000 = midnight). This is what keeps the game clock and the real ephemeris — and
	 * therefore vanilla, Iris and every shaderpack — telling the same time of day.
	 */
	public static double alignEpochToGameClock(double epochJulianDateUtc) {
		// SkyContext.julianDateUtc already subtracts the longitude, so localHour(T) is
		// 24 * frac(epoch + T/24000 + 0.5) and only the epoch's own UTC fraction has to be normalised.
		double fraction = epochJulianDateUtc + 0.5;
		fraction -= Math.floor(fraction);
		return epochJulianDateUtc - fraction + 0.25;
	}

	public double epochJulianDateUtc() {
		return epochJulianDateUtc;
	}

	public double longitudeDeg() {
		return longitudeDeg;
	}

	public ClockMode clockMode() {
		return clockMode;
	}

	public boolean refraction() {
		return refraction;
	}

	/** UTC Julian date for a game tick count measured from the epoch instant. */
	public double julianDateUtc(long timeOfDay) {
		double jd = AstroTime.julianDateUtc(epochJulianDateUtc, timeOfDay);
		if (clockMode == ClockMode.LOCAL_MEAN_SOLAR) {
			// Local mean solar time runs ahead of UTC by longitude/15 hours east of Greenwich.
			jd -= longitudeDeg / 360.0;
		}
		return jd;
	}

	/** Bennett refraction for an apparent altitude in degrees (0 outside a sensible range). */
	public static double refractionDegrees(double altitudeDeg) {
		if (altitudeDeg < -1.0 || altitudeDeg > 89.9) {
			return 0.0;
		}
		double arcMinutes = 1.02 / Math.tan(Math.toRadians(altitudeDeg + 10.3 / (altitudeDeg + 5.11)));
		return arcMinutes / 60.0;
	}
}
