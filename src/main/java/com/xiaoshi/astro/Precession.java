package com.xiaoshi.astro;

/**
 * IAU 1976 precession (Meeus, Astronomical Algorithms 21.3/21.4) from J2000.0 to the equator and
 * equinox of date. The result is a row-major 3×3 matrix applied to J2000 equatorial unit vectors.
 */
public final class Precession {
	private static final double ARCSEC = 1.0 / 3600.0;

	private Precession() {
	}

	/** Row-major 3×3 rotation from J2000 equatorial coordinates to the equator of date. */
	public static double[] j2000ToDate(double t) {
		double zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * ARCSEC;
		double z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * ARCSEC;
		double theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * ARCSEC;
		// M = Rz(z) * Ry(-theta) * Rz(zeta), active rotations on column vectors.
		double[] rzZeta = rotationZ(zeta);
		double[] ryMinusTheta = rotationY(-theta);
		double[] rzZ = rotationZ(z);
		return multiply(rzZ, multiply(ryMinusTheta, rzZeta));
	}

	/**
	 * Small rotation from the mean equator of date to the true equator of date (nutation), using the
	 * standard first-order form: a rotation of −Δψ·cos ε about the pole plus −Δε about the equinox.
	 */
	public static double[] nutation(double dPsiDeg, double dEpsDeg, double trueObliquityDeg) {
		double[] rz = rotationZ(-dPsiDeg * Math.cos(Math.toRadians(trueObliquityDeg)));
		double[] rx = rotationX(-dEpsDeg);
		return multiply(rz, rx);
	}

	/** Applies a row-major 3×3 matrix to a vector. */
	public static double[] apply(double[] matrix, double x, double y, double z) {
		return new double[] {
			matrix[0] * x + matrix[1] * y + matrix[2] * z,
			matrix[3] * x + matrix[4] * y + matrix[5] * z,
			matrix[6] * x + matrix[7] * y + matrix[8] * z
		};
	}

	public static double[] multiply(double[] a, double[] b) {
		double[] out = new double[9];
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 3; col++) {
				double sum = 0.0;
				for (int k = 0; k < 3; k++) {
					sum += a[row * 3 + k] * b[k * 3 + col];
				}
				out[row * 3 + col] = sum;
			}
		}
		return out;
	}

	/** Unit vector of right ascension/declination in the equatorial frame. */
	public static double[] equatorialUnitVector(double raDeg, double decDeg) {
		double ra = Math.toRadians(raDeg);
		double dec = Math.toRadians(decDeg);
		double cosDec = Math.cos(dec);
		return new double[] { cosDec * Math.cos(ra), cosDec * Math.sin(ra), Math.sin(dec) };
	}

	private static double[] rotationZ(double angleDeg) {
		double a = Math.toRadians(angleDeg);
		double c = Math.cos(a);
		double s = Math.sin(a);
		return new double[] { c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0 };
	}

	private static double[] rotationY(double angleDeg) {
		double a = Math.toRadians(angleDeg);
		double c = Math.cos(a);
		double s = Math.sin(a);
		return new double[] { c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c };
	}

	private static double[] rotationX(double angleDeg) {
		double a = Math.toRadians(angleDeg);
		double c = Math.cos(a);
		double s = Math.sin(a);
		return new double[] { 1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c };
	}
}
