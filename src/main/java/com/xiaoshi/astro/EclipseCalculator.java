package com.xiaoshi.astro;

/**
 * Solar and lunar eclipse geometry for one observer (Meeus, Astronomical Algorithms chapter 54).
 *
 * <p>Solar: the Moon's disc covers part of the Sun when the topocentric separation of the two discs
 * is smaller than the sum of their true angular radii; total/annular/partial follow from the radius
 * ratio, and the obscured fraction is the exact circle-overlap area.
 *
 * <p>Lunar: the Moon enters the Earth's umbra/penumbra; both radii are computed from the real
 * shadow-cone geometry at the Moon's distance (plus the traditional 2% umbra enlargement).
 */
public final class EclipseCalculator {
	public static final int NONE = 0;
	public static final int SOLAR = 1;
	public static final int LUNAR = 2;

	private static final double SUN_RADIUS_KM = 696000.0;
	private static final double AU_KM = 149597870.7;
	private static final double UMBRA_ENLARGEMENT = 1.02;

	private EclipseCalculator() {
	}

	public static final class Result {
		public final int kind;
		/** Fraction of the Sun's disc hidden (solar) or of the Moon's disc inside the umbra (lunar). */
		public final double obscuration;
		/** Meeus-style eclipse magnitude. */
		public final double magnitude;
		public final double separationDeg;
		public final double umbraRadiusDeg;
		public final double penumbraRadiusDeg;

		Result(int kind, double obscuration, double magnitude, double separationDeg,
				double umbraRadiusDeg, double penumbraRadiusDeg) {
			this.kind = kind;
			this.obscuration = obscuration;
			this.magnitude = magnitude;
			this.separationDeg = separationDeg;
			this.umbraRadiusDeg = umbraRadiusDeg;
			this.penumbraRadiusDeg = penumbraRadiusDeg;
		}
	}

	public static Result solar(double separationDeg, double sunRadiusDeg, double moonRadiusDeg) {
		double sum = sunRadiusDeg + moonRadiusDeg;
		if (separationDeg >= sum || sunRadiusDeg <= 0.0) {
			return new Result(NONE, 0.0, 0.0, separationDeg, 0.0, 0.0);
		}
		double magnitude = (sum - separationDeg) / (2.0 * sunRadiusDeg);
		double area = overlapArea(sunRadiusDeg, moonRadiusDeg, separationDeg);
		double obscuration = Math.max(0.0, Math.min(1.0, area / (Math.PI * sunRadiusDeg * sunRadiusDeg)));
		return new Result(SOLAR, obscuration, magnitude, separationDeg, 0.0, 0.0);
	}

	public static Result lunar(double separationDeg, double moonRadiusDeg, double sunDistanceAu,
			double moonDistanceKm) {
		double tanAlpha = (SUN_RADIUS_KM - MoonPosition.EARTH_RADIUS_KM)
			/ Math.max(1.0, sunDistanceAu * AU_KM);
		double umbraKm = MoonPosition.EARTH_RADIUS_KM - moonDistanceKm * tanAlpha;
		double penumbraKm = MoonPosition.EARTH_RADIUS_KM + moonDistanceKm * tanAlpha;
		double umbraDeg = Math.toDegrees(Math.atan(umbraKm / moonDistanceKm)) * UMBRA_ENLARGEMENT;
		double penumbraDeg = Math.toDegrees(Math.atan(penumbraKm / moonDistanceKm));
		if (separationDeg >= penumbraDeg + moonRadiusDeg) {
			return new Result(NONE, 0.0, 0.0, separationDeg, umbraDeg, penumbraDeg);
		}
		double magnitude = (umbraDeg + moonRadiusDeg - separationDeg) / (2.0 * moonRadiusDeg);
		double penumbralMagnitude = (penumbraDeg + moonRadiusDeg - separationDeg) / (2.0 * moonRadiusDeg);
		double area = overlapArea(moonRadiusDeg, umbraDeg, separationDeg);
		double obscuration = Math.max(0.0, Math.min(1.0, area / (Math.PI * moonRadiusDeg * moonRadiusDeg)));
		// Keep the sign of the umbral magnitude so callers can tell penumbral-only events apart.
		double reported = magnitude > 0.0 ? magnitude : penumbralMagnitude;
		return new Result(LUNAR, obscuration, reported, separationDeg, umbraDeg, penumbraDeg);
	}

	/** Area of the intersection of two circles, the standard lens formula. */
	static double overlapArea(double r1, double r2, double d) {
		if (d >= r1 + r2) {
			return 0.0;
		}
		if (d <= Math.abs(r1 - r2)) {
			double r = Math.min(r1, r2);
			return Math.PI * r * r;
		}
		double r1s = r1 * r1;
		double r2s = r2 * r2;
		double ds = d * d;
		double a1 = Math.acos(Math.max(-1.0, Math.min(1.0, (ds + r1s - r2s) / (2.0 * d * r1))));
		double a2 = Math.acos(Math.max(-1.0, Math.min(1.0, (ds + r2s - r1s) / (2.0 * d * r2))));
		double product = Math.max(0.0, (-d + r1 + r2) * (d + r1 - r2) * (d - r1 + r2) * (d + r1 + r2));
		return r1s * a1 + r2s * a2 - 0.5 * Math.sqrt(product);
	}
}
