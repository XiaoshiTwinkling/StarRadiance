package com.xiaoshi.astro;

/**
 * Orthographic projection of a sphere viewed from a given direction, with the helpers needed to
 * draw a shaded globe: a day/night terminator, a latitude/longitude grid and surface markers.
 *
 * <p>Everything is in the ecliptic frame used by the rest of the mod. The screen basis is
 * {@code (right, up, forward)} with {@code forward} pointing from the body towards the viewer, so a
 * surface normal {@code n} lands at {@code (cx + R·n·right, cy − R·n·up)} and is visible when
 * {@code n·forward > 0}. Nothing here depends on Minecraft, so the verification tool can check the
 * terminator and the marker round trips.
 */
public final class SphereProjection {
	private static final double OBLIQUITY = Math.toRadians(23.4392911);

	private final double[] right;
	private final double[] up;
	private final double[] forward;

	/** @param viewDirection direction from the body's centre towards the viewer (need not be unit) */
	public SphereProjection(double[] viewDirection) {
		this(viewDirection, new double[] { 0.0, 0.0, 1.0 });
	}

	/**
	 * Full camera frame: the viewer sits along {@code viewDirection} and {@code upReference} fixes
	 * which way is up on screen.
	 *
	 * <p>Passing the ecliptic north (what the single-argument constructor does) is right for a fixed
	 * view, but a camera that has been turned must carry its own up vector: re-deriving up from the
	 * north after every turn adds a roll that grows with the turn angle, which makes a horizontal
	 * drag look like it is also twisting the globe.
	 */
	public SphereProjection(double[] viewDirection, double[] upReference) {
		this.forward = normalize(viewDirection);
		double[] projectedUp = subtract(upReference, scale(forward, dot(upReference, forward)));
		if (length(projectedUp) < 1.0E-6) {
			projectedUp = new double[] { 0.0, 0.0, 1.0 };
			projectedUp = subtract(projectedUp, scale(forward, dot(projectedUp, forward)));
			if (length(projectedUp) < 1.0E-6) {
				projectedUp = new double[] { 0.0, 1.0, 0.0 };
				projectedUp = subtract(projectedUp, scale(forward, dot(projectedUp, forward)));
			}
		}
		this.up = normalize(projectedUp);
		this.right = normalize(cross(up, forward));
	}

	/** Screen position of a surface normal, plus how much it faces the viewer (negative = far side). */
	public double[] project(double cx, double cy, double radius, double[] normal) {
		double x = cx + radius * dot(normal, right);
		double y = cy - radius * dot(normal, up);
		return new double[] { x, y, dot(normal, forward) };
	}

	/** A direction expressed in the screen basis: {@code (right, up, forward)} components. */
	public double[] inScreenBasis(double[] direction) {
		double[] unit = normalize(direction);
		return new double[] { dot(unit, right), dot(unit, up), dot(unit, forward) };
	}

	public double[] forward() {
		return forward.clone();
	}

	/** Fraction of the visible disc that is lit, from the sun's screen-basis components. */
	public static double litFraction(double[] sun) {
		return Math.max(0.0, Math.min(1.0, (1.0 + sun[2]) / 2.0));
	}

	/** Signed lit value at a disc coordinate; positive = day side. */
	public static double litAt(double dx, double dy, double[] sun) {
		double zSquared = 1.0 - dx * dx - dy * dy;
		if (zSquared < 0.0) {
			return -1.0;
		}
		return dx * sun[0] + dy * sun[1] + Math.sqrt(zSquared) * sun[2];
	}

	/**
	 * The night side's horizontal span inside the disc at basis-y {@code dy}, as
	 * {@code {x0, x1}}, or null when the whole chord is lit. Callers pass {@code w} (the chord's
	 * half width) so no square roots have to be repeated.
	 */
	public static double[] nightSpan(double dy, double w, double[] sun) {
		if (w <= 0.0) {
			return null;
		}
		// The terminator satisfies dx·sx + dy·sy + sqrt(1 - dx² - dy²)·sz = 0; sample the chord and
		// keep the widest unlit run, which is exact enough at 1/64 of the chord.
		final int samples = 64;
		double widestStart = Double.NaN;
		double widestEnd = Double.NaN;
		double runStart = Double.NaN;
		for (int i = 0; i <= samples; i++) {
			double x = -w + 2.0 * w * i / samples;
			boolean lit = litAt(x, dy, sun) > 0.0;
			if (!lit && Double.isNaN(runStart)) {
				runStart = x;
			}
			if ((lit || i == samples) && !Double.isNaN(runStart)) {
				double runEnd = lit ? x : w;
				if (Double.isNaN(widestStart) || (runEnd - runStart) > (widestEnd - widestStart)) {
					widestStart = runStart;
					widestEnd = runEnd;
				}
				runStart = Double.NaN;
			}
		}
		if (Double.isNaN(widestStart)) {
			return null;
		}
		return new double[] { widestStart, widestEnd };
	}

	/**
	 * Direction from the Earth's centre to the surface point at {@code latDeg}/{@code lonDeg} east,
	 * in ecliptic coordinates. At {@code gmstDeg} the meridian {@code lonDeg} faces right ascension
	 * {@code gmst + lon}, which is what makes the globe rotate correctly.
	 */
	public static double[] earthSurfaceDirection(double latDeg, double lonDeg, double gmstDeg) {
		// The direction from the Earth's centre to a place uses the geocentric latitude (the geodetic
		// latitude minus the flattening correction), which is also what the observer frame uses.
		double geocentric = Math.toDegrees(Math.atan(0.99664719 * Math.tan(Math.toRadians(latDeg))));
		double[] equatorial = Precession.equatorialUnitVector(AstroTime.mod360(gmstDeg + lonDeg), geocentric);
		return equatorialToEcliptic(equatorial);
	}

	/** Rotates an equatorial unit vector into the ecliptic frame. */
	public static double[] equatorialToEcliptic(double[] equatorial) {
		double cosE = Math.cos(OBLIQUITY);
		double sinE = Math.sin(OBLIQUITY);
		return new double[] {
			equatorial[0],
			equatorial[1] * cosE + equatorial[2] * sinE,
			-equatorial[1] * sinE + equatorial[2] * cosE
		};
	}

	/** Latitude of a unit direction relative to the ecliptic plane, in degrees. */
	public static double eclipticLatitudeDeg(double[] direction) {
		return Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, direction[2]))));
	}

	public static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	public static double length(double[] v) {
		return Math.sqrt(dot(v, v));
	}

	public static double[] normalize(double[] v) {
		double length = length(v);
		if (length < 1.0E-12) {
			return new double[] { 0.0, 0.0, 1.0 };
		}
		return scale(v, 1.0 / length);
	}

	public static double[] scale(double[] v, double factor) {
		return new double[] { v[0] * factor, v[1] * factor, v[2] * factor };
	}

	public static double[] subtract(double[] a, double[] b) {
		return new double[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
	}

	public static double[] cross(double[] a, double[] b) {
		return new double[] {
			a[1] * b[2] - a[2] * b[1],
			a[2] * b[0] - a[0] * b[2],
			a[0] * b[1] - a[1] * b[0]
		};
	}
}
