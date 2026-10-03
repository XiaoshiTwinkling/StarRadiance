package com.xiaoshi.astro;

/**
 * Apparent geocentric position of the Sun, after Meeus, Astronomical Algorithms chapter 25.
 *
 * <p>Accuracy is about 0.01° in longitude (the truncated VSOP87 terms used by that method), which
 * is far below one pixel of the rendered sun. {@code VSOP87D.ear} + {@code tools/build_ephemeris_tables.py}
 * can raise it to sub-arcsecond later without changing callers.
 */
public final class SunPosition {
	/** Solar semidiameter at 1 AU, degrees (959.63″). */
	public static final double SEMIDIAMETER_1AU_DEG = 959.63 / 3600.0;

	public final double trueLongitudeDeg;
	public final double apparentLongitudeDeg;
	public final double rightAscensionDeg;
	public final double declinationDeg;
	public final double distanceAu;
	public final double angularRadiusDeg;
	public final double equationOfTimeMinutes;

	private SunPosition(double trueLongitudeDeg, double apparentLongitudeDeg, double rightAscensionDeg,
			double declinationDeg, double distanceAu, double angularRadiusDeg, double equationOfTimeMinutes) {
		this.trueLongitudeDeg = trueLongitudeDeg;
		this.apparentLongitudeDeg = apparentLongitudeDeg;
		this.rightAscensionDeg = rightAscensionDeg;
		this.declinationDeg = declinationDeg;
		this.distanceAu = distanceAu;
		this.angularRadiusDeg = angularRadiusDeg;
		this.equationOfTimeMinutes = equationOfTimeMinutes;
	}

	public static SunPosition compute(double t) {
		double t2 = t * t;
		double meanLongitude = AstroTime.mod360(280.46646 + 36000.76983 * t + 0.0003032 * t2);
		double meanAnomaly = AstroTime.mod360(357.52911 + 35999.05029 * t - 0.0001537 * t2);
		double eccentricity = 0.016708634 - 0.000042037 * t - 0.0000001267 * t2;

		double m = Math.toRadians(meanAnomaly);
		double equationOfCentre = (1.914602 - 0.004817 * t - 0.000014 * t2) * Math.sin(m)
			+ (0.019993 - 0.000101 * t) * Math.sin(2.0 * m)
			+ 0.000289 * Math.sin(3.0 * m);
		double trueLongitude = meanLongitude + equationOfCentre;
		double trueAnomaly = meanAnomaly + equationOfCentre;

		double distance = 1.000001018 * (1.0 - eccentricity * eccentricity)
			/ (1.0 + eccentricity * Math.cos(Math.toRadians(trueAnomaly)));

		double omega = Math.toRadians(AstroTime.mod360(125.04 - 1934.136 * t));
		double apparentLongitude = trueLongitude - 0.00569 - 0.00478 * Math.sin(omega);
		double obliquity = Nutation.meanObliquityDegrees(t) + 0.00256 * Math.cos(omega);

		double lambda = Math.toRadians(apparentLongitude);
		double eps = Math.toRadians(obliquity);
		double rightAscension = AstroTime.mod360(Math.toDegrees(
			Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda))));
		double declination = Math.toDegrees(Math.asin(Math.sin(eps) * Math.sin(lambda)));

		// Equation of time (Meeus 28.3); the nutation term is tiny but keeps it consistent.
		double dPsiDeg = Nutation.compute(t)[0];
		double equation = meanLongitude - 0.0057183 - rightAscension + dPsiDeg * Math.cos(eps);
		equation = AstroTime.mod360(equation + 180.0) - 180.0;
		double equationMinutes = equation * 4.0;

		return new SunPosition(trueLongitude, apparentLongitude, rightAscension, declination, distance,
			SEMIDIAMETER_1AU_DEG / distance, equationMinutes);
	}
}
