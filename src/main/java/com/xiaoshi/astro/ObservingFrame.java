package com.xiaoshi.astro;

/**
 * The observer's local horizontal frame, expressed in equatorial coordinates of date.
 *
 * <p>World convention (shared with the rest of the mod): +X east, +Y up, −Z north. The frame is
 * built from the local apparent sidereal time and the geographic latitude, so the same rotation
 * drives the Sun, the Moon and the whole star catalogue.
 */
public final class ObservingFrame {
	public final double latitudeDeg;
	public final double longitudeDeg;
	public final double localSiderealTimeDeg;

	private final double[] eastEquatorial;
	private final double[] upEquatorial;
	private final double[] northEquatorial;

	public ObservingFrame(double latitudeDeg, double longitudeDeg, double jdUtc) {
		this.latitudeDeg = latitudeDeg;
		this.longitudeDeg = longitudeDeg;
		double t = AstroTime.centuriesSinceJ2000(AstroTime.julianDateTt(jdUtc));
		double[] nutation = Nutation.compute(t);
		double obliquity = Nutation.trueObliquityDegrees(t);
		double apparentSidereal = AstroTime.gmstDegrees(jdUtc)
			+ nutation[0] * Math.cos(Math.toRadians(obliquity));
		this.localSiderealTimeDeg = AstroTime.mod360(apparentSidereal + longitudeDeg);

		double lst = Math.toRadians(localSiderealTimeDeg);
		double phi = Math.toRadians(latitudeDeg);
		double sinLst = Math.sin(lst);
		double cosLst = Math.cos(lst);
		double sinPhi = Math.sin(phi);
		double cosPhi = Math.cos(phi);

		// Equatorial unit vectors of the local east / up / north directions.
		this.eastEquatorial = new double[] { -sinLst, cosLst, 0.0 };
		this.upEquatorial = new double[] { cosPhi * cosLst, cosPhi * sinLst, sinPhi };
		this.northEquatorial = new double[] { -sinPhi * cosLst, -sinPhi * sinLst, cosPhi };
	}

	/** Converts an equatorial-of-date direction into a world direction (+X east, +Y up, −Z north). */
	public double[] toWorld(double raDeg, double decDeg) {
		return toWorld(Precession.equatorialUnitVector(raDeg, decDeg));
	}

	public double[] toWorld(double[] equatorial) {
		double east = dot(eastEquatorial, equatorial);
		double up = dot(upEquatorial, equatorial);
		double north = dot(northEquatorial, equatorial);
		return new double[] { east, up, -north };
	}

	/**
	 * Row-major 3×3 matrix taking J2000 equatorial unit vectors (the star catalogue) to world
	 * directions, i.e. precession to the date followed by the horizontal transform.
	 */
	public double[] starMatrix(double[] precessionJ2000ToDate) {
		double[] horizontal = new double[] {
			eastEquatorial[0], eastEquatorial[1], eastEquatorial[2],
			upEquatorial[0], upEquatorial[1], upEquatorial[2],
			-northEquatorial[0], -northEquatorial[1], -northEquatorial[2]
		};
		return Precession.multiply(horizontal, precessionJ2000ToDate);
	}

	/** Geographic (geodetic) latitude of a world Z coordinate. */
	public static double altitudeOf(double[] world) {
		return Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, world[1]))));
	}

	/** Azimuth in degrees measured from north through east, matching the mod's world axes. */
	public static double azimuthOf(double[] world) {
		return AstroTime.mod360(Math.toDegrees(Math.atan2(world[0], -world[2])));
	}

	/**
	 * Topocentric position of a body at {@code distanceKm}: returns
	 * {@code {raDeg, decDeg, distanceKm}} after subtracting the observer's geocentric position.
	 */
	public double[] topocentric(double raDeg, double decDeg, double distanceKm) {
		double phi = Math.toRadians(latitudeDeg);
		double u = Math.atan(0.99664719 * Math.tan(phi));
		double rhoSin = 0.99664719 * Math.sin(u);
		double rhoCos = Math.cos(u);
		double lst = Math.toRadians(localSiderealTimeDeg);
		double[] observer = {
			rhoCos * Math.cos(lst) * MoonPosition.EARTH_RADIUS_KM,
			rhoCos * Math.sin(lst) * MoonPosition.EARTH_RADIUS_KM,
			rhoSin * MoonPosition.EARTH_RADIUS_KM
		};
		double[] direction = Precession.equatorialUnitVector(raDeg, decDeg);
		double[] v = {
			direction[0] * distanceKm - observer[0],
			direction[1] * distanceKm - observer[1],
			direction[2] * distanceKm - observer[2]
		};
		double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		if (length < 1.0) {
			length = 1.0;
		}
		double ra = AstroTime.mod360(Math.toDegrees(Math.atan2(v[1], v[0])));
		double dec = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, v[2] / length))));
		return new double[] { ra, dec, length };
	}

	public static double angularSeparationDeg(double[] a, double[] b) {
		double dot = Math.max(-1.0, Math.min(1.0, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]));
		return Math.toDegrees(Math.acos(dot));
	}

	public static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}
}
