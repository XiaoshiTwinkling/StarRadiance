package com.xiaoshi.astro;

/**
 * Positions and orientations of the eight planets.
 *
 * <p>Positions come from the JPL "Keplerian Elements for Approximate Positions of the Major
 * Planets" (Standish), which is good to a few arcseconds to arcminutes over 1800-2050 — far more
 * than a diagram needs. Each planet is propagated from its mean elements with a Kepler solve, then
 * the ecliptic coordinates of J2000 are precessed into the equinox of date so they share the frame
 * of the Sun and Moon ephemerides.
 *
 * <p>Orientations use the IAU (2015) rotation model: a pole (right ascension, declination) and the
 * angle of the prime meridian with its rotation rate, so the drawn globes show the real axial tilts
 * (Uranus on its side at 98 degrees, retrograde Venus, tiny Mercury) and rotate at the real rate.
 *
 * <p>No Minecraft types: the verification tool exercises this directly.
 */
public final class PlanetPosition {
	public static final int MERCURY = 0;
	public static final int VENUS = 1;
	public static final int EARTH = 2;
	public static final int MARS = 3;
	public static final int JUPITER = 4;
	public static final int SATURN = 5;
	public static final int URANUS = 6;
	public static final int NEPTUNE = 7;
	/** Small bodies, in the same order as the planets: the dwarf planets and Halley's comet. */
	public static final int PLUTO = 8;
	public static final int CERES = 9;
	public static final int ERIS = 10;
	public static final int HAUMEA = 11;
	public static final int MAKEMAKE = 12;
	public static final int HALLEY = 13;
	public static final int COUNT = 14;

	/** Translation key suffixes, in the same order as the indices above. */
	public static final String[] KEYS = {
		"mercury", "venus", "earth", "mars", "jupiter", "saturn", "uranus", "neptune",
		"pluto", "ceres", "eris", "haumea", "makemake", "halley"
	};

	/** Semimajor axis in AU, used to scale the drawn orbits. */
	public static final double[] SEMIMAJOR_AXIS = {
		0.38709927, 0.72333566, 1.00000261, 1.52371034,
		5.20288700, 9.53667594, 19.18916464, 30.06992276,
		39.48211675, 2.76750000, 67.78000000, 43.13200000, 45.43000000, 17.83400000
	};

	/**
	 * JPL elements: a, a-dot, e, e-dot, I, I-dot, L, L-dot, longitude of perihelion and its rate,
	 * longitude of the ascending node and its rate. Angles are degrees, rates per Julian century.
	 */
	private static final double[][] ELEMENTS = {
		{ 0.38709927, 0.00000037, 0.20563593, 0.00001906, 7.00497902, -0.00594749,
		  252.25032350, 149472.67411175, 77.45779628, 0.16047689, 48.33076593, -0.12534081 },
		{ 0.72333566, 0.00000390, 0.00677672, -0.00004107, 3.39467605, -0.00078890,
		  181.97909950, 58517.81538729, 131.60246718, 0.00268329, 76.67984255, -0.27769418 },
		{ 1.00000261, 0.00000562, 0.01671123, -0.00004392, -0.00001531, -0.01294668,
		  100.46457166, 35999.37244981, 102.93768193, 0.32327364, 0.0, 0.0 },
		{ 1.52371034, 0.00001847, 0.09339410, 0.00007882, 1.84969142, -0.00813131,
		  -4.55343205, 19140.30268499, -23.94362959, 0.44441088, 49.55953891, -0.29257343 },
		{ 5.20288700, -0.00011607, 0.04838624, -0.00013253, 1.30439695, -0.00183714,
		  34.39644051, 3034.74612775, 14.72847983, 0.21252668, 100.47390909, 0.20469106 },
		{ 9.53667594, -0.00125060, 0.05386179, -0.00050991, 2.48599187, 0.00193609,
		  49.95424423, 1222.49362201, 92.59887831, -0.41897216, 113.66242448, -0.28867794 },
		{ 19.18916464, -0.00196176, 0.04725744, -0.00004397, 0.77263783, -0.00242939,
		  313.23810451, 428.48202785, 170.95427630, 0.40805281, 74.01692503, 0.04240589 },
		{ 30.06992276, 0.00026291, 0.00859048, 0.00005105, 1.77004347, 0.00035372,
		  -55.12002969, 218.45945325, 44.96476227, -0.32241464, 131.78422574, -0.00508664 },
		// Pluto: the same JPL table (it still appears there with full secular rates).
		{ 39.48211675, -0.00031596, 0.24882730, 0.00005170, 17.14001206, 0.00004818,
		  238.92903833, 145.20780515, 224.06891629, -0.04062942, 110.30393684, -0.01183482 },
		// Dwarf planets and Halley: J2000 osculating elements; only the mean longitude advances
		// quickly enough to matter over the decades this mod covers.
		{ 2.76750000, 0.0, 0.07831399, 0.0, 10.58685000, 0.0,
		  249.89120000, 7820.20000000, 153.90210000, 0.0, 80.30500000, 0.0 },
		{ 67.78000000, 0.0, 0.44177000, 0.0, 44.04000000, 0.0,
		  31.75000000, 64.51000000, 187.59000000, 0.0, 35.95100000, 0.0 },
		{ 43.13200000, 0.0, 0.19126000, 0.0, 28.19000000, 0.0,
		  191.04000000, 126.71000000, 0.94000000, 0.0, 121.90000000, 0.0 },
		{ 45.43000000, 0.0, 0.15900000, 0.0, 29.00000000, 0.0,
		  179.45000000, 117.57000000, 14.45000000, 0.0, 79.62000000, 0.0 },
		// Halley's comet at its 1986 perihelion, expressed as elements at J2000.
		{ 17.83400000, 0.0, 0.96714000, 0.0, 162.26000000, 0.0,
		  236.26000000, 477.99000000, 169.75000000, 0.0, 58.42000000, 0.0 },
	};

	/**
	 * IAU (2015) rotation: pole right ascension and declination (J2000, degrees), the prime meridian
	 * angle at J2000 and its rate in degrees per day. Negative rates are retrograde rotators.
	 */
	private static final double[][] ROTATION = {
		{ 281.0103, 61.4155, 329.5988, 6.1385108 },     // Mercury
		{ 272.7600, 67.1600, 160.2000, -1.4813688 },    // Venus
		{ 0.0000, 90.0000, 190.1470, 360.9856235 },     // Earth
		{ 317.68143, 52.88650, 176.6300, 350.89198226 },// Mars
		{ 268.056595, 64.495303, 284.9500, 870.536 },   // Jupiter (System III)
		{ 40.5890, 83.5370, 38.9000, 810.7939024 },     // Saturn
		{ 257.3110, -15.1750, 203.8100, -501.1600928 }, // Uranus
		{ 299.3600, 43.4600, 253.1800, 536.3128492 },   // Neptune
		{ 132.9930, -6.1630, 302.6950, 56.3625225 },   // Pluto
		{ 291.4180, 66.8340, 170.6500, 952.1532 },     // Ceres
		{ 249.0000, -7.0000, 0.0000, 546.8000 },       // Eris (pole approximate)
		{ 318.2000, 41.5000, 0.0000, 2206.9000 },      // Haumea (pole approximate)
		{ 79.4000, 21.0000, 0.0000, 378.5000 },        // Makemake (pole approximate)
		{ 136.0000, 25.0000, 0.0000, 163.6000 },       // Halley (tumbling nucleus, mean rate)
	};

	private static final double ARCSEC_TO_DEG = 1.0 / 3600.0;

	private PlanetPosition() {
	}

	/** Heliocentric ecliptic position in AU, referred to the mean equinox of J2000. */
	public static double[] heliocentricJ2000(int planet, double t) {
		double[] elements = evaluate(planet, t);
		double meanAnomaly = Math.toRadians(AstroTime.mod360(elements[3] - elements[4]));
		return positionFromElements(elements, meanAnomaly);
	}

	/** Heliocentric ecliptic position in AU, referred to the mean equinox of date. */
	public static double[] heliocentricAu(int planet, double t) {
		double[] position = heliocentricJ2000(planet, t);
		double precession = Math.toRadians(generalPrecessionDeg(t));
		double cos = Math.cos(precession);
		double sin = Math.sin(precession);
		return new double[] {
			position[0] * cos - position[1] * sin,
			position[0] * sin + position[1] * cos,
			position[2]
		};
	}

	/**
	 * One full revolution of a planet, as {@code samples + 1} ecliptic points in AU referred to the
	 * mean equinox of date (the last point repeats the first so the drawn ring closes).
	 */
	public static double[][] orbitAu(int planet, double t, int samples) {
		double[] elements = evaluate(planet, t);
		double precession = Math.toRadians(generalPrecessionDeg(t));
		double cos = Math.cos(precession);
		double sin = Math.sin(precession);
		double[][] points = new double[samples + 1][];
		for (int i = 0; i <= samples; i++) {
			double[] point = positionFromElements(elements, 2.0 * Math.PI * i / samples);
			points[i] = new double[] {
				point[0] * cos - point[1] * sin,
				point[0] * sin + point[1] * cos,
				point[2]
			};
		}
		return points;
	}

	/** The planet's north rotation pole as a J2000 ecliptic unit vector. */
	public static double[] poleJ2000(int planet, double daysSinceJ2000) {
		return bodyToEclipticJ2000(planet, new double[] { 0.0, 0.0, 1.0 }, daysSinceJ2000);
	}

	/**
	 * Direction from the planet's centre to the surface point at {@code latDeg}/{@code lonDeg} east,
	 * as a J2000 ecliptic unit vector. {@code daysSinceJ2000} sets the rotation phase, so a caller
	 * that advances it by 1 draws the planet turning once per real day (or its own rate).
	 */
	public static double[] surfaceDirection(int planet, double latDeg, double lonDeg, double daysSinceJ2000) {
		double lat = Math.toRadians(latDeg);
		double lon = Math.toRadians(lonDeg);
		double[] body = {
			Math.cos(lat) * Math.cos(lon),
			Math.cos(lat) * Math.sin(lon),
			Math.sin(lat)
		};
		return bodyToEclipticJ2000(planet, body, daysSinceJ2000);
	}

	/** The general precession in longitude (degrees) from J2000 to date. */
	public static double generalPrecessionDeg(double t) {
		return 5029.0966 * ARCSEC_TO_DEG * t;
	}

	/** The planet's mean distance from the Sun in AU (the semimajor axis at the epoch). */
	public static double meanDistanceAu(int planet) {
		return ELEMENTS[planet][0];
	}

	/** {a, e, I, L, longitude of perihelion, longitude of node, argument of perihelion} at time t. */
	private static double[] evaluate(int planet, double t) {
		double[] e = ELEMENTS[planet];
		double a = e[0] + e[1] * t;
		double eccentricity = e[2] + e[3] * t;
		double inclination = e[4] + e[5] * t;
		double meanLongitude = e[6] + e[7] * t;
		double perihelion = e[8] + e[9] * t;
		double node = e[10] + e[11] * t;
		return new double[] { a, eccentricity, inclination, meanLongitude, perihelion, node,
			AstroTime.mod360(perihelion - node) };
	}

	/** Position in the orbital plane for a mean anomaly, rotated into the J2000 ecliptic frame. */
	private static double[] positionFromElements(double[] elements, double meanAnomaly) {
		double a = elements[0];
		double e = elements[1];
		double inclination = Math.toRadians(elements[2]);
		double node = Math.toRadians(elements[5]);
		double argument = Math.toRadians(elements[6]);
		double eccentricAnomaly = solveKepler(meanAnomaly, e);

		double xOrbital = a * (Math.cos(eccentricAnomaly) - e);
		double yOrbital = a * Math.sqrt(Math.max(0.0, 1.0 - e * e)) * Math.sin(eccentricAnomaly);

		double cosNode = Math.cos(node);
		double sinNode = Math.sin(node);
		double cosArgument = Math.cos(argument);
		double sinArgument = Math.sin(argument);
		double cosInclination = Math.cos(inclination);
		double sinInclination = Math.sin(inclination);

		return new double[] {
			(cosArgument * cosNode - sinArgument * sinNode * cosInclination) * xOrbital
				+ (-sinArgument * cosNode - cosArgument * sinNode * cosInclination) * yOrbital,
			(cosArgument * sinNode + sinArgument * cosNode * cosInclination) * xOrbital
				+ (-sinArgument * sinNode + cosArgument * cosNode * cosInclination) * yOrbital,
			(sinArgument * sinInclination) * xOrbital + (cosArgument * sinInclination) * yOrbital
		};
	}

	/** Newton iteration on {@code E - e sin E = M}; the eccentricities here are all small. */
	private static double solveKepler(double meanAnomaly, double e) {
		double eccentricAnomaly = meanAnomaly;
		for (int i = 0; i < 32; i++) {
			double step = (eccentricAnomaly - e * Math.sin(eccentricAnomaly) - meanAnomaly)
				/ (1.0 - e * Math.cos(eccentricAnomaly));
			eccentricAnomaly -= step;
			if (Math.abs(step) < 1.0E-13) {
				break;
			}
		}
		return eccentricAnomaly;
	}

	/**
	 * Rotates a body-fixed direction into the J2000 ecliptic frame using the IAU rotation model
	 * {@code Rz(90 + alpha0) * Rx(90 - delta0) * Rz(W)}, where W is the prime meridian angle.
	 */
	private static double[] bodyToEclipticJ2000(int planet, double[] body, double daysSinceJ2000) {
		double[] rotation = ROTATION[planet];
		double w = Math.toRadians(rotation[2] + rotation[3] * daysSinceJ2000);
		double[] afterW = new double[] {
			body[0] * Math.cos(w) - body[1] * Math.sin(w),
			body[0] * Math.sin(w) + body[1] * Math.cos(w),
			body[2]
		};
		double tilt = Math.toRadians(90.0 - rotation[1]);
		double[] afterTilt = new double[] {
			afterW[0],
			afterW[1] * Math.cos(tilt) - afterW[2] * Math.sin(tilt),
			afterW[1] * Math.sin(tilt) + afterW[2] * Math.cos(tilt)
		};
		double spin = Math.toRadians(90.0 + rotation[0]);
		double[] equatorial = new double[] {
			afterTilt[0] * Math.cos(spin) - afterTilt[1] * Math.sin(spin),
			afterTilt[0] * Math.sin(spin) + afterTilt[1] * Math.cos(spin),
			afterTilt[2]
		};
		return OrbitSampler.equatorialToEcliptic(equatorial[0], equatorial[1], equatorial[2]);
	}
}
