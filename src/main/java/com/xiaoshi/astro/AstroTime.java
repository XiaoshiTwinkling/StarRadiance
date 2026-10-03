package com.xiaoshi.astro;

/**
 * Time scales for the StarRadiance ephemeris.
 *
 * <p>Time compression: one game day (24000 ticks) is one real day, so one tick is 3.6 s of real
 * time. Everything else (Julian dates, ΔT, sidereal time) is standard astronomy.
 */
public final class AstroTime {
	/** Julian date of J2000.0 (2000-01-01 12:00 TT). */
	public static final double J2000 = 2451545.0;
	public static final double UNIX_EPOCH_JD = 2440587.5;
	public static final double TICKS_PER_DAY = 24000.0;
	public static final double MILLIS_PER_DAY = 86400000.0;

	private AstroTime() {
	}

	public static double julianDateFromEpochMillis(long epochMillis) {
		return UNIX_EPOCH_JD + epochMillis / MILLIS_PER_DAY;
	}

	/** UTC Julian date for a game tick count measured from the configured epoch instant. */
	public static double julianDateUtc(double epochJulianDateUtc, long timeOfDay) {
		return epochJulianDateUtc + timeOfDay / TICKS_PER_DAY;
	}

	/**
	 * ΔT = TT − UT1 in seconds. Espenak &amp; Meeus polynomial expressions (NASA eclipse site), which
	 * are the same ones used by most almanac software; a second of ΔT moves the sky by nothing that a
	 * player could notice, so only the modern-era ranges matter for accuracy.
	 */
	public static double deltaTSeconds(double year) {
		double u;
		if (year < -500.0) {
			u = (year - 1820.0) / 100.0;
			return -20.0 + 32.0 * u * u;
		}
		if (year < 500.0) {
			u = year / 100.0;
			return 10583.6 + u * (-1014.41 + u * (33.78311 + u * (-5.952053 + u * (-0.1798452
				+ u * (0.022174192 + u * 0.0090316521)))));
		}
		if (year < 1600.0) {
			u = (year - 1000.0) / 100.0;
			return 1574.2 + u * (-556.01 + u * (71.23472 + u * (0.319781 + u * (-0.8503463
				+ u * (-0.005050998 + u * 0.0083572073)))));
		}
		if (year < 1700.0) {
			u = year - 1600.0;
			return 120.0 + u * (-0.9808 + u * (-0.01532 + u / 7129.0));
		}
		if (year < 1800.0) {
			u = year - 1700.0;
			return 8.83 + u * (0.1603 + u * (-0.0059285 + u * (0.00013336 - u / 1174000.0)));
		}
		if (year < 1860.0) {
			u = year - 1800.0;
			return 13.72 + u * (-0.332447 + u * (0.0068612 + u * (0.0041116 + u * (-0.00037436
				+ u * (0.0000121272 + u * (-0.0000001699 + u * 0.000000000875))))));
		}
		if (year < 1900.0) {
			u = year - 1860.0;
			return 7.62 + u * (0.5737 + u * (-0.251754 + u * (0.01680668 + u * (-0.0004473624
				+ u / 233174.0))));
		}
		if (year < 1920.0) {
			u = year - 1900.0;
			return -2.79 + u * (1.494119 + u * (-0.0598939 + u * (0.0061966 - u * 0.000197)));
		}
		if (year < 1941.0) {
			u = year - 1920.0;
			return 21.20 + u * (0.84493 + u * (-0.076100 + u * 0.0020936));
		}
		if (year < 1961.0) {
			u = year - 1950.0;
			return 29.07 + u * (0.407 + u * (-1.0 / 233.0 + u / 2547.0));
		}
		if (year < 1986.0) {
			u = year - 1975.0;
			return 45.45 + u * (1.067 + u * (-1.0 / 260.0 - u / 718.0));
		}
		if (year < 2005.0) {
			u = year - 2000.0;
			return 63.86 + u * (0.3345 + u * (-0.060374 + u * (0.0017275 + u * (0.000651814
				+ u * 0.00002373599))));
		}
		if (year < 2050.0) {
			u = year - 2000.0;
			return 62.92 + u * (0.32217 + u * 0.005589);
		}
		if (year < 2150.0) {
			u = (year - 1820.0) / 100.0;
			return -20.0 + 32.0 * u * u - 0.5628 * (2150.0 - year);
		}
		u = (year - 1820.0) / 100.0;
		return -20.0 + 32.0 * u * u;
	}

	/** Decimal year (with the fraction of the year) for a UTC Julian date. */
	public static double decimalYear(double jdUtc) {
		double jd = jdUtc + 0.5;
		long z = (long) Math.floor(jd);
		double f = jd - z;
		long a = z;
		if (z >= 2299161L) {
			long alpha = (long) ((z - 1867216.25) / 36524.25);
			a = z + 1 + alpha - alpha / 4;
		}
		long b = a + 1524;
		long c = (long) ((b - 122.1) / 365.25);
		long d = (long) (365.25 * c);
		long e = (long) ((b - d) / 30.6001);
		double day = b - d - (long) (30.6001 * e) + f;
		long month = e < 14 ? e - 1 : e - 13;
		long year = month > 2 ? c - 4716 : c - 4715;
		double daysInYear = isLeap(year) ? 366.0 : 365.0;
		double dayOfYear = dayOfYear(year, month, day);
		return year + (dayOfYear - 1.0) / daysInYear;
	}

	private static boolean isLeap(long year) {
		return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
	}

	private static double dayOfYear(long year, long month, double day) {
		long[] cumulative = { 0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334 };
		long doy = cumulative[(int) (month - 1)] + (long) day;
		if (month > 2 && isLeap(year)) {
			doy++;
		}
		return doy;
	}

	public static double julianDateTt(double jdUtc) {
		return jdUtc + deltaTSeconds(decimalYear(jdUtc)) / 86400.0;
	}

	/** Julian centuries of TT since J2000.0. */
	public static double centuriesSinceJ2000(double jdTt) {
		return (jdTt - J2000) / 36525.0;
	}

	/** Greenwich mean sidereal time in degrees (Meeus, Astronomical Algorithms 12.4). */
	public static double gmstDegrees(double jdUtc) {
		double t = (jdUtc - J2000) / 36525.0;
		double gmst = 280.46061837 + 360.98564736629 * (jdUtc - J2000)
			+ 0.000387933 * t * t - t * t * t / 38710000.0;
		return mod360(gmst);
	}

	public static double mod360(double value) {
		double wrapped = value % 360.0;
		return wrapped < 0.0 ? wrapped + 360.0 : wrapped;
	}

	/** Formats a UTC Julian date as {@code YYYY-MM-DD HH:MMZ} (Meeus 7.1, inverse). */
	public static String calendarString(double jdUtc) {
		double jd = jdUtc + 0.5;
		long z = (long) Math.floor(jd);
		double fraction = jd - z;
		long a = z;
		if (z >= 2299161L) {
			long alpha = (long) ((z - 1867216.25) / 36524.25);
			a = z + 1 + alpha - alpha / 4;
		}
		long b = a + 1524;
		long c = (long) ((b - 122.1) / 365.25);
		long d = (long) (365.25 * c);
		long e = (long) ((b - d) / 30.6001);
		double dayWithFraction = b - d - (long) (30.6001 * e) + fraction;
		long day = (long) Math.floor(dayWithFraction);
		long month = e < 14 ? e - 1 : e - 13;
		long year = month > 2 ? c - 4716 : c - 4715;
		double hours = (dayWithFraction - day) * 24.0;
		long hour = (long) Math.floor(hours);
		long minute = (long) Math.floor((hours - hour) * 60.0);
		return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d %02d:%02dZ", year, month, day, hour, minute);
	}
}
