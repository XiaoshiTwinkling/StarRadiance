package com.xiaoshi.astro;

/**
 * The star-chart screen's zenith-centred azimuthal-equidistant projection.
 *
 * <p>The zenith sits at the centre of the wheel, the horizon on the rim, north up and east right
 * (matching the mod's world axes: +X east, +Y up, -Z north). The radius of an object at altitude
 * {@code a} is {@code (90 - a) / 90 * base * zoom}, so the mapping is linear in altitude and the
 * chart can be shifted by a pixel pan and magnified without changing the shape of the sky.
 *
 * <p>The class is deliberately free of Minecraft types so the renderer, the click hit-testing and
 * the headless verification all share exactly one implementation.
 */
public final class SkyChartProjection {
	/** Altitude of the zenith, i.e. the centre of the wheel. */
	public static final double ZENITH_ALTITUDE = 90.0;

	private final double centerX;
	private final double centerY;
	private final double baseRadius;
	private final double zoom;
	private final double panX;
	private final double panY;

	public SkyChartProjection(double centerX, double centerY, double baseRadius, double zoom,
			double panX, double panY) {
		this.centerX = centerX;
		this.centerY = centerY;
		this.baseRadius = baseRadius;
		this.zoom = zoom;
		this.panX = panX;
		this.panY = panY;
	}

	/** Pixels per degree of altitude, i.e. {@code base * zoom / 90}. */
	public double scale() {
		return baseRadius * zoom / ZENITH_ALTITUDE;
	}

	public double centerX() {
		return centerX + panX;
	}

	public double centerY() {
		return centerY + panY;
	}

	/** Distance from the wheel centre, in pixels, of the circle at {@code altitudeDeg}. */
	public double radiusOf(double altitudeDeg) {
		return (ZENITH_ALTITUDE - altitudeDeg) * scale();
	}

	/** Screen position of an (altitude, azimuth) pair; azimuth is measured from north through east. */
	public double[] project(double altitudeDeg, double azimuthDeg) {
		double radius = radiusOf(altitudeDeg);
		double azimuth = Math.toRadians(azimuthDeg);
		return new double[] {
			centerX + panX + radius * Math.sin(azimuth),
			centerY + panY - radius * Math.cos(azimuth)
		};
	}

	/** Screen position of a world direction (+X east, +Y up, -Z north). */
	public double[] projectWorld(double[] world) {
		return project(ObservingFrame.altitudeOf(world), ObservingFrame.azimuthOf(world));
	}

	/** Inverse of {@link #project}: returns {@code {altitudeDeg, azimuthDeg}}. */
	public double[] unproject(double x, double y) {
		double dx = x - centerX - panX;
		double dy = y - centerY - panY;
		double radius = Math.hypot(dx, dy);
		double altitude = ZENITH_ALTITUDE - radius / scale();
		double azimuth = AstroTime.mod360(Math.toDegrees(Math.atan2(dx, -dy)));
		return new double[] { altitude, azimuth };
	}

	/** True when the point lies inside the window, with an optional pixel margin. */
	public boolean insideViewport(double x, double y, double margin, int width, int height) {
		return x >= -margin && x <= width + margin && y >= -margin && y <= height + margin;
	}
}
