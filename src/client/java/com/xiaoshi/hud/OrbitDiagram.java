package com.xiaoshi.hud;

import com.xiaoshi.astro.AstroTime;
import com.xiaoshi.astro.OrbitProjection;
import com.xiaoshi.astro.OrbitSampler;
import com.xiaoshi.astro.PlanetPosition;
import com.xiaoshi.sky.Celestial;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * The hold-K orbit diagram: Sun, Earth and Moon with their real (sampled) paths, plus the tilts and
 * rotations that make the geometry readable.
 *
 * <p>The Earth/Moon radius scale is necessarily exaggerated — the Moon's orbit is 1/389 of the
 * Earth's, so at the drawn Earth-orbit radius it would be 0.4 px. Only that radius is exaggerated;
 * the shapes, inclinations and node line all come from the ephemeris.
 */
public final class OrbitDiagram {
	private static final double YAW_DEG = 35.0;
	private static final double ELEVATION_DEG = 25.0;
	private static final double MOON_RING_AU = 0.22;
	private static final double EARTH_MARKER_AU = 0.055;
	private static final double MOON_MARKER_AU = 0.028;
	private static final double AXIS_HALF_AU = 0.065;
	private static final int GRID_CIRCLES = 4;
	private static final int GRID_SPOKES = 12;
	private static final int RING_SAMPLES = 72;
	private static final int LEGEND_LINE = 9;
	/**
	 * Samples per planet orbit. The polylines are sampled once per game day and the fill cost depends
	 * on the on-screen length rather than the sample count, so this is set for smoothness: 192 keeps
	 * the chord error well under a pixel even for Jupiter's orbit.
	 */
	private static final int PLANET_SAMPLES = 192;

	private static final int BACKGROUND = 0xC0000000;
	private static final int BORDER = 0x60FFFFFF;
	private static final int GRID = 0x24FFFFFF;
	private static final int ECLIPTIC_RING = 0x50FFFFFF;
	private static final int NODE_LINE = 0x90B0B0B0;
	private static final int EARTH_ORBIT = 0xFF7FB2D9;
	private static final int MOON_ORBIT = 0xFFD8D8D8;
	private static final int SUN = 0xFFFFE08A;
	private static final int EARTH = 0xFF4C7FE0;
	private static final int MOON = 0xFFCFCFCF;
	private static final int MARKER = 0xFFF0F0F0;
	private static final int AXIS = 0xFFE8C07A;
	private static final int LABEL = 0xFFE0E0E0;
	private static final int LABEL_DIM = 0xFF909090;

	private static final OrbitProjection PROJECTION = new OrbitProjection(YAW_DEG, ELEVATION_DEG);
	/** Alpha multiplier for the current frame: 1 = opaque, lower = see-through star map. */
	private static float frameAlpha = 1.0F;
	/**
	 * Screen rectangle every line is clipped to while the chart is drawn: {x0, y0, x1, y1}, or null
	 * outside the chart. Without it a planet's orbit, which can be tens of thousands of pixels across
	 * at the default zoom, would be filled in full even though only a short arc is on screen.
	 */
	private static int[] clipRect;
	private static final double[] clipped = new double[4];
	/** Sub-pixel epsilon so a run that ends exactly on a pixel edge excludes that pixel. */
	private static final double EDGE = 1.0E-9;
	/** The planet orbit polylines barely change within a day, so they are sampled once per game day. */
	private static long cachedPlanetDay = Long.MIN_VALUE;
	private static double[][][] cachedPlanetOrbits;

	private OrbitDiagram() {
	}

	/**
	 * Draws the orbit chart.
	 *
	 * @param opacity   cross-fade factor used while zooming into the globe view (1 = fully drawn)
	 * @param zoom      chart magnification from the mouse wheel (1 = fit the Earth's orbit)
	 * @param panX      horizontal chart offset in pixels, from dragging
	 * @param panY      vertical chart offset in pixels, from dragging
	 * @param highlight body index to ring (the one being zoomed into), or -1
	 * @return every drawn body's screen circle as {@code {x, y, radius, planetIndex}},
	 *         {@code planetIndex} indexing {@link com.xiaoshi.astro.PlanetPosition}
	 */
	public static double[][] render(DrawContext context, MinecraftClient client, Celestial.SkyState state,
			int x, int y, int width, int height, boolean transparent, double opacity,
			double zoom, double panX, double panY, int highlight) {
		if (width < 110 || height < 70) {
			return null;
		}
		frameAlpha = (float) ((transparent ? 0.30F : 1.0F) * Math.max(0.0, Math.min(1.0, opacity)));
		TextRenderer font = client.textRenderer;
		// In see-through mode the backdrop disappears entirely and only the chart itself stays.
		if (!transparent) {
			context.fill(x, y, x + width, y + height, BACKGROUND);
		}
		context.drawBorder(x, y, x + width, y + height, fade(BORDER, transparent ? 0.35F : 1.0F));
		context.enableScissor(x, y, x + width, y + height);
		clipRect = new int[] { x, y, x + width, y + height };

		double jdTt = AstroTime.julianDateTt(state.julianDateUtc);
		double[][] earthOrbit = OrbitSampler.earthHeliocentricOrbit(jdTt, state.day);
		double[][] moonOrbit = OrbitSampler.moonGeocentricOrbit(jdTt, state.day);

		double earthLambda = Math.toRadians(state.sunApparentLongitudeDeg + 180.0);
		double[] earth = {
			state.sunDistanceAu * Math.cos(earthLambda),
			state.sunDistanceAu * Math.sin(earthLambda),
			0.0
		};
		double[] moonKm = OrbitSampler.eclipticKm(jdTt);
		double moonDistance = length(moonKm);
		double meanMoonDistance = meanRadius(moonOrbit);
		double exaggeration = MOON_RING_AU / (meanMoonDistance / OrbitSampler.AU_KM);
		scaleVector(moonKm, MOON_RING_AU / meanMoonDistance);
		double[] moon = { earth[0] + moonKm[0], earth[1] + moonKm[1], earth[2] + moonKm[2] };

		// Fit the whole drawing (Earth orbit plus the exaggerated lunar ring's footprint) to the
		// screen: project at unit scale, take the bounding box, then scale it to fill the panel.
		double[] bounds = projectedBounds(earthOrbit, earth, MOON_RING_AU);
		double margin = 12.0;
		double scale = Math.min((width - 2.0 * margin) / Math.max(1.0E-6, bounds[2] - bounds[0]),
			(height - 2.0 * margin) / Math.max(1.0E-6, bounds[3] - bounds[1]));
		double centerX = x + width / 2.0 - (bounds[0] + bounds[2]) / 2.0 * scale;
		double centerY = y + height / 2.0 - (bounds[1] + bounds[3]) / 2.0 * scale;
		scale *= zoom;
		centerX += panX;
		centerY += panY;

		// Ecliptic reference grid.
		for (int ring = 1; ring <= GRID_CIRCLES; ring++) {
			double radius = ring / (double) GRID_CIRCLES;
			drawRing(context, new double[] { 0.0, 0.0, 0.0 }, radius, GRID, scale, centerX, centerY, false);
		}
		for (int spoke = 0; spoke < GRID_SPOKES; spoke++) {
			double angle = 2.0 * Math.PI * spoke / GRID_SPOKES;
			line(context,
				project(Math.cos(angle) * 1.02, Math.sin(angle) * 1.02, 0.0, scale, centerX, centerY),
				project(0.0, 0.0, 0.0, scale, centerX, centerY), GRID);
		}

		// The other seven planets: real heliocentric orbits and current positions.
		double[][] hits = new double[PlanetPosition.COUNT][];
		int hitCount = 0;
		double centuries = AstroTime.centuriesSinceJ2000(jdTt);
		for (int planet = 0; planet < PlanetPosition.COUNT; planet++) {
			if (planet == PlanetPosition.EARTH) {
				continue;
			}
			double[][] orbit = planetOrbits(centuries, state.day)[planet];
			double[] position = PlanetPosition.heliocentricAu(planet, centuries);
			double[] screen = project(position[0], position[1], position[2], scale, centerX, centerY);
			if (!drawPolylineClipped(context, orbit, scale, centerX, centerY,
					dim(PlanetView.DAY_COLOR[planet], 0.65F), x, y, width, height)) {
				continue;
			}
			int color = PlanetView.DAY_COLOR[planet];
			if (planet == highlight) {
				ring(context, screen[0], screen[1], 8.0, color);
			}
			dot(context, screen, planet == PlanetPosition.JUPITER || planet == PlanetPosition.SATURN
				? 3 : 2, color);
			if (screen[0] > x && screen[0] < x + width && screen[1] > y && screen[1] < y + height) {
				label(context, font, "hud.starradiance.planet." + PlanetPosition.KEYS[planet],
					screen, 4, -10, LABEL);
			}
			hits[hitCount++] = new double[] { screen[0], screen[1], 8.0, planet };
		}

		// Earth's real heliocentric path.
		drawPolyline(context, earthOrbit, scale, centerX, centerY, EARTH_ORBIT);

		// Moon's real geocentric path, radius-scaled around the Earth.
		double[][] moonDisplay = new double[moonOrbit.length][];
		for (int i = 0; i < moonOrbit.length; i++) {
			double radius = length(moonOrbit[i]);
			double factor = radius > 1.0 ? MOON_RING_AU * radius / meanMoonDistance : 0.0;
			double inverse = radius > 1.0 ? factor / radius : 0.0;
			moonDisplay[i] = new double[] {
				earth[0] + moonOrbit[i][0] * inverse,
				earth[1] + moonOrbit[i][1] * inverse,
				earth[2] + moonOrbit[i][2] * inverse
			};
		}
		drawPolyline(context, moonDisplay, scale, centerX, centerY, MOON_ORBIT);

		// Ecliptic reference ring around the Earth and the node line between the two planes.
		drawRing(context, earth, MOON_RING_AU, ECLIPTIC_RING, scale, centerX, centerY, true);
		double[] node = OrbitSampler.nodeDirection(state.moonNodeDeg);
		dashedLine(context,
			project(earth[0] - node[0] * MOON_RING_AU, earth[1] - node[1] * MOON_RING_AU, earth[2], scale, centerX, centerY),
			project(earth[0] + node[0] * MOON_RING_AU, earth[1] + node[1] * MOON_RING_AU, earth[2], scale, centerX, centerY),
			NODE_LINE);

		// Earth's axis of rotation (23.4° from the ecliptic normal).
		double[] axis = OrbitSampler.earthRotationAxis();
		line(context,
			project(earth[0] - axis[0] * AXIS_HALF_AU, earth[1] - axis[1] * AXIS_HALF_AU, earth[2] - axis[2] * AXIS_HALF_AU, scale, centerX, centerY),
			project(earth[0] + axis[0] * AXIS_HALF_AU, earth[1] + axis[1] * AXIS_HALF_AU, earth[2] + axis[2] * AXIS_HALF_AU, scale, centerX, centerY),
			AXIS);

		// Rotation marks: the prime meridian (Greenwich sidereal time) and the observer.
		double gmst = AstroTime.mod360(state.localSiderealTimeDeg - state.longitudeDeg);
		drawSurfaceMarker(context, earth, OrbitSampler.primeMeridianDirection(gmst), EARTH_MARKER_AU,
			scale, centerX, centerY, MARKER, 0.5);
		double[] observer = OrbitSampler.observerDirection(state.latitudeDeg, state.localSiderealTimeDeg);
		drawSurfaceMarker(context, earth, observer, EARTH_MARKER_AU, scale, centerX, centerY, SUN, 0.85);

		// Bodies.
		double[] sunScreen = project(0.0, 0.0, 0.0, scale, centerX, centerY);
		double[] earthScreen = project(earth[0], earth[1], earth[2], scale, centerX, centerY);
		double[] moonScreen = project(moon[0], moon[1], moon[2], scale, centerX, centerY);
		dot(context, sunScreen, 2, SUN);
		dot(context, earthScreen, 3, EARTH);
		dot(context, moonScreen, 2, MOON);
		double[] nearSide = normalize(new double[] { earth[0] - moon[0], earth[1] - moon[1], earth[2] - moon[2] });
		drawSurfaceMarker(context, moon, nearSide, MOON_MARKER_AU, scale, centerX, centerY, MARKER, 0.7);

		// Labels.
		label(context, font, "hud.starradiance.diagram.sun", sunScreen, 4, -12, LABEL);
		label(context, font, "hud.starradiance.diagram.earth", earthScreen, 5, -5, LABEL);
		label(context, font, "hud.starradiance.diagram.moon", moonScreen, 5, 1, LABEL);
		label(context, font, "hud.starradiance.diagram.observer", project(
			earth[0] + observer[0] * EARTH_MARKER_AU, earth[1] + observer[1] * EARTH_MARKER_AU,
			earth[2] + observer[2] * EARTH_MARKER_AU, scale, centerX, centerY), 4, -3, SUN);
		// Legend in the lower-left, so the translated data block can live in the upper-left corner.
		// Kept clear of the controls hint the screen draws on the very last text line.
		int legendY = y + height - 17 - LEGEND_LINE * 6;
		drawText(context, font, x + 6, legendY, LABEL_DIM,
			Text.translatable("hud.starradiance.diagram.ecliptic").getString());
		drawText(context, font, x + 6, legendY + LEGEND_LINE, LABEL,
			Text.translatable("hud.starradiance.diagram.moonOrbit",
				String.format(Locale.ROOT, "%.0f", exaggeration)).getString());
		drawText(context, font, x + 6, legendY + LEGEND_LINE * 2, LABEL_DIM,
			Text.translatable("hud.starradiance.diagram.inclination",
				String.format(Locale.ROOT, "%.1f", OrbitSampler.moonInclinationDeg(moonOrbit))).getString());
		drawText(context, font, x + 6, legendY + LEGEND_LINE * 3, LABEL_DIM,
			Text.translatable("hud.starradiance.diagram.axis",
				String.format(Locale.ROOT, "%.1f", 23.4)).getString());
		drawText(context, font, x + 6, legendY + LEGEND_LINE * 4, LABEL,
			Text.translatable("hud.starradiance.diagram.zoom",
				String.format(Locale.ROOT, "%.2f", zoom)).getString());
		drawText(context, font, x + 6, legendY + LEGEND_LINE * 5, LABEL_DIM,
			Text.translatable("hud.starradiance.diagram.hint").getString());

		context.disableScissor();
		clipRect = null;
		hits[hitCount++] = new double[] { earthScreen[0], earthScreen[1],
			Math.max(12.0, EARTH_MARKER_AU * scale), PlanetPosition.EARTH };
		double[][] out = new double[hitCount][];
		System.arraycopy(hits, 0, out, 0, hitCount);
		return out;
	}

	/** Draws a dotted circle, used for the hover highlight around the Earth. */
	public static void ring(DrawContext context, double cx, double cy, double radius, int color) {
		int samples = Math.max(24, (int) (radius * 3.0));
		double previousX = cx + radius;
		double previousY = cy;
		for (int i = 1; i <= samples; i++) {
			double angle = 2.0 * Math.PI * i / samples;
			double x = cx + Math.cos(angle) * radius;
			double y = cy + Math.sin(angle) * radius;
			line(context, new double[] { previousX, previousY, 0.0 }, new double[] { x, y, 0.0 }, color);
			previousX = x;
			previousY = y;
		}
	}

	/**
	 * The seven non-Earth orbits, sampled once per game day. A planet's yearly path is a closed
	 * curve that is independent of where on it the planet currently sits, so one sample per day is
	 * indistinguishable from resampling every frame.
	 */
	private static double[][][] planetOrbits(double centuries, long day) {
		if (cachedPlanetOrbits != null && cachedPlanetDay == day) {
			return cachedPlanetOrbits;
		}
		double[][][] orbits = new double[PlanetPosition.COUNT][][];
		for (int planet = 0; planet < PlanetPosition.COUNT; planet++) {
			orbits[planet] = PlanetPosition.orbitAu(planet, centuries, PLANET_SAMPLES);
		}
		cachedPlanetOrbits = orbits;
		cachedPlanetDay = day;
		return orbits;
	}

	private static void drawPolyline(DrawContext context, double[][] points, double scale, double centerX,
			double centerY, int color) {
		for (int i = 0; i + 1 < points.length; i++) {
			line(context,
				project(points[i][0], points[i][1], points[i][2], scale, centerX, centerY),
				project(points[i + 1][0], points[i + 1][1], points[i + 1][2], scale, centerX, centerY),
				fade(color, frameAlpha));
		}
	}

	/**
	 * Draws a polyline only when some part of it lands inside the panel. The viewport test matters
	 * because at the default zoom the outer planets are far outside the chart and would otherwise
	 * cost thousands of clipped pixel fills every frame.
	 */
	private static boolean drawPolylineClipped(DrawContext context, double[][] points, double scale,
			double centerX, double centerY, int color, int x, int y, int width, int height) {
		double minX = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (double[] point : points) {
			double[] screen = project(point[0], point[1], point[2], scale, centerX, centerY);
			minX = Math.min(minX, screen[0]);
			maxX = Math.max(maxX, screen[0]);
			minY = Math.min(minY, screen[1]);
			maxY = Math.max(maxY, screen[1]);
		}
		if (maxX < x || minX > x + width || maxY < y || minY > y + height) {
			return false;
		}
		drawPolyline(context, points, scale, centerX, centerY, color);
		return true;
	}

	private static void drawRing(DrawContext context, double[] center, double radius, int color,
			double scale, double centerX, double centerY, boolean dashed) {
		double previousX = 0.0;
		double previousY = 0.0;
		double previousZ = 0.0;
		for (int i = 0; i <= RING_SAMPLES; i++) {
			double angle = 2.0 * Math.PI * i / RING_SAMPLES;
			double x = center[0] + Math.cos(angle) * radius;
			double y = center[1] + Math.sin(angle) * radius;
			double z = center[2];
			if (i > 0) {
				double[] from = project(previousX, previousY, previousZ, scale, centerX, centerY);
				double[] to = project(x, y, z, scale, centerX, centerY);
				if (dashed && (i % 4 == 0)) {
					line(context, from, to, fade(color, frameAlpha));
				} else if (!dashed) {
					line(context, from, to, fade(color, frameAlpha));
				}
			}
			previousX = x;
			previousY = y;
			previousZ = z;
		}
	}

	private static void drawSurfaceMarker(DrawContext context, double[] body, double[] direction,
			double radius, double scale, double centerX, double centerY, int color, double visibility) {
		double[] camera = PROJECTION.cameraDirection();
		double facing = direction[0] * camera[0] + direction[1] * camera[1] + direction[2] * camera[2];
		int shaded = fade(facing >= 0.0 ? color : dim(color, 0.4F), frameAlpha);
		double[] from = project(body[0], body[1], body[2], scale, centerX, centerY);
		double[] to = project(body[0] + direction[0] * radius, body[1] + direction[1] * radius,
			body[2] + direction[2] * radius, scale, centerX, centerY);
		if (visibility >= 0.8) {
			line(context, from, to, shaded);
		}
		dot(context, to, 1, shaded);
	}

	private static double[] project(double x, double y, double z, double scale, double centerX, double centerY) {
		return PROJECTION.project(x, y, z, scale, centerX, centerY);
	}

	/**
	 * Draws a one-pixel line as a run of pixels per scan line instead of one quad per pixel. The
	 * pixel-per-quad version cost 1000+ draws for a single long grid spoke and made dragging the
	 * chart stutter; this fills one rectangle per row (or per column for steep lines), which is a
	 * couple of dozen draws for the whole chart.
	 */
	public static void line(DrawContext context, double[] from, double[] to, int color) {
		double x0 = from[0];
		double y0 = from[1];
		double x1 = to[0];
		double y1 = to[1];
		if (clipRect != null) {
			if (!clip(x0, y0, x1, y1)) {
				return;
			}
			x0 = clipped[0];
			y0 = clipped[1];
			x1 = clipped[2];
			y1 = clipped[3];
		}
		double dx = x1 - x0;
		double dy = y1 - y0;
		// Each run is the half-open pixel interval [ceil(low), ceil(high)), which is exactly the set
		// of pixels the old per-pixel Bresenham walk covered: ceil for both ends, because the run
		// spans [centre - 0.5, centre + 0.5). Using floor/ceil instead would add one extra pixel to
		// every run and visibly thicken every diagonal.
		if (Math.abs(dy) < EDGE) {
			int y = (int) Math.round(y0);
			int left = (int) Math.ceil(Math.min(x0, x1) - EDGE);
			int right = (int) Math.ceil(Math.max(x0, x1) - EDGE);
			context.fill(left, y, Math.max(left + 1, right), y + 1, color);
			return;
		}
		if (Math.abs(dx) < EDGE) {
			int x = (int) Math.round(x0);
			int top = (int) Math.ceil(Math.min(y0, y1) - EDGE);
			int bottom = (int) Math.ceil(Math.max(y0, y1) - EDGE);
			context.fill(x, top, x + 1, Math.max(top + 1, bottom), color);
			return;
		}
		if (Math.abs(dx) >= Math.abs(dy)) {
			int first = (int) Math.round(Math.min(y0, y1));
			int last = (int) Math.round(Math.max(y0, y1));
			for (int y = first; y <= last; y++) {
				double enter = (y - 0.5 - y0) / dy;
				double exit = (y + 0.5 - y0) / dy;
				double low = Math.max(0.0, Math.min(enter, exit));
				double high = Math.min(1.0, Math.max(enter, exit));
				if (high < low) {
					continue;
				}
				double a = x0 + dx * low;
				double b = x0 + dx * high;
				int left = (int) Math.ceil(Math.min(a, b) - EDGE);
				int right = (int) Math.ceil(Math.max(a, b) - EDGE);
				context.fill(left, y, Math.max(left + 1, right), y + 1, color);
			}
			return;
		}
		int first = (int) Math.round(Math.min(x0, x1));
		int last = (int) Math.round(Math.max(x0, x1));
		for (int x = first; x <= last; x++) {
			double enter = (x - 0.5 - x0) / dx;
			double exit = (x + 0.5 - x0) / dx;
			double low = Math.max(0.0, Math.min(enter, exit));
			double high = Math.min(1.0, Math.max(enter, exit));
			if (high < low) {
				continue;
			}
			double a = y0 + dy * low;
			double b = y0 + dy * high;
			int top = (int) Math.ceil(Math.min(a, b) - EDGE);
			int bottom = (int) Math.ceil(Math.max(a, b) - EDGE);
			context.fill(x, top, x + 1, Math.max(top + 1, bottom), color);
		}
	}

	/**
	 * Liang-Barsky clip of a screen-space segment against {@link #clipRect}, leaving the visible part
	 * in {@link #clipped}. Returns false when the segment misses the rectangle entirely.
	 */
	private static boolean clip(double x0, double y0, double x1, double y1) {
		double dx = x1 - x0;
		double dy = y1 - y0;
		double enter = 0.0;
		double exit = 1.0;
		double[] edge = { -dx, dx, -dy, dy };
		double[] distance = {
			x0 - clipRect[0], clipRect[2] - x0,
			y0 - clipRect[1], clipRect[3] - y0
		};
		for (int i = 0; i < 4; i++) {
			if (edge[i] == 0.0) {
				if (distance[i] < 0.0) {
					return false;
				}
				continue;
			}
			double t = distance[i] / edge[i];
			if (edge[i] < 0.0) {
				if (t > exit) {
					return false;
				}
				if (t > enter) {
					enter = t;
				}
			} else {
				if (t < enter) {
					return false;
				}
				if (t < exit) {
					exit = t;
				}
			}
		}
		clipped[0] = x0 + enter * dx;
		clipped[1] = y0 + enter * dy;
		clipped[2] = x0 + exit * dx;
		clipped[3] = y0 + exit * dy;
		return true;
	}

	public static void dashedLine(DrawContext context, double[] from, double[] to, int color) {
		double dx = to[0] - from[0];
		double dy = to[1] - from[1];
		int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
		if (steps <= 0) {
			return;
		}
		for (int i = 0; i <= steps; i++) {
			if ((i / 3) % 2 != 0) {
				continue;
			}
			int px = (int) Math.round(from[0] + dx * i / steps);
			int py = (int) Math.round(from[1] + dy * i / steps);
			context.fill(px, py, px + 1, py + 1, color);
		}
	}

	public static void dot(DrawContext context, double[] point, int radius, int color) {
		int cx = (int) Math.round(point[0]);
		int cy = (int) Math.round(point[1]);
		context.fill(cx - radius, cy - radius, cx + radius + 1, cy + radius + 1, color);
	}

	private static void label(DrawContext context, TextRenderer font, String key, double[] point,
			int offsetX, int offsetY, int color) {
		drawText(context, font, (int) Math.round(point[0]) + offsetX, (int) Math.round(point[1]) + offsetY,
			color, Text.translatable(key).getString());
	}

	/** Draws an already-translated label at a fractional position (used by the zoomed globe view). */
	public static void drawLabel(DrawContext context, TextRenderer font, String text, double x, double y, int color) {
		drawText(context, font, (int) Math.round(x), (int) Math.round(y), color, text);
	}

	private static void drawText(DrawContext context, TextRenderer font, int x, int y, int color, String text) {
		context.drawText(font, text, x, y, color, true);
	}

	/** Bounding box of the drawn content, projected at unit scale around the origin. */
	private static double[] projectedBounds(double[][] earthOrbit, double[] earth, double ringRadius) {
		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (double[] point : earthOrbit) {
			double[] screen = PROJECTION.project(point[0], point[1], point[2], 1.0, 0.0, 0.0);
			minX = Math.min(minX, screen[0]);
			maxX = Math.max(maxX, screen[0]);
			minY = Math.min(minY, screen[1]);
			maxY = Math.max(maxY, screen[1]);
		}
		for (int i = 0; i < 16; i++) {
			double angle = 2.0 * Math.PI * i / 16.0;
			double[] screen = PROJECTION.project(earth[0] + Math.cos(angle) * ringRadius,
				earth[1] + Math.sin(angle) * ringRadius, earth[2], 1.0, 0.0, 0.0);
			minX = Math.min(minX, screen[0]);
			maxX = Math.max(maxX, screen[0]);
			minY = Math.min(minY, screen[1]);
			maxY = Math.max(maxY, screen[1]);
		}
		return new double[] { minX, minY, maxX, maxY };
	}

	private static int dim(int color, float factor) {
		int alpha = (color >>> 24) & 0xFF;
		int red = (int) (((color >>> 16) & 0xFF) * factor);
		int green = (int) (((color >>> 8) & 0xFF) * factor);
		int blue = (int) ((color & 0xFF) * factor);
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	/** Scales a colour's alpha channel (1 = unchanged). */
	private static int fade(int color, float factor) {
		int alpha = Math.round(((color >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, factor)));
		return (alpha << 24) | (color & 0x00FFFFFF);
	}

	private static double length(double[] v) {
		return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
	}

	private static double[] normalize(double[] v) {
		double length = length(v);
		if (length < 1.0E-12) {
			return new double[] { 1.0, 0.0, 0.0 };
		}
		return new double[] { v[0] / length, v[1] / length, v[2] / length };
	}

	private static void scaleVector(double[] v, double factor) {
		v[0] *= factor;
		v[1] *= factor;
		v[2] *= factor;
	}

	private static double meanRadius(double[][] points) {
		double sum = 0.0;
		for (double[] point : points) {
			sum += length(point);
		}
		return points.length == 0 ? 1.0 : sum / points.length;
	}
}
