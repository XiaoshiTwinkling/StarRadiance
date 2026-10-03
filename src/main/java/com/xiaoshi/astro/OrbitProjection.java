package com.xiaoshi.astro;

/**
 * Fixed oblique orthographic projection for the debug orbit diagram.
 *
 * <p>The scene lives in ecliptic coordinates (x toward the vernal equinox, z toward the ecliptic
 * north). The camera yaws the scene about the ecliptic pole and then looks down on it from an
 * elevation, so a circle in the ecliptic plane projects to an ellipse whose minor/major ratio is
 * exactly {@code sin(elevation)}.
 */
public final class OrbitProjection {
	private final double yawRad;
	private final double elevationRad;

	public OrbitProjection(double yawDeg, double elevationDeg) {
		this.yawRad = Math.toRadians(yawDeg);
		this.elevationRad = Math.toRadians(elevationDeg);
	}

	/** Projects an ecliptic-space point to {@code {screenX, screenY, depth}}. */
	public double[] project(double x, double y, double z, double scale, double centerX, double centerY) {
		double cosYaw = Math.cos(yawRad);
		double sinYaw = Math.sin(yawRad);
		double u = x * cosYaw + y * sinYaw;
		double v = -x * sinYaw + y * cosYaw;
		double w = z;
		double sinElev = Math.sin(elevationRad);
		double cosElev = Math.cos(elevationRad);
		double screenX = centerX + u * scale;
		double screenY = centerY - (v * sinElev + w * cosElev) * scale;
		double depth = -v * cosElev + w * sinElev;
		return new double[] { screenX, screenY, depth };
	}

	public double elevationDeg() {
		return Math.toDegrees(elevationRad);
	}

	/** Scene-space direction from the origin towards the camera (for near/far side tests). */
	public double[] cameraDirection() {
		double cosElev = Math.cos(elevationRad);
		return new double[] {
			cosElev * Math.sin(yawRad),
			-cosElev * Math.cos(yawRad),
			Math.sin(elevationRad)
		};
	}
}
