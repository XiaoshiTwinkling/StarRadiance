package com.xiaoshi.astro;

/**
 * Nutation and the obliquity of the ecliptic.
 *
 * <p>Uses the short series from Meeus (Astronomical Algorithms 22.2), which is good to about 0.5″
 * in longitude — far below anything visible in the sky. The full IAU 2000B series can replace it
 * without touching any other code.
 */
public final class Nutation {
	private static final double ARCSEC = 1.0 / 3600.0;

	private Nutation() {
	}

	/** Nutation in longitude (degrees), index 0, and in obliquity (degrees), index 1. */
	public static double[] compute(double t) {
		double omega = Math.toRadians(AstroTime.mod360(125.04452 - 1934.136261 * t + 0.0020708 * t * t
			+ t * t * t / 450000.0));
		double sunLong = Math.toRadians(AstroTime.mod360(280.4665 + 36000.7698 * t));
		double moonLong = Math.toRadians(AstroTime.mod360(218.3165 + 481267.8813 * t));

		double dPsi = (-17.20 * Math.sin(omega) - 1.32 * Math.sin(2.0 * sunLong)
			- 0.23 * Math.sin(2.0 * moonLong) + 0.21 * Math.sin(2.0 * omega)) * ARCSEC;
		double dEps = (9.20 * Math.cos(omega) + 0.57 * Math.cos(2.0 * sunLong)
			+ 0.10 * Math.cos(2.0 * moonLong) - 0.09 * Math.cos(2.0 * omega)) * ARCSEC;
		return new double[] { dPsi, dEps };
	}

	/** Mean obliquity of the ecliptic in degrees (Meeus 22.2). */
	public static double meanObliquityDegrees(double t) {
		double seconds = 21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813));
		return 23.0 + (26.0 + seconds / 60.0) / 60.0;
	}

	/** True obliquity of the ecliptic in degrees. */
	public static double trueObliquityDegrees(double t) {
		return meanObliquityDegrees(t) + compute(t)[1];
	}
}
