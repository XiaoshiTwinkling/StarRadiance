package com.xiaoshi.astro;

/**
 * Geocentric position of the Moon, after Meeus, Astronomical Algorithms chapter 47 (the truncated
 * ELP2000-82 series).
 *
 * <p>The terms kept here are the ones with amplitude ≥0.001° (longitude/latitude) and ≥10 km
 * (distance); the resulting accuracy is roughly 0.01°–0.02°, i.e. about 1% of the Moon's own disc.
 * Dropping in the full ELP2000-82B tables later only means extending the three arrays below.
 */
public final class MoonPosition {
	/** Mean lunar radius in km. */
	public static final double MOON_RADIUS_KM = 1737.4;
	/** Earth equatorial radius in km. */
	public static final double EARTH_RADIUS_KM = 6378.14;

	private static final double[][] TERMS_LONGITUDE = {
		{ 0, 0, 1, 0, 6288774, 0 },
		{ 2, 0, -1, 0, 1274027, 0 },
		{ 2, 0, 0, 0, 658314, 0 },
		{ 0, 0, 2, 0, 213618, 0 },
		{ 0, 1, 0, 0, -185116, 1 },
		{ 0, 0, 0, 2, -114332, 0 },
		{ 2, 0, -2, 0, 58793, 0 },
		{ 2, -1, -1, 0, 57066, 1 },
		{ 2, 0, 1, 0, 53322, 0 },
		{ 2, -1, 0, 0, 45758, 1 },
		{ 0, 1, -1, 0, -40923, 1 },
		{ 1, 0, 0, 0, -34720, 0 },
		{ 0, 1, 1, 0, -30383, 1 },
		{ 2, 0, 0, -2, 15327, 0 },
		{ 0, 0, 1, 2, -12528, 0 },
		{ 0, 0, 1, -2, 10980, 0 },
		{ 4, 0, -1, 0, 10675, 0 },
		{ 0, 0, 3, 0, 10034, 0 },
		{ 4, 0, -2, 0, 8548, 0 },
		{ 2, 1, -1, 0, -7888, 1 },
		{ 2, 1, 0, 0, -6766, 1 },
		{ 1, 0, -1, 0, -5163, 0 },
		{ 1, 1, 0, 0, 4987, 1 },
		{ 2, -1, 1, 0, 4036, 1 },
		{ 2, 0, 2, 0, 3994, 0 },
		{ 4, 0, 0, 0, 3861, 0 },
		{ 2, 0, -3, 0, 3665, 0 },
		{ 0, 1, -2, 0, -2689, 1 },
		{ 2, 0, -1, 2, -2602, 0 },
		{ 2, -1, -2, 0, 2390, 1 },
		{ 1, 0, 1, 0, -2348, 0 },
		{ 2, -2, 0, 0, 2236, 2 },
		{ 0, 1, 2, 0, -2120, 1 },
		{ 0, 2, 0, 0, -2069, 2 },
		{ 2, -2, -1, 0, 2048, 2 },
		{ 2, 0, 1, -2, -1773, 0 },
		{ 2, 0, 0, 2, -1595, 0 },
		{ 4, -1, -1, 0, 1215, 1 },
		{ 0, 0, 2, 2, -1110, 0 },
		{ 3, 0, -1, 0, -892, 0 },
		{ 2, 1, 1, 0, -810, 1 },
		{ 4, -1, -2, 0, 759, 1 },
		{ 0, 2, -1, 0, -713, 2 },
		{ 2, 2, -1, 0, -700, 2 },
		{ 2, 1, -2, 0, 691, 1 },
		{ 2, -1, 0, -2, 596, 1 },
		{ 4, 0, 1, 0, 549, 0 },
		{ 0, 0, 4, 0, 537, 0 },
		{ 4, -1, 0, 0, 520, 1 },
		{ 1, 0, -2, 0, -487, 0 },
		{ 2, 1, 0, -2, -399, 1 },
		{ 0, 0, 2, -2, -381, 0 },
		{ 1, 1, 1, 0, 351, 1 },
		{ 3, 0, -2, 0, -340, 0 },
		{ 4, 0, -3, 0, 330, 0 },
		{ 2, -1, 2, 0, 327, 1 },
		{ 0, 2, 1, 0, -323, 2 },
		{ 1, 1, -1, 0, 299, 1 },
		{ 2, 0, 3, 0, 294, 0 },
	};

	private static final double[][] TERMS_DISTANCE = {
		{ 0, 0, 1, 0, -20905355, 0 },
		{ 2, 0, -1, 0, -3699111, 0 },
		{ 2, 0, 0, 0, -2955968, 0 },
		{ 0, 0, 2, 0, -569925, 0 },
		{ 0, 1, 0, 0, 48888, 1 },
		{ 0, 0, 0, 2, -3149, 0 },
		{ 2, 0, -2, 0, 246158, 0 },
		{ 2, -1, -1, 0, -152138, 1 },
		{ 2, 0, 1, 0, -170733, 0 },
		{ 2, -1, 0, 0, -204586, 1 },
		{ 0, 1, -1, 0, -129620, 1 },
		{ 1, 0, 0, 0, 108743, 0 },
		{ 0, 1, 1, 0, 104755, 1 },
		{ 2, 0, 0, -2, 10321, 0 },
		{ 0, 0, 1, 2, 79661, 0 },
		{ 4, 0, -1, 0, -34782, 0 },
		{ 0, 0, 3, 0, -23210, 0 },
		{ 4, 0, -2, 0, -21636, 0 },
		{ 2, 1, -1, 0, 24208, 1 },
		{ 2, 1, 0, 0, 30824, 1 },
		{ 1, 0, -1, 0, -8379, 0 },
		{ 1, 1, 0, 0, -16675, 1 },
		{ 2, -1, 1, 0, -12831, 1 },
		{ 2, 0, 2, 0, -10445, 0 },
		{ 4, 0, 0, 0, -11650, 0 },
		{ 2, 0, -3, 0, 14403, 0 },
		{ 0, 1, -2, 0, -7003, 1 },
	};

	private static final double[][] TERMS_LATITUDE = {
		{ 0, 0, 0, 1, 5128122, 0 },
		{ 0, 0, 1, 1, 280602, 0 },
		{ 0, 0, 1, -1, 277693, 0 },
		{ 2, 0, 0, -1, 173237, 0 },
		{ 2, 0, -1, 1, 55413, 0 },
		{ 2, 0, -1, -1, 46271, 0 },
		{ 2, 0, 0, 1, 32573, 0 },
		{ 0, 0, 2, 1, 17198, 0 },
		{ 2, 0, 1, -1, 9266, 0 },
		{ 0, 0, 2, -1, 8822, 0 },
		{ 2, -1, 0, -1, 8216, 1 },
		{ 2, 0, -2, -1, 4324, 0 },
		{ 2, 0, 1, 1, 4200, 0 },
		{ 2, 1, 0, -1, -3359, 1 },
		{ 2, -1, -1, 1, 2463, 1 },
		{ 2, -1, 0, 1, 2211, 1 },
		{ 2, -1, -1, -1, 2065, 1 },
		{ 0, 1, -1, -1, -1870, 1 },
		{ 4, 0, -1, -1, 1828, 0 },
		{ 0, 1, 0, 1, -1794, 1 },
		{ 0, 0, 0, 3, -1749, 0 },
		{ 0, 1, -1, 1, -1565, 1 },
		{ 1, 0, 0, 1, -1491, 0 },
		{ 0, 1, 1, 1, -1475, 1 },
		{ 0, 1, 1, -1, -1410, 1 },
		{ 0, 1, 0, -1, -1344, 1 },
		{ 1, 0, 0, -1, -1335, 0 },
		{ 0, 0, 3, 1, 1107, 0 },
		{ 4, 0, 0, -1, 1021, 0 },
		{ 4, 0, -1, 1, 833, 0 },
	};

	public final double eclipticLongitudeDeg;
	public final double eclipticLatitudeDeg;
	public final double distanceKm;

	private MoonPosition(double eclipticLongitudeDeg, double eclipticLatitudeDeg, double distanceKm) {
		this.eclipticLongitudeDeg = eclipticLongitudeDeg;
		this.eclipticLatitudeDeg = eclipticLatitudeDeg;
		this.distanceKm = distanceKm;
	}

	public static MoonPosition compute(double t) {
		double t2 = t * t;
		double t3 = t2 * t;
		double t4 = t3 * t;

		double lPrime = AstroTime.mod360(218.3164477 + 481267.88123421 * t - 0.0015786 * t2
			+ t3 / 538841.0 - t4 / 65194000.0);
		double d = AstroTime.mod360(297.8501921 + 445267.1114034 * t - 0.0018819 * t2
			+ t3 / 545868.0 - t4 / 113065000.0);
		double m = AstroTime.mod360(357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000.0);
		double mPrime = AstroTime.mod360(134.9633964 + 477198.8675055 * t + 0.0087414 * t2
			+ t3 / 69699.0 - t4 / 14712000.0);
		double f = AstroTime.mod360(93.2720950 + 483202.0175233 * t - 0.0036539 * t2
			- t3 / 3526000.0 + t4 / 863310000.0);
		double a1 = AstroTime.mod360(119.75 + 131.849 * t);
		double a2 = AstroTime.mod360(53.09 + 479264.290 * t);
		double a3 = AstroTime.mod360(313.45 + 481266.484 * t);
		double e = 1.0 - 0.002516 * t - 0.0000074 * t2;

		double sumL = 0.0;
		double sumR = 0.0;
		double sumB = 0.0;
		for (double[] term : TERMS_LONGITUDE) {
			sumL += term[4] * eccentricityFactor(e, term[5]) * Math.sin(argument(term, d, m, mPrime, f));
		}
		for (double[] term : TERMS_DISTANCE) {
			sumR += term[4] * eccentricityFactor(e, term[5]) * Math.cos(argument(term, d, m, mPrime, f));
		}
		for (double[] term : TERMS_LATITUDE) {
			sumB += term[4] * eccentricityFactor(e, term[5]) * Math.sin(argument(term, d, m, mPrime, f));
		}

		double r = Math.toRadians(1.0);
		sumL += 3958.0 * Math.sin(Math.toRadians(a1))
			+ 1962.0 * Math.sin(Math.toRadians(lPrime - f))
			+ 318.0 * Math.sin(Math.toRadians(a2));
		sumB += -2235.0 * Math.sin(Math.toRadians(lPrime))
			+ 382.0 * Math.sin(Math.toRadians(a3))
			+ 175.0 * Math.sin(Math.toRadians(a1 - f))
			+ 175.0 * Math.sin(Math.toRadians(a1 + f))
			+ 127.0 * Math.sin(Math.toRadians(lPrime - mPrime))
			- 115.0 * Math.sin(Math.toRadians(lPrime + mPrime));

		double longitude = lPrime + sumL / 1.0E6;
		double latitude = sumB / 1.0E6;
		double distance = 385000.56 + sumR / 1000.0;
		return new MoonPosition(longitude, latitude, distance);
	}

	/** Right ascension (degrees) and declination (degrees) for the apparent ecliptic position. */
	public double[] equatorial(double trueObliquityDeg, double nutationLongitudeDeg) {
		double lambda = Math.toRadians(AstroTime.mod360(eclipticLongitudeDeg + nutationLongitudeDeg));
		double beta = Math.toRadians(eclipticLatitudeDeg);
		double eps = Math.toRadians(trueObliquityDeg);
		double sinDec = Math.sin(beta) * Math.cos(eps) + Math.cos(beta) * Math.sin(eps) * Math.sin(lambda);
		double dec = Math.asin(Math.max(-1.0, Math.min(1.0, sinDec)));
		double ra = Math.atan2(Math.sin(lambda) * Math.cos(eps) - Math.tan(beta) * Math.sin(eps),
			Math.cos(lambda));
		return new double[] { AstroTime.mod360(Math.toDegrees(ra)), Math.toDegrees(dec) };
	}

	/** Geocentric angular radius of the Moon's disc. */
	public double angularRadiusDeg() {
		return angularRadiusDeg(distanceKm);
	}

	public static double angularRadiusDeg(double distanceKm) {
		return Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, MOON_RADIUS_KM / distanceKm))));
	}

	/** Equatorial horizontal parallax of the Moon. */
	public double horizontalParallaxDeg() {
		return Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, EARTH_RADIUS_KM / distanceKm))));
	}

	private static double argument(double[] term, double d, double m, double mPrime, double f) {
		double deg = term[0] * d + term[1] * m + term[2] * mPrime + term[3] * f;
		return Math.toRadians(deg);
	}

	private static double eccentricityFactor(double e, double power) {
		if (power == 0.0) {
			return 1.0;
		}
		if (power == 1.0) {
			return e;
		}
		return e * e;
	}
}
