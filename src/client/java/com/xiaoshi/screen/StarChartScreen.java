package com.xiaoshi.screen;

import com.xiaoshi.astro.AstroTime;
import com.xiaoshi.astro.Precession;
import com.xiaoshi.astro.SkyChartProjection;
import com.xiaoshi.hud.OrbitDiagram;
import com.xiaoshi.hud.PanelBlur;
import com.xiaoshi.hud.EntryZoom;
import com.xiaoshi.sky.Celestial;
import com.xiaoshi.sky.ConstellationCatalog;
import com.xiaoshi.sky.DeepSkyCatalog;
import com.xiaoshi.sky.SkyPalette;
import com.xiaoshi.sky.StarCatalog;
import com.xiaoshi.sky.StarFieldRenderer;
import com.xiaoshi.sky.StarMeta;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * The "star watching assistant" (U by default): a non-pausing, interactive star wheel.
 *
 * <p>The whole sky above the horizon is drawn with the zenith at the centre of the screen and north
 * up, using exactly the rotation the sky dome uses ({@code SkyState.starMatrix}), so what the chart
 * shows is what the player sees outside. The chart can be dragged, zoomed with the wheel and
 * clicked: picking a star opens a data panel with its name, catalog designation, constellation,
 * magnitude, colour index, distance and both the J2000 and the current horizontal position.
 *
 * <p>Shift (press once) overlays the traditional constellation figures. During daylight the wheel
 * is replaced by a notice plus the sunset countdown, since there is nothing to observe.
 */
public class StarChartScreen extends Screen {
	private static final double MIN_ZOOM = 1.0;
	private static final double MAX_ZOOM = 8.0;
	private static final double PICK_RADIUS = 6.0;
	private static final double TICKS_PER_MINUTE = Celestial.TICKS_PER_DAY / 1440.0;
	/** How long a star has to be held down before it is marked in the sky. */
	private static final double MARK_HOLD_SECONDS = 0.5;
	/** Moving further than this while pressed turns the gesture into a chart pan instead. */
	private static final double MARK_DRAG_CANCEL_PIXELS = 4.0;

	private static final int BELOW_HORIZON = 0xF0040710;
	private static final int SKY_DISC = 0xF0121A2C;
	private static final int HORIZON_RING = 0x90FFFFFF;
	private static final int ALTITUDE_RING = 0x38FFFFFF;
	private static final int GRID_LABEL = 0xFF8FA6C8;
	private static final int CONSTELLATION_LINE = 0x8F7FB0FF;
	private static final int CONSTELLATION_LABEL = 0xFFA8C4F0;
	private static final int SELECTION = 0xFFFFD24A;
	/** Colour of the "marked in the sky" highlight, matching the beacon drawn in the sky. */
	private static final int MARKED = 0xFFFFD24A;
	private static final int MARKED_DIM = 0x60FFD24A;
	private static final int PANEL_BACKGROUND = 0xB0000000;
	/** Darkening laid over the blurred copy so the panel text keeps its contrast. */
	private static final int PANEL_TINT = 0x78000000;
	private static final int PANEL_BORDER = 0x60FFFFFF;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFF9AA4B4;

	private final Screen parent;
	private double zoom = MIN_ZOOM;
	private double panX;
	private double panY;
	private boolean showConstellations;
	private boolean shiftDown;
	private int selected = -1;
	/** Index into {@link DeepSkyCatalog} of the selected galaxy/nebula/cluster, or -1. */
	private int selectedDeepSky = -1;

	/** Per-frame cache of the projected stars, reused so the render loop never allocates. */
	private float[] screenX = new float[4096];
	private float[] screenY = new float[4096];
	private int[] starIndex = new int[4096];
	private int visibleStars;

	/** Star being held down to mark, or -1. */
	private int holdStar = -1;
	private long holdStartNanos;
	/** The star already marked in this chart session, or -1. */
	private int markedStar = -1;
	/** Nanosecond deadline for the "marked" confirmation. */
	private long markedMessageUntil;
	private double pressX;
	private double pressY;
	private final EntryZoom entry = new EntryZoom();
	/** The view zoom the last frame was drawn with, so click picking matches the screen exactly. */
	private double renderedZoom = MIN_ZOOM;

	/** J2000 equatorial unit vectors, one per catalogue entry (built once). */
	private float[] baseVectors;

	private double sunsetMinutes = Double.NaN;
	private long sunsetCacheKey = Long.MIN_VALUE;
	private double sunsetCacheLatitude = Double.NaN;

	/** Per-frame screen positions of the visible deep-sky objects (reused). */
	private float[] deepX = new float[64];
	private float[] deepY = new float[64];
	private float[] deepRadius = new float[64];
	private int[] deepIndex = new int[64];
	private int visibleDeep;

	public StarChartScreen(Screen parent) {
		super(Text.translatable("screen.starradiance.starchart.title"));
		this.parent = parent;
		// Opening the chart always starts a fresh marking session.
		StarFieldRenderer.clearMarkedStar();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void tick() {
		if (this.client != null && this.client.world == null) {
			close();
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		MinecraftClient client = this.client;
		if (client == null || client.world == null || client.player == null) {
			return;
		}
		context.fill(0, 0, this.width, this.height, BELOW_HORIZON);

		double latitude = Celestial.latitudeOf(client.player.getZ());
		Celestial.SkyState state = Celestial.compute(client.world.getTimeOfDay(), latitude);
		TextRenderer font = client.textRenderer;

		if (SkyPalette.starBrightness(state) <= 0.0) {
			renderDaylight(context, font, client, state, latitude);
			renderHint(context, font);
			return;
		}

		double baseRadius = Math.min(this.width, this.height) * 0.5 - 26.0;
		// The view itself eases forward from further away while the interface stays put.
		double viewZoom = zoom * entry.factor();
		renderedZoom = viewZoom;
		SkyChartProjection projection = new SkyChartProjection(this.width / 2.0, this.height / 2.0,
			baseRadius, viewZoom, panX, panY);
		double magnitudeLimit = magnitudeLimit(viewZoom);
		StarCatalog catalog = StarCatalog.load();

		fillDisc(context, projection, SKY_DISC);
		drawGrid(context, font, projection);
		if (showConstellations) {
			drawConstellations(context, font, projection, state);
		}
		if (catalog != null) {
			collectVisible(catalog, state, projection, magnitudeLimit);
			drawStars(context, catalog, state);
		}
		collectDeepSky(state, projection);
		drawDeepSky(context, font, projection);
		if (showConstellations) {
			drawConstellationNames(context, font, projection, state);
		}
		if (selected >= 0 && catalog != null) {
			drawSelection(context, projection, state, catalog);
		}
		if (markedStar >= 0 && catalog != null) {
			drawMarkedRing(context, projection, state, catalog);
		}
		updateMarkHold(catalog);
		drawMarkProgress(context, font, catalog);
		drawMarkedMessage(context, font);
		drawStats(context, font, magnitudeLimit);
		renderHint(context, font);
		if (selected >= 0 && catalog != null) {
			drawInfoPanel(context, font, state, catalog);
		} else if (selectedDeepSky >= 0) {
			drawDeepSkyPanel(context, font, state);
		} else {
			Text hint = Text.translatable("hud.starradiance.chart.click");
			context.drawText(font, hint, this.width - font.getWidth(hint) - 10, 26, TEXT_DIM, true);
		}
	}

	// ------------------------------------------------------------------ geometry passes

	private double magnitudeLimit(double viewZoom) {
		double value = 6.5 + 0.5 * (Math.log(viewZoom) / Math.log(2.0));
		return Math.max(4.5, Math.min(8.0, value));
	}

	private void ensureBases(StarCatalog catalog) {
		if (baseVectors != null && baseVectors.length == catalog.count * 3) {
			return;
		}
		float[] vectors = new float[catalog.count * 3];
		for (int i = 0; i < catalog.count; i++) {
			double ra = Math.toRadians(catalog.raDeg[i]);
			double dec = Math.toRadians(catalog.decDeg[i]);
			double cosDec = Math.cos(dec);
			vectors[i * 3] = (float) (Math.cos(ra) * cosDec);
			vectors[i * 3 + 1] = (float) (Math.sin(ra) * cosDec);
			vectors[i * 3 + 2] = (float) Math.sin(dec);
		}
		baseVectors = vectors;
	}

	/**
	 * Projects every star brighter than the magnitude limit that is above the horizon, caching the
	 * screen positions for both drawing and click picking.
	 */
	private void collectVisible(StarCatalog catalog, Celestial.SkyState state,
			SkyChartProjection projection, double magnitudeLimit) {
		ensureBases(catalog);
		double[] matrix = state.starMatrix;
		visibleStars = 0;
		for (int i = 0; i < catalog.count; i++) {
			if (catalog.magnitude[i] > (float) magnitudeLimit) {
				// stars.dat is sorted by magnitude, so the rest of the file is fainter still.
				break;
			}
			int base = i * 3;
			double bx = baseVectors[base];
			double by = baseVectors[base + 1];
			double bz = baseVectors[base + 2];
			double wx = matrix[0] * bx + matrix[1] * by + matrix[2] * bz;
			double wy = matrix[3] * bx + matrix[4] * by + matrix[5] * bz;
			double wz = matrix[6] * bx + matrix[7] * by + matrix[8] * bz;
			if (wy <= 0.0) {
				continue;
			}
			double altitude = Math.toDegrees(Math.asin(Math.min(1.0, wy)));
			double azimuth = AstroTime.mod360(Math.toDegrees(Math.atan2(wx, -wz)));
			double[] point = projection.project(altitude, azimuth);
			if (!projection.insideViewport(point[0], point[1], 8.0, this.width, this.height)) {
				continue;
			}
			if (visibleStars == screenX.length) {
				grow();
			}
			screenX[visibleStars] = (float) point[0];
			screenY[visibleStars] = (float) point[1];
			starIndex[visibleStars] = i;
			visibleStars++;
		}
	}

	private void grow() {
		int size = Math.min(1 << 20, screenX.length * 2);
		float[] x = new float[size];
		float[] y = new float[size];
		int[] index = new int[size];
		System.arraycopy(screenX, 0, x, 0, screenX.length);
		System.arraycopy(screenY, 0, y, 0, screenY.length);
		System.arraycopy(starIndex, 0, index, 0, starIndex.length);
		screenX = x;
		screenY = y;
		starIndex = index;
	}

	private void drawStars(DrawContext context, StarCatalog catalog, Celestial.SkyState state) {
		float[] rgb = new float[3];
		double night = SkyPalette.starBrightness(state);
		for (int k = 0; k < visibleStars; k++) {
			int i = starIndex[k];
			float lum = StarFieldRenderer.luminance(catalog.magnitude[i]);
			StarFieldRenderer.colorFromBv(catalog.bv[i], rgb);
			int alpha = (int) Math.round(255.0 * Math.min(1.0, night * (0.42 + 0.58 * lum)));
			if (alpha < 8) {
				continue;
			}
			int color = (alpha << 24) | (channel(rgb[0]) << 16) | (channel(rgb[1]) << 8) | channel(rgb[2]);
			int size = 1 + (int) Math.round(3.0 * lum);
			int x = (int) screenX[k];
			int y = (int) screenY[k];
			context.fill(x, y, x + size, y + size, color);
		}
	}

	private static int channel(float value) {
		return Math.max(0, Math.min(255, (int) Math.round(value * 255.0)));
	}

	// ------------------------------------------------------------------ deep-sky objects

	/** Projects the galaxies, nebulae and clusters that are above the horizon this frame. */
	private void collectDeepSky(Celestial.SkyState state, SkyChartProjection projection) {
		DeepSkyCatalog catalog = DeepSkyCatalog.get();
		visibleDeep = 0;
		if (catalog == null) {
			return;
		}
		if (deepX.length < catalog.count) {
			deepX = new float[catalog.count];
			deepY = new float[catalog.count];
			deepRadius = new float[catalog.count];
			deepIndex = new int[catalog.count];
		}
		double[] matrix = state.starMatrix;
		for (int i = 0; i < catalog.count; i++) {
			double[] base = Precession.equatorialUnitVector(catalog.raDeg[i], catalog.decDeg[i]);
			double[] world = Precession.apply(matrix, base[0], base[1], base[2]);
			if (world[1] <= 0.0) {
				continue;
			}
			double altitude = Math.toDegrees(Math.asin(Math.min(1.0, world[1])));
			double azimuth = AstroTime.mod360(Math.toDegrees(Math.atan2(world[0], -world[2])));
			double[] point = projection.project(altitude, azimuth);
			if (!projection.insideViewport(point[0], point[1], 24.0, this.width, this.height)) {
				continue;
			}
			deepX[visibleDeep] = (float) point[0];
			deepY[visibleDeep] = (float) point[1];
			deepRadius[visibleDeep] = symbolRadius(catalog, i, projection);
			deepIndex[visibleDeep] = i;
			visibleDeep++;
		}
	}

	/** On-screen symbol radius: the object's real angular size, with a readable minimum. */
	private static float symbolRadius(DeepSkyCatalog catalog, int index, SkyChartProjection projection) {
		double pixelsPerDegree = projection.scale();
		double real = catalog.majorArcmin[index] / 120.0 * pixelsPerDegree;
		return (float) Math.max(5.0, Math.min(120.0, real));
	}

	/** Draws each object as a ring plus a core, coloured by type and labelled with its catalogue id. */
	private void drawDeepSky(DrawContext context, TextRenderer font, SkyChartProjection projection) {
		DeepSkyCatalog catalog = DeepSkyCatalog.get();
		if (catalog == null) {
			return;
		}
		for (int k = 0; k < visibleDeep; k++) {
			int i = deepIndex[k];
			int color = DeepSkyCatalog.colorOf(catalog.type[i]);
			double x = deepX[k];
			double y = deepY[k];
			double radius = deepRadius[k];
			OrbitDiagram.ring(context, x, y, radius, color);
			OrbitDiagram.dot(context, new double[] { x, y }, (int) Math.max(1.0, radius * 0.22), color);
			if (radius >= 4.0 || catalog.magnitude[i] <= 6.5 || i == selectedDeepSky) {
				String label = catalog.id[i];
				context.drawText(font, label, (int) Math.round(x) + (int) radius + 2,
					(int) Math.round(y) - 4, color, true);
			}
		}
	}

	// ------------------------------------------------------------------ grid and constellations

	private void drawGrid(DrawContext context, TextRenderer font, SkyChartProjection projection) {
		drawCircle(context, projection, 0.0, HORIZON_RING, 2);
		drawCircle(context, projection, 30.0, ALTITUDE_RING, 2);
		drawCircle(context, projection, 60.0, ALTITUDE_RING, 2);
		drawSpoke(context, projection, 0.0, GRID_LABEL);
		drawSpoke(context, projection, 90.0, GRID_LABEL);
		drawSpoke(context, projection, 180.0, GRID_LABEL);
		drawSpoke(context, projection, 270.0, GRID_LABEL);
		drawCardinal(context, font, projection, 0.0, "hud.starradiance.chart.dir.n");
		drawCardinal(context, font, projection, 90.0, "hud.starradiance.chart.dir.e");
		drawCardinal(context, font, projection, 180.0, "hud.starradiance.chart.dir.s");
		drawCardinal(context, font, projection, 270.0, "hud.starradiance.chart.dir.w");
	}

	/** Paints the disc of sky above the horizon (the horizon circle is its rim). */
	private void fillDisc(DrawContext context, SkyChartProjection projection, int color) {
		double radius = projection.radiusOf(0.0);
		if (radius <= 0.0) {
			return;
		}
		double cx = projection.centerX();
		double cy = projection.centerY();
		for (int y = 0; y < this.height; y += 2) {
			double dy = (y + 1.0) - cy;
			if (Math.abs(dy) >= radius) {
				continue;
			}
			double half = Math.sqrt(radius * radius - dy * dy);
			int x1 = (int) Math.max(0.0, Math.floor(cx - half));
			int x2 = (int) Math.min(this.width, Math.ceil(cx + half));
			if (x2 > x1) {
				context.fill(x1, y, x2, Math.min(this.height, y + 2), color);
			}
		}
	}

	private void drawCircle(DrawContext context, SkyChartProjection projection, double altitudeDeg,
			int color, int thickness) {
		double radius = projection.radiusOf(altitudeDeg);
		if (radius < 3.0) {
			return;
		}
		double cx = projection.centerX();
		double cy = projection.centerY();
		int samples = (int) Math.max(64.0, Math.min(900.0, Math.round(2.0 * Math.PI * radius / 3.0)));
		for (int i = 0; i < samples; i++) {
			double angle = 2.0 * Math.PI * i / samples;
			int x = (int) Math.round(cx + Math.cos(angle) * radius);
			int y = (int) Math.round(cy + Math.sin(angle) * radius);
			if (x < -thickness || x > this.width + thickness || y < -thickness
					|| y > this.height + thickness) {
				continue;
			}
			context.fill(x, y, x + thickness, y + thickness, color);
		}
	}

	private void drawSpoke(DrawContext context, SkyChartProjection projection, double azimuthDeg,
			int color) {
		double[] rim = projection.project(0.0, azimuthDeg);
		OrbitDiagram.line(context, new double[] { projection.centerX(), projection.centerY() },
			new double[] { rim[0], rim[1] }, color);
	}

	private void drawCardinal(DrawContext context, TextRenderer font, SkyChartProjection projection,
			double azimuthDeg, String key) {
		String text = I18n.translate(key);
		double[] point = projection.project(-4.0, azimuthDeg);
		context.drawText(font, text, (int) Math.round(point[0] - font.getWidth(text) / 2.0),
			(int) Math.round(point[1] - 4), GRID_LABEL, true);
	}

	private void drawConstellations(DrawContext context, TextRenderer font,
			SkyChartProjection projection, Celestial.SkyState state) {
		ConstellationCatalog catalog = ConstellationCatalog.get();
		if (catalog == null) {
			return;
		}
		double[] matrix = state.starMatrix;
		for (float[][] polylines : catalog.lines) {
			for (float[] line : polylines) {
				for (int v = 0; v + 3 < line.length; v += 2) {
					double[] a = toWorld(matrix, line[v], line[v + 1]);
					double[] b = toWorld(matrix, line[v + 2], line[v + 3]);
					drawHorizonClipped(context, projection, a, b, CONSTELLATION_LINE);
				}
			}
		}
	}

	private void drawConstellationNames(DrawContext context, TextRenderer font,
			SkyChartProjection projection, Celestial.SkyState state) {
		ConstellationCatalog catalog = ConstellationCatalog.get();
		if (catalog == null) {
			return;
		}
		double[] matrix = state.starMatrix;
		for (int c = 0; c < catalog.lines.length; c++) {
			double sx = 0.0;
			double sy = 0.0;
			double sz = 0.0;
			int count = 0;
			for (float[] line : catalog.lines[c]) {
				for (int v = 0; v + 1 < line.length; v += 2) {
					double[] world = toWorld(matrix, line[v], line[v + 1]);
					if (world[1] <= 0.0) {
						continue;
					}
					sx += world[0];
					sy += world[1];
					sz += world[2];
					count++;
				}
			}
			if (count < 3) {
				continue;
			}
			double length = Math.sqrt(sx * sx + sy * sy + sz * sz);
			if (length < 1.0E-6) {
				continue;
			}
			double altitude = Math.toDegrees(Math.asin(Math.min(1.0, sy / length)));
			double azimuth = AstroTime.mod360(Math.toDegrees(Math.atan2(sx / length, -sz / length)));
			double[] point = projection.project(altitude, azimuth);
			if (!projection.insideViewport(point[0], point[1], 0.0, this.width, this.height)) {
				continue;
			}
			String name = I18n.translate("constellation.starradiance." + catalog.ids[c]);
			context.drawText(font, name, (int) Math.round(point[0]) - font.getWidth(name) / 2,
				(int) Math.round(point[1]) - 4, CONSTELLATION_LABEL, true);
		}
	}

	/** Draws a great-circle segment, clipping it where it dips below the horizon. */
	private void drawHorizonClipped(DrawContext context, SkyChartProjection projection, double[] a,
			double[] b, int color) {
		boolean aUp = a[1] > 0.0;
		boolean bUp = b[1] > 0.0;
		if (!aUp && !bUp) {
			return;
		}
		if (!aUp || !bUp) {
			double t = a[1] / (a[1] - b[1]);
			double[] crossing = new double[] {
				a[0] + (b[0] - a[0]) * t,
				a[1] + (b[1] - a[1]) * t,
				a[2] + (b[2] - a[2]) * t
			};
			double length = Math.sqrt(crossing[0] * crossing[0] + crossing[1] * crossing[1]
				+ crossing[2] * crossing[2]);
			if (length > 1.0E-9) {
				crossing[0] /= length;
				crossing[1] /= length;
				crossing[2] /= length;
			}
			if (aUp) {
				b = crossing;
			} else {
				a = crossing;
			}
		}
		double[] from = projection.projectWorld(a);
		double[] to = projection.projectWorld(b);
		if (!projection.insideViewport(from[0], from[1], 0.0, this.width, this.height)
				&& !projection.insideViewport(to[0], to[1], 0.0, this.width, this.height)) {
			return;
		}
		OrbitDiagram.line(context, from, to, color);
	}

	private static double[] toWorld(double[] matrix, float raDeg, float decDeg) {
		double[] base = Precession.equatorialUnitVector(raDeg, decDeg);
		return Precession.apply(matrix, base[0], base[1], base[2]);
	}

	// ------------------------------------------------------------------ selection and panel

	private void drawSelection(DrawContext context, SkyChartProjection projection,
			Celestial.SkyState state, StarCatalog catalog) {
		double[] horizontal = altAzOf(catalog, state, selected);
		if (horizontal == null) {
			return;
		}
		double[] point = projection.project(horizontal[0], horizontal[1]);
		OrbitDiagram.ring(context, point[0], point[1], 7.0, SELECTION);
	}

	/** Rings the star that is currently marked in the sky, in the chart too. */
	private void drawMarkedRing(DrawContext context, SkyChartProjection projection,
			Celestial.SkyState state, StarCatalog catalog) {
		double[] horizontal = altAzOf(catalog, state, markedStar);
		if (horizontal == null) {
			return;
		}
		double[] point = projection.project(horizontal[0], horizontal[1]);
		OrbitDiagram.ring(context, point[0], point[1], 11.0, MARKED);
		OrbitDiagram.ring(context, point[0], point[1], 13.0, MARKED_DIM);
	}

	/** Marks the held star once the hold has lasted long enough. */
	private void updateMarkHold(StarCatalog catalog) {
		if (holdStar < 0 || catalog == null || holdStar >= catalog.count) {
			return;
		}
		double held = (System.nanoTime() - holdStartNanos) / 1.0E9;
		if (held >= MARK_HOLD_SECONDS) {
			StarFieldRenderer.setMarkedStar(catalog.raDeg[holdStar], catalog.decDeg[holdStar]);
			markedStar = holdStar;
			markedMessageUntil = System.nanoTime() + 2_000_000_000L;
			holdStar = -1;
		}
	}

	/** The 0.5 s hold progress bar, drawn under the star being held. */
	private void drawMarkProgress(DrawContext context, TextRenderer font, StarCatalog catalog) {
		if (holdStar < 0 || catalog == null) {
			return;
		}
		int slot = screenIndexOf(holdStar);
		if (slot < 0) {
			return;
		}
		double progress = Math.max(0.0, Math.min(1.0,
			(System.nanoTime() - holdStartNanos) / 1.0E9 / MARK_HOLD_SECONDS));
		int centerX = Math.round(screenX[slot]);
		int centerY = Math.round(screenY[slot]);
		int barWidth = 72;
		int barHeight = 6;
		int left = centerX - barWidth / 2;
		int top = centerY + 11;
		String label = I18n.translate("hud.starradiance.chart.mark.hint");
		context.drawText(font, label, centerX - font.getWidth(label) / 2, top - 12, TEXT, true);
		context.fill(left - 1, top - 1, left + barWidth + 1, top + barHeight + 1, 0xC0000000);
		context.fill(left, top, left + Math.round(barWidth * (float) progress), top + barHeight, MARKED);
	}

	/** Confirms the mark for a couple of seconds after the hold completes. */
	private void drawMarkedMessage(DrawContext context, TextRenderer font) {
		if (markedMessageUntil <= System.nanoTime()) {
			return;
		}
		String name = markedStar >= 0 ? displayName(StarMeta.get(), markedStar) : "";
		Text message = Text.translatable("hud.starradiance.chart.mark.done", name);
		int width = font.getWidth(message);
		context.fill(this.width / 2 - width / 2 - 6, 30, this.width / 2 + width / 2 + 6, 46, 0xA0000000);
		context.drawText(font, message, this.width / 2 - width / 2, 34, MARKED, true);
	}

	/** Slot in the per-frame visible list for a catalogue index, or -1 when it is off screen. */
	private int screenIndexOf(int star) {
		for (int k = 0; k < visibleStars; k++) {
			if (starIndex[k] == star) {
				return k;
			}
		}
		return -1;
	}

	/** Current {altitude, azimuth} of a catalogue star, or null when it is below the horizon. */
	private double[] altAzOf(StarCatalog catalog, Celestial.SkyState state, int index) {
		ensureBases(catalog);
		double[] matrix = state.starMatrix;
		int base = index * 3;
		double bx = baseVectors[base];
		double by = baseVectors[base + 1];
		double bz = baseVectors[base + 2];
		double wx = matrix[0] * bx + matrix[1] * by + matrix[2] * bz;
		double wy = matrix[3] * bx + matrix[4] * by + matrix[5] * bz;
		double wz = matrix[6] * bx + matrix[7] * by + matrix[8] * bz;
		if (wy <= 0.0) {
			return null;
		}
		double altitude = Math.toDegrees(Math.asin(Math.min(1.0, wy)));
		double azimuth = AstroTime.mod360(Math.toDegrees(Math.atan2(wx, -wz)));
		return new double[] { altitude, azimuth };
	}

	private void drawInfoPanel(DrawContext context, TextRenderer font, Celestial.SkyState state,
			StarCatalog catalog) {
		StarMeta meta = StarMeta.get();
		String name = displayName(meta, selected);
		if (meta == null || selected >= meta.count) {
			drawDataPanel(context, font, name, List.<String[]>of(new String[] {
				"", Text.translatable("hud.starradiance.chart.dataMissing").getString() }));
			return;
		}
		String constellation = meta.constellation[selected] >= 0
			? I18n.translate("constellation.starradiance."
				+ meta.constellations[meta.constellation[selected]])
			: unknown();
		double[] horizontal = altAzOf(catalog, state, selected);
		List<String[]> rows = new ArrayList<>();
		rows.add(row("hud.starradiance.chart.field.designation", meta.designation[selected]));
		rows.add(row("hud.starradiance.chart.field.constellation", constellation));
		rows.add(row("hud.starradiance.chart.field.magnitude", num("%.2f", catalog.magnitude[selected])));
		rows.add(row("hud.starradiance.chart.field.color",
			num("%+.2f", catalog.bv[selected]) + " / " + nonEmpty(meta.spectral[selected])));
		rows.add(row("hud.starradiance.chart.field.distance",
			meta.distanceCentily[selected] > 0
				? Text.translatable("hud.starradiance.chart.value.distance",
					num("%.2f", meta.distanceCentily[selected] / 100.0)).getString()
				: unknown()));
		rows.add(row("hud.starradiance.chart.field.radec",
			num("%.3f", catalog.raDeg[selected] / 15.0) + "h  " + num("%+.3f", catalog.decDeg[selected])));
		rows.add(row("hud.starradiance.chart.field.altaz",
			horizontal == null ? unknown()
				: num("%.1f", horizontal[0]) + "  " + num("%.1f", horizontal[1])));
		drawDataPanel(context, font, name, rows);
	}

	/** The data panel for a galaxy, nebula or cluster. */
	private void drawDeepSkyPanel(DrawContext context, TextRenderer font, Celestial.SkyState state) {
		DeepSkyCatalog catalog = DeepSkyCatalog.get();
		if (catalog == null || selectedDeepSky < 0 || selectedDeepSky >= catalog.count) {
			return;
		}
		int i = selectedDeepSky;
		List<String[]> rows = new ArrayList<>();
		rows.add(row("hud.starradiance.chart.field.designation", catalog.id[i]));
		rows.add(row("hud.starradiance.chart.field.type",
			I18n.translate("hud.starradiance.deepsky.type." + DeepSkyCatalog.TYPE_KEYS[catalog.type[i]])));
		rows.add(row("hud.starradiance.chart.field.constellation",
			I18n.translate("constellation.starradiance." + catalog.constellation[i])));
		rows.add(row("hud.starradiance.chart.field.magnitude", num("%.1f", catalog.magnitude[i])));
		rows.add(row("hud.starradiance.chart.field.size",
			Text.translatable("hud.starradiance.chart.value.size",
				num("%.1f", catalog.majorArcmin[i])).getString()));
		rows.add(row("hud.starradiance.chart.field.radec",
			num("%.3f", catalog.raDeg[i] / 15.0) + "h  " + num("%+.3f", catalog.decDeg[i])));
		double[] horizontal = deepSkyAltAz(catalog, state, i);
		rows.add(row("hud.starradiance.chart.field.altaz",
			horizontal == null ? unknown()
				: num("%.1f", horizontal[0]) + "  " + num("%.1f", horizontal[1])));
		drawDataPanel(context, font, deepSkyName(catalog, i), rows);
	}

	private static String deepSkyName(DeepSkyCatalog catalog, int index) {
		String key = "deepsky.starradiance." + catalog.id[index];
		return I18n.hasTranslation(key) ? I18n.translate(key) : catalog.name[index];
	}

	/** Current {altitude, azimuth} of a deep-sky object, or null when it is below the horizon. */
	private double[] deepSkyAltAz(DeepSkyCatalog catalog, Celestial.SkyState state, int index) {
		double[] base = Precession.equatorialUnitVector(catalog.raDeg[index], catalog.decDeg[index]);
		double[] world = Precession.apply(state.starMatrix, base[0], base[1], base[2]);
		if (world[1] <= 0.0) {
			return null;
		}
		return new double[] {
			Math.toDegrees(Math.asin(Math.min(1.0, world[1]))),
			AstroTime.mod360(Math.toDegrees(Math.atan2(world[0], -world[2])))
		};
	}

	private static String[] row(String key, String value) {
		return new String[] { I18n.translate(key), value };
	}

	/**
	 * Draws the info panel. The value column is placed just past the widest label instead of at a
	 * fixed offset, because the translated field names vary a lot in width (the Chinese labels in
	 * particular used to run into their values).
	 */
	private void drawDataPanel(DrawContext context, TextRenderer font, String title, List<String[]> rows) {
		int labelWidth = 0;
		int valueWidth = 0;
		for (String[] row : rows) {
			labelWidth = Math.max(labelWidth, row[0].isEmpty() ? 0 : font.getWidth(row[0] + ":"));
			valueWidth = Math.max(valueWidth, font.getWidth(row[1]));
		}
		int labelX = 6;
		int valueX = labelX + (labelWidth == 0 ? 0 : labelWidth + 8);
		int panelWidth = Math.max(200, valueX + valueWidth + 8);
		panelWidth = Math.min(panelWidth, Math.max(120, this.width - 24));
		int x = this.width - panelWidth - 8;
		int y = 38;
		int height = 12 + (rows.size() + 1) * 11;
		// Frosted glass: blur whatever is behind the panel, then tint it so white text stays legible.
		if (PanelBlur.draw(context, x, y, panelWidth, height)) {
			context.fill(x, y, x + panelWidth, y + height, PANEL_TINT);
		} else {
			context.fill(x, y, x + panelWidth, y + height, PANEL_BACKGROUND);
		}
		context.fill(x, y, x + panelWidth, y + 1, PANEL_BORDER);
		context.fill(x, y + height - 1, x + panelWidth, y + height, PANEL_BORDER);

		int lineY = y + 5;
		context.drawText(font, title, x + labelX, lineY, TEXT, true);
		lineY += 11;
		for (String[] row : rows) {
			if (row[0].isEmpty()) {
				context.drawText(font, row[1], x + labelX, lineY, TEXT_DIM, true);
			} else {
				context.drawText(font, row[0] + ":", x + labelX, lineY, TEXT_DIM, true);
				context.drawText(font, row[1], x + valueX, lineY, TEXT, true);
			}
			lineY += 11;
		}
	}

	private String displayName(StarMeta meta, int index) {
		if (meta != null) {
			String proper = meta.name[index];
			if (!proper.isEmpty()) {
				String key = "star.starradiance." + proper;
				return I18n.hasTranslation(key) ? I18n.translate(key) : proper;
			}
			String designation = meta.designation[index];
			if (!designation.isEmpty()) {
				return designation;
			}
		}
		return num("#%d", index);
	}

	private static String nonEmpty(String value) {
		return value.isEmpty() ? unknown() : value;
	}

	private static String unknown() {
		return I18n.translate("hud.starradiance.chart.value.unknown");
	}

	// ------------------------------------------------------------------ overlays

	private void drawStats(DrawContext context, TextRenderer font, double magnitudeLimit) {
		Text stats = Text.translatable("hud.starradiance.chart.stats",
			num("%.1f", magnitudeLimit), num("%d", visibleStars), num("%.1f", zoom));
		context.drawText(font, stats, this.width - font.getWidth(stats) - 10, 12, GRID_LABEL, true);
	}

	private void renderHint(DrawContext context, TextRenderer font) {
		Text hint = Text.translatable("hud.starradiance.chart.hint",
			Text.translatable(showConstellations ? "hud.starradiance.chart.constellations.hide"
				: "hud.starradiance.chart.constellations.show"));
		context.drawText(font, hint, 10, this.height - 14, GRID_LABEL, true);
	}

	private void renderDaylight(DrawContext context, TextRenderer font, MinecraftClient client,
			Celestial.SkyState state, double latitude) {
		Text title = Text.translatable("hud.starradiance.chart.daylight");
		int centerY = this.height / 2 - 20;
		context.drawText(font, title, (this.width - font.getWidth(title)) / 2, centerY, TEXT, true);
		Text altitude = Text.translatable("hud.starradiance.chart.daylight.sunAlt",
			num("%.1f", state.sunAltitudeDeg));
		context.drawText(font, altitude, (this.width - font.getWidth(altitude)) / 2, centerY + 16,
			TEXT_DIM, true);
		double minutes = sunsetMinutes(client, latitude);
		Text sunset = Double.isNaN(minutes)
			? Text.translatable("hud.starradiance.chart.daylight.never")
			: Text.translatable("hud.starradiance.chart.daylight.sunset", num("%.0f", minutes));
		context.drawText(font, sunset, (this.width - font.getWidth(sunset)) / 2, centerY + 30,
			TEXT_DIM, true);
	}

	/** Minutes of game time until the Sun next crosses the horizon, cached for one game hour. */
	private double sunsetMinutes(MinecraftClient client, double latitude) {
		long key = client.world.getTimeOfDay() / 1000L;
		if (key == sunsetCacheKey && latitude == sunsetCacheLatitude) {
			return sunsetMinutes;
		}
		long start = client.world.getTimeOfDay();
		double found = Double.NaN;
		for (long t = 100L; t <= Celestial.TICKS_PER_DAY; t += 100L) {
			if (Celestial.compute(start + t, latitude).sunY <= 0.0) {
				found = t / TICKS_PER_MINUTE;
				break;
			}
		}
		sunsetCacheKey = key;
		sunsetCacheLatitude = latitude;
		sunsetMinutes = found;
		return found;
	}

	private static String num(String format, double value) {
		return String.format(Locale.ROOT, format, value);
	}

	private static String num(String format, long value) {
		return String.format(Locale.ROOT, format, value);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.client != null && this.client.world != null
				&& this.client.player != null) {
			entry.cancel();
			pressX = mouseX;
			pressY = mouseY;
			selectStarNear(mouseX, mouseY);
			// Holding the star that was just picked marks it in the sky.
			holdStar = selected;
			holdStartNanos = System.nanoTime();
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private void selectStarNear(double mouseX, double mouseY) {
		// Deep-sky objects are few and drawn large, so they take precedence over single stars.
		int deepFound = -1;
		double bestDeep = Double.MAX_VALUE;
		for (int k = 0; k < visibleDeep; k++) {
			double dx = mouseX - deepX[k];
			double dy = mouseY - deepY[k];
			double reach = Math.max(6.0, deepRadius[k]);
			double distance = dx * dx + dy * dy;
			if (distance <= reach * reach && distance < bestDeep) {
				bestDeep = distance;
				deepFound = deepIndex[k];
			}
		}
		if (deepFound >= 0) {
			selectedDeepSky = deepFound;
			selected = -1;
			return;
		}
		selectedDeepSky = -1;

		StarCatalog catalog = StarCatalog.load();
		if (catalog == null) {
			return;
		}
		Celestial.SkyState state = Celestial.compute(this.client.world.getTimeOfDay(),
			Celestial.latitudeOf(this.client.player.getZ()));
		double baseRadius = Math.min(this.width, this.height) * 0.5 - 26.0;
		SkyChartProjection projection = new SkyChartProjection(this.width / 2.0, this.height / 2.0,
			baseRadius, renderedZoom, panX, panY);
		collectVisible(catalog, state, projection, magnitudeLimit(renderedZoom));
		double best = PICK_RADIUS * PICK_RADIUS;
		int found = -1;
		for (int k = 0; k < visibleStars; k++) {
			double dx = screenX[k] - mouseX;
			double dy = screenY[k] - mouseY;
			double distance = dx * dx + dy * dy;
			if (distance <= best) {
				best = distance;
				found = starIndex[k];
			}
		}
		selected = found;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		entry.cancel();
		if (Math.hypot(mouseX - pressX, mouseY - pressY) > MARK_DRAG_CANCEL_PIXELS) {
			holdStar = -1;
		}
		panX += deltaX;
		panY += deltaY;
		clampPan();
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		holdStar = -1;
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount,
			double verticalAmount) {
		if (verticalAmount == 0.0) {
			return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		}
		entry.cancel();
		double target = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * Math.pow(1.25, verticalAmount)));
		if (target != zoom) {
			// Keep the sky point under the cursor anchored while the scale changes.
			double baseRadius = Math.min(this.width, this.height) * 0.5 - 26.0;
			double centerX = this.width / 2.0;
			double centerY = this.height / 2.0;
			double offsetX = mouseX - centerX - panX;
			double offsetY = mouseY - centerY - panY;
			double ratio = target / zoom;
			panX = mouseX - centerX - offsetX * ratio;
			panY = mouseY - centerY - offsetY * ratio;
			zoom = target;
			clampPan();
		}
		return true;
	}

	private void clampPan() {
		double baseRadius = Math.min(this.width, this.height) * 0.5 - 26.0;
		double limit = (zoom - MIN_ZOOM) * baseRadius * 0.9 + 16.0;
		panX = Math.max(-limit, Math.min(limit, panX));
		panY = Math.max(-limit, Math.min(limit, panY));
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_LEFT_SHIFT || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			if (!shiftDown) {
				shiftDown = true;
				showConstellations = !showConstellations;
			}
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_R) {
			entry.cancel();
			zoom = MIN_ZOOM;
			panX = 0.0;
			panY = 0.0;
			selected = -1;
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_U || keyCode == GLFW.GLFW_KEY_ESCAPE) {
			close();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_LEFT_SHIFT || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			shiftDown = false;
			return true;
		}
		return super.keyReleased(keyCode, scanCode, modifiers);
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(this.parent);
		}
	}
}
