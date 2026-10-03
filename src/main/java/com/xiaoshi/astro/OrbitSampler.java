package com.xiaoshi.astro;

/**
 * Samples StarRadiance's own ephemeris to build the orbit polylines for the debug diagram.
 *
 * <p>Nothing here is schematic: the Earth's heliocentric path is sampled from the Sun's apparent
 * geocentric position and the Moon's geocentric path from the ELP2000 series, so eccentricity and
 * the Moon's perturbations are all present. Only the Moon's <em>radius scale</em> is exaggerated
 * when drawing, because its real orbit is 1/389 of the Earth's.
 *
 * <p>Results are cached per game day: the polylines change slowly compared with the frame rate.
 */
public final class OrbitSampler {
	public static final int EARTH_SAMPLES = 128;
	public static final int MOON_SAMPLES = 96;
	public static final double TROPICAL_YEAR_DAYS = 365.2422;
	public static final double SIDEREAL_MONTH_DAYS = 27.321661;
	/** Astronomical unit in km, for the Moon/Earth scale comparison. */
	public static final double AU_KM = 149597870.7;

	private static long cachedEarthDay = Long.MIN_VALUE;
	private static double[][] cachedEarth;
	private static long cachedMoonDay = Long.MIN_VALUE;
	private static double[][] cachedMoon;

	private OrbitSampler() {
	}

	/** Earth's heliocentric ecliptic path in AU, sampled over ±half a tropical year. */
	public static synchronized double[][] earthHeliocentricOrbit(double jdTtCenter, long dayStamp) {
		if (cachedEarth != null && cachedEarthDay == dayStamp) {
			return cachedEarth;
		}
		// One extra point so the first and last sample coincide and the drawn ring closes.
		double[][] points = new double[EARTH_SAMPLES + 1][];
		for (int i = 0; i <= EARTH_SAMPLES; i++) {
			double offsetDays = -TROPICAL_YEAR_DAYS / 2.0 + TROPICAL_YEAR_DAYS * i / EARTH_SAMPLES;
			double t = AstroTime.centuriesSinceJ2000(jdTtCenter + offsetDays);
			SunPosition sun = SunPosition.compute(t);
			double lambda = Math.toRadians(sun.apparentLongitudeDeg + 180.0);
			points[i] = new double[] {
				sun.distanceAu * Math.cos(lambda),
				sun.distanceAu * Math.sin(lambda),
				0.0
			};
		}
		cachedEarth = points;
		cachedEarthDay = dayStamp;
		return points;
	}

	/** Moon's geocentric ecliptic path in km, sampled over ±half a sidereal month. */
	public static synchronized double[][] moonGeocentricOrbit(double jdTtCenter, long dayStamp) {
		if (cachedMoon != null && cachedMoonDay == dayStamp) {
			return cachedMoon;
		}
		double[][] points = new double[MOON_SAMPLES + 1][];
		for (int i = 0; i <= MOON_SAMPLES; i++) {
			double offsetDays = -SIDEREAL_MONTH_DAYS / 2.0 + SIDEREAL_MONTH_DAYS * i / MOON_SAMPLES;
			points[i] = eclipticKm(jdTtCenter + offsetDays);
		}
		cachedMoon = points;
		cachedMoonDay = dayStamp;
		return points;
	}

	/** Moon's geocentric ecliptic position in km at a Julian date (TT). */
	public static double[] eclipticKm(double jdTt) {
		MoonPosition moon = MoonPosition.compute(AstroTime.centuriesSinceJ2000(jdTt));
		double lambda = Math.toRadians(moon.eclipticLongitudeDeg);
		double beta = Math.toRadians(moon.eclipticLatitudeDeg);
		double distance = moon.distanceKm;
		return new double[] {
			distance * Math.cos(beta) * Math.cos(lambda),
			distance * Math.cos(beta) * Math.sin(lambda),
			distance * Math.sin(beta)
		};
	}

	/** Earth's rotation axis in ecliptic coordinates (unit vector). */
	public static double[] earthRotationAxis() {
		double obliquity = Math.toRadians(23.4392911);
		// The north celestial pole sits at ecliptic latitude 90° - eps and longitude 90°.
		return new double[] { 0.0, Math.sin(obliquity), Math.cos(obliquity) };
	}

	/** Lunar ascending node direction in ecliptic coordinates (unit vector). */
	public static double[] nodeDirection(double nodeLongitudeDeg) {
		double node = Math.toRadians(nodeLongitudeDeg);
		return new double[] { Math.cos(node), Math.sin(node), 0.0 };
	}

	/**
	 * Angle between a sampled geocentric orbit's plane and the ecliptic, in degrees. Averaged over a
	 * few consecutive samples so the reported value is the orbit's inclination, not a single
	 * perturbation wiggle.
	 */
	public static double moonInclinationDeg(double[][] orbit) {
		double sum = 0.0;
		int count = 0;
		for (int i = 0; i + 1 < orbit.length && i < 16; i++) {
			double[] a = orbit[i];
			double[] b = orbit[i + 1];
			double nx = a[1] * b[2] - a[2] * b[1];
			double ny = a[2] * b[0] - a[0] * b[2];
			double nz = a[0] * b[1] - a[1] * b[0];
			double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (length > 0.0) {
				sum += Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, Math.abs(nz) / length))));
				count++;
			}
		}
		return count == 0 ? 5.145 : sum / count;
	}

	/**
	 * Direction from the Earth's centre to the observer, in ecliptic coordinates, for the given
	 * geodetic latitude and local apparent sidereal time.
	 */
	public static double[] observerDirection(double latitudeDeg, double localSiderealTimeDeg) {
		double phi = Math.toRadians(latitudeDeg);
		double geocentric = Math.atan(0.99664719 * Math.tan(phi));
		double lst = Math.toRadians(localSiderealTimeDeg);
		double cosPhi = Math.cos(geocentric);
		double xEquatorial = cosPhi * Math.cos(lst);
		double yEquatorial = cosPhi * Math.sin(lst);
		double zEquatorial = Math.sin(geocentric);
		return equatorialToEcliptic(xEquatorial, yEquatorial, zEquatorial);
	}

	/** Surface direction of the prime meridian (longitude 0) at a given Greenwich sidereal time. */
	public static double[] primeMeridianDirection(double greenwichSiderealTimeDeg) {
		double gmst = Math.toRadians(greenwichSiderealTimeDeg);
		return equatorialToEcliptic(Math.cos(gmst), Math.sin(gmst), 0.0);
	}

	/** Rotates an equatorial unit vector into the ecliptic frame. */
	public static double[] equatorialToEcliptic(double x, double y, double z) {
		double obliquity = Math.toRadians(23.4392911);
		double cosE = Math.cos(obliquity);
		double sinE = Math.sin(obliquity);
		return new double[] { x, y * cosE + z * sinE, -y * sinE + z * cosE };
	}
}
