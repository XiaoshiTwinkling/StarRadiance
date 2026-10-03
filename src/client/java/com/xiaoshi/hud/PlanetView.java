package com.xiaoshi.hud;

import com.xiaoshi.astro.AstroTime;
import com.xiaoshi.astro.EclipseCalculator;
import com.xiaoshi.astro.PlanetPosition;
import com.xiaoshi.astro.SphereProjection;
import com.xiaoshi.sky.Celestial;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * The zoomed-in globe view: the selected body as a procedural sphere, lit by the real Sun.
 *
 * <p>No textures are involved. The disc is rasterised in horizontal bands (daylit colour, then the
 * night side clipped by the terminator) and overlaid with the body's own latitude/longitude grid,
 * its real rotation axis and the sub-solar point. Earth additionally gets the observer's meridian,
 * the observer's position and the Moon, whose distance is exaggerated (the real one is 60 Earth
 * radii) while the direction and the size ratio stay real.
 *
 * <p>For the other planets both the axis and the rotation come from the IAU rotation model, so the
 * drawn tilt is the real one: Mercury is nearly upright, Uranus lies on its side and Venus turns
 * backwards. The camera looks at the body from the Earth, so the phase you see is the phase that
 * body shows us in the sky tonight. Dragging orbits the camera around the body.
 */
public final class PlanetView {
	private static final int BANDS = 96;
	private static final int MERIDIANS = 12;
	private static final int GRATICULE_SAMPLES = 28;
	/**
	 * Globe radius as a fraction of the smaller screen dimension. The value is chosen so that the
	 * Earth plus the Moon still fit on screen for *any* direction the Moon can be in (the worst case
	 * is straight up or down), because the Earth sits in the exact centre.
	 */
	private static final double RADIUS_FRACTION = 0.185;
	private static final double MOON_RADIUS_RATIO = 1737.4 / 6378.14;
	private static final double MOON_DISTANCE_EARTH_RADII = 2.2;
	private static final double MEAN_MOON_DISTANCE_KM = 384400.0;

	/** Daylit colour of each body, indexed like {@link PlanetPosition}. */
	public static final int[] DAY_COLOR = {
		0xFF9E9689, 0xFFE6D9A8, 0xFF6E9ED9, 0xFFC1613B,
		0xFFD8C3A0, 0xFFE3D6AC, 0xFFA8E4EA, 0xFF5A7BE0,
		0xFFD8C0A8, 0xFF9A9A94, 0xFFDCE4EC, 0xFFEDEDE8, 0xFFC89B84, 0xFF6E6A64
	};
	/** Night side of each body; kept dark but never pure black so the disc stays readable. */
	public static final int[] NIGHT_COLOR = {
		0xFF2A2622, 0xFF3A3428, 0xFF16233A, 0xFF3A1D12,
		0xFF3A3126, 0xFF3A352A, 0xFF25383A, 0xFF1A2350,
		0xFF3A3226, 0xFF2A2A28, 0xFF33393E, 0xFF3A3A38, 0xFF3A2A22, 0xFF201E1C
	};

	private static final int EARTH_GRID = 0x66FFFFFF;
	private static final int GRID = 0x50FFFFFF;
	private static final int EARTH_MERIDIAN = 0xB0FFD24A;
	private static final int AXIS = 0xFFE8C07A;
	private static final int OBSERVER = 0xFFFFD24A;
	private static final int SUBSOLAR = 0xFFFF9A3C;
	private static final int LINK = 0x90FFFFFF;
	private static final int LABEL = 0xFFEEEEEE;
	private static final int LABEL_DIM = 0xFFA8A8A8;

	private PlanetView() {
	}

	/** A direction on the body's surface, in the frame the globe is drawn in. */
	private interface Surface {
		double[] at(double latDeg, double lonDeg);
	}

	/**
	 * @param planet       index into {@link PlanetPosition}
	 * @param opacity      cross-fade factor from the orbit chart to this view (0..1)
	 * @param originX      on-screen position the globe grows out of (its chart position)
	 * @param originY      on-screen position the globe grows out of
	 * @param originRadius chart radius, so the globe visibly grows out of the small dot
	 * @param yawDeg       camera revolution around the body, driven by dragging
	 * @param pitchDeg     camera elevation around the body, driven by dragging
	 * @param zoom         extra magnification from the scroll wheel (1 = default size)
	 */
	public static void render(DrawContext context, MinecraftClient client, Celestial.SkyState state,
			int planet, double opacity, double originX, double originY, double originRadius,
			double yawDeg, double pitchDeg, double zoom) {
		TextRenderer font = client.textRenderer;
		int width = client.getWindow().getScaledWidth();
		int height = client.getWindow().getScaledHeight();
		float alpha = (float) Math.max(0.0, Math.min(1.0, opacity));
		if (alpha < 0.02F) {
			return;
		}
		double growth = opacity * opacity * (3.0 - 2.0 * opacity);
		double jdTt = AstroTime.julianDateTt(state.julianDateUtc);
		double days = jdTt - AstroTime.J2000;
		double centuries = AstroTime.centuriesSinceJ2000(jdTt);
		boolean isEarth = planet == PlanetPosition.EARTH;

		double[] cameraBase;
		double[] sunDir;
		double[] axis;
		Surface surface;
		double[] moonDir = null;
		if (isEarth) {
			double gmst = AstroTime.mod360(state.localSiderealTimeDeg - state.longitudeDeg);
			cameraBase = SphereProjection.earthSurfaceDirection(state.latitudeDeg, state.longitudeDeg, gmst);
			double lambda = Math.toRadians(state.sunApparentLongitudeDeg);
			sunDir = new double[] { Math.cos(lambda), Math.sin(lambda), 0.0 };
			axis = SphereProjection.earthSurfaceDirection(90.0, 0.0, gmst);
			surface = (lat, lon) -> SphereProjection.earthSurfaceDirection(lat, lon, gmst);
			double moonLambda = Math.toRadians(state.moonEclipticLongitudeDeg);
			double moonBeta = Math.toRadians(state.moonEclipticLatitudeDeg);
			moonDir = new double[] {
				Math.cos(moonBeta) * Math.cos(moonLambda),
				Math.cos(moonBeta) * Math.sin(moonLambda),
				Math.sin(moonBeta)
			};
		} else {
			double[] body = PlanetPosition.heliocentricJ2000(planet, centuries);
			double[] earth = PlanetPosition.heliocentricJ2000(PlanetPosition.EARTH, centuries);
			// Look at the body from the Earth, so the drawn phase is what we see tonight.
			cameraBase = SphereProjection.normalize(SphereProjection.subtract(earth, body));
			sunDir = SphereProjection.normalize(SphereProjection.scale(body, -1.0));
			axis = PlanetPosition.poleJ2000(planet, days);
			int index = planet;
			surface = (lat, lon) -> PlanetPosition.surfaceDirection(index, lat, lon, days);
		}

		double[][] frame = cameraFrame(cameraBase, yawDeg, pitchDeg);
		SphereProjection sphere = new SphereProjection(frame[0], frame[1]);
		double finalRadius = Math.min(width, height) * RADIUS_FRACTION * zoom;
		double bodyX = originX + (width * 0.5 - originX) * growth;
		double bodyY = originY + (height * 0.5 - originY) * growth;
		double bodyRadius = originRadius + (finalRadius - originRadius) * growth;

		double moonX = 0.0;
		double moonY = 0.0;
		double moonRadius = 0.0;
		if (isEarth && moonDir != null) {
			moonRadius = bodyRadius * MOON_RADIUS_RATIO;
			double separation = bodyRadius * MOON_DISTANCE_EARTH_RADII * growth
				* (state.moonDistanceKm / MEAN_MOON_DISTANCE_KM);
			double[] basis = sphere.inScreenBasis(moonDir);
			double offsetX = basis[0];
			double offsetY = -basis[1];
			double offsetLength = Math.hypot(offsetX, offsetY);
			if (offsetLength < 0.15) {
				offsetX = 1.0;
				offsetY = 0.0;
				offsetLength = 1.0;
			}
			moonX = bodyX + offsetX / offsetLength * separation;
			moonY = bodyY + offsetY / offsetLength * separation;
			OrbitDiagram.dashedLine(context, new double[] { bodyX, bodyY, 0.0 },
				new double[] { moonX, moonY, 0.0 }, fade(LINK, alpha));
		}

		drawGlobe(context, sphere, bodyX, bodyY, bodyRadius, sunDir, DAY_COLOR[planet],
			NIGHT_COLOR[planet], alpha);
		if (isEarth && moonDir != null) {
			drawGlobe(context, sphere, moonX, moonY, moonRadius, sunDir, 0xFFCFCFCF, 0xFF2A2A2A, alpha);
			drawMoonGrid(context, sphere, moonX, moonY, moonRadius, moonDir, alpha);
		}
		drawGraticule(context, sphere, bodyX, bodyY, bodyRadius, surface,
			isEarth ? EARTH_GRID : GRID, alpha);
		double[] observerDir = null;
		if (isEarth) {
			// The observer's own meridian, drawn brighter, and the observer's position.
			drawMeridian(context, sphere, bodyX, bodyY, bodyRadius,
				(lat, lon) -> SphereProjection.earthSurfaceDirection(lat, state.longitudeDeg,
					AstroTime.mod360(state.localSiderealTimeDeg - state.longitudeDeg)),
				EARTH_MERIDIAN, alpha);
			observerDir = SphereProjection.earthSurfaceDirection(state.latitudeDeg,
				state.longitudeDeg,
				AstroTime.mod360(state.localSiderealTimeDeg - state.longitudeDeg));
		}
		drawAxis(context, sphere, bodyX, bodyY, bodyRadius, axis, alpha);
		drawMarkers(context, sphere, bodyX, bodyY, bodyRadius, sunDir, observerDir, alpha);
		drawLabels(context, font, state, planet, isEarth, bodyX, bodyY, bodyRadius, moonX, moonY,
			moonRadius, sphere, axis, sunDir, observerDir, alpha);
	}

	/**
	 * The dragged camera frame, as {@code {forward, up}}.
	 *
	 * <p>Yaw orbits the camera about the on-screen vertical and pitch about the screen horizontal,
	 * with the up vector <em>carried through</em> both. That last part matters: the projection can
	 * re-derive "up" from the ecliptic north for a fixed view, but doing it after every turn adds a
	 * roll proportional to the turn angle, so a straight horizontal drag also twisted the globe.
	 */
	private static double[][] cameraFrame(double[] base, double yawDeg, double pitchDeg) {
		double[] north = { 0.0, 0.0, 1.0 };
		double[] up = SphereProjection.subtract(north,
			SphereProjection.scale(base, SphereProjection.dot(north, base)));
		if (SphereProjection.length(up) < 1.0E-6) {
			// Looking straight down the ecliptic pole: any perpendicular axis will do.
			up = new double[] { 0.0, 1.0, 0.0 };
		}
		up = SphereProjection.normalize(up);
		double[] forward = rotate(base, up, Math.toRadians(yawDeg));
		double[] right = SphereProjection.normalize(SphereProjection.cross(up, forward));
		forward = rotate(forward, right, Math.toRadians(pitchDeg));
		up = SphereProjection.normalize(rotate(up, right, Math.toRadians(pitchDeg)));
		return new double[][] { forward, up };
	}

	/** Rodrigues rotation of {@code v} about a unit {@code axis}. */
	private static double[] rotate(double[] v, double[] axis, double angle) {
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		double dot = SphereProjection.dot(axis, v);
		double[] cross = SphereProjection.cross(axis, v);
		double factor = dot * (1.0 - cos);
		return new double[] {
			v[0] * cos + cross[0] * sin + axis[0] * factor,
			v[1] * cos + cross[1] * sin + axis[1] * factor,
			v[2] * cos + cross[2] * sin + axis[2] * factor
		};
	}

	private static void drawGlobe(DrawContext context, SphereProjection sphere, double cx, double cy,
			double radius, double[] sunDir, int dayColor, int nightColor, float alpha) {
		double[] sun = sphere.inScreenBasis(sunDir);
		double bandHeight = 2.0 * radius / BANDS;
		for (int i = 0; i < BANDS; i++) {
			double dy = -1.0 + (i + 0.5) * 2.0 / BANDS;
			double w = Math.sqrt(Math.max(0.0, 1.0 - dy * dy));
			if (w <= 1.0E-6) {
				continue;
			}
			int yTop = (int) Math.round(cy + radius - (i + 1) * bandHeight);
			int yBottom = (int) Math.round(cy + radius - i * bandHeight);
			if (yBottom <= yTop) {
				yBottom = yTop + 1;
			}
			int xLeft = (int) Math.round(cx - w * radius);
			int xRight = (int) Math.round(cx + w * radius);
			context.fill(xLeft, yTop, xRight, yBottom, fade(dayColor, alpha));
			double[] night = SphereProjection.nightSpan(dy, w, sun);
			if (night != null) {
				int nightLeft = (int) Math.round(cx + night[0] * radius);
				int nightRight = (int) Math.round(cx + night[1] * radius);
				context.fill(nightLeft, yTop, Math.max(nightLeft + 1, nightRight), yBottom,
					fade(nightColor, alpha));
			}
		}
	}

	private static void drawGraticule(DrawContext context, SphereProjection sphere, double cx,
			double cy, double radius, Surface surface, int color, float alpha) {
		for (int m = 0; m < MERIDIANS; m++) {
			double lon = m * 360.0 / MERIDIANS;
			drawMeridian(context, sphere, cx, cy, radius, (lat, ignored) -> surface.at(lat, lon), color,
				alpha);
		}
		for (int p = -2; p <= 2; p++) {
			double lat = p * 30.0;
			drawParallel(context, sphere, cx, cy, radius, (ignored, lon) -> surface.at(lat, lon), color,
				alpha);
		}
	}

	private static void drawMeridian(DrawContext context, SphereProjection sphere, double cx,
			double cy, double radius, Surface meridian, int color, float alpha) {
		double[] previous = null;
		for (int i = 0; i <= GRATICULE_SAMPLES; i++) {
			double lat = -88.0 + 176.0 * i / GRATICULE_SAMPLES;
			double[] screen = sphere.project(cx, cy, radius, meridian.at(lat, 0.0));
			if (screen[2] > 0.02 && previous != null) {
				OrbitDiagram.line(context, previous, screen, fade(color, alpha));
			}
			previous = screen[2] > 0.02 ? screen : null;
		}
	}

	private static void drawParallel(DrawContext context, SphereProjection sphere, double cx,
			double cy, double radius, Surface parallel, int color, float alpha) {
		double[] previous = null;
		for (int i = 0; i <= GRATICULE_SAMPLES * 2; i++) {
			double lon = 360.0 * i / (GRATICULE_SAMPLES * 2);
			double[] screen = sphere.project(cx, cy, radius, parallel.at(0.0, lon));
			if (screen[2] > 0.02 && previous != null) {
				OrbitDiagram.line(context, previous, screen, fade(color, alpha));
			}
			previous = screen[2] > 0.02 ? screen : null;
		}
	}

	/** A few great circles on the Moon make its synchronous rotation readable. */
	private static void drawMoonGrid(DrawContext context, SphereProjection sphere, double cx,
			double cy, double radius, double[] moonDir, float alpha) {
		double[] referenceUp = SphereProjection.normalize(new double[] { 0.0, 0.0, 1.0 });
		double[] tangent = SphereProjection.normalize(SphereProjection.subtract(referenceUp,
			SphereProjection.scale(moonDir, SphereProjection.dot(referenceUp, moonDir))));
		double[] bitangent = SphereProjection.cross(moonDir, tangent);
		for (int m = 0; m < 6; m++) {
			double angle = Math.PI * m / 6.0;
			double[] previous = null;
			for (int i = 0; i <= 18; i++) {
				double phi = Math.PI * i / 18.0;
				double[] direction = SphereProjection.normalize(new double[] {
					moonDir[0] * Math.cos(phi)
						+ (tangent[0] * Math.cos(angle) + bitangent[0] * Math.sin(angle)) * Math.sin(phi),
					moonDir[1] * Math.cos(phi)
						+ (tangent[1] * Math.cos(angle) + bitangent[1] * Math.sin(angle)) * Math.sin(phi),
					moonDir[2] * Math.cos(phi)
						+ (tangent[2] * Math.cos(angle) + bitangent[2] * Math.sin(angle)) * Math.sin(phi)
				});
				double[] screen = sphere.project(cx, cy, radius, direction);
				if (screen[2] > 0.02 && previous != null) {
					OrbitDiagram.line(context, previous, screen, fade(GRID, alpha));
				}
				previous = screen[2] > 0.02 ? screen : null;
			}
		}
	}

	private static void drawAxis(DrawContext context, SphereProjection sphere, double cx, double cy,
			double radius, double[] pole, float alpha) {
		double[] north = sphere.project(cx, cy, radius * 1.18, pole);
		double[] south = sphere.project(cx, cy, radius * 1.18, SphereProjection.scale(pole, -1.0));
		OrbitDiagram.line(context, south, north, fade(AXIS, alpha));
	}

	private static void drawMarkers(DrawContext context, SphereProjection sphere, double cx,
			double cy, double radius, double[] sunDir, double[] observerDir, float alpha) {
		if (observerDir != null) {
			OrbitDiagram.dot(context, sphere.project(cx, cy, radius, observerDir), 2,
				fade(OBSERVER, alpha));
		}
		double[] subsolar = sphere.project(cx, cy, radius, sunDir);
		if (subsolar[2] > 0.0) {
			OrbitDiagram.dot(context, subsolar, 2, fade(SUBSOLAR, alpha));
		}
	}

	private static void drawLabels(DrawContext context, TextRenderer font, Celestial.SkyState state,
			int planet, boolean isEarth, double bodyX, double bodyY, double bodyRadius, double moonX,
			double moonY, double moonRadius, SphereProjection sphere, double[] axis, double[] sunDir,
			double[] observerDir, float alpha) {
		String name = Text.translatable("hud.starradiance.planet." + PlanetPosition.KEYS[planet]).getString();
		OrbitDiagram.drawLabel(context, font, name, bodyX - font.getWidth(name) / 2.0,
			bodyY - bodyRadius - 14, fade(LABEL, alpha));
		if (isEarth) {
			drawLabel(context, font, "hud.starradiance.diagram.moon", moonX - 10, moonY - moonRadius - 12,
				LABEL, alpha);
		}
		if (observerDir != null) {
			double[] observer = sphere.project(bodyX, bodyY, bodyRadius, observerDir);
			if (observer[2] > 0.0) {
				OrbitDiagram.drawLabel(context, font,
					Text.translatable("hud.starradiance.zoom.observer").getString(),
					observer[0] + 5, observer[1] - 4, fade(OBSERVER, alpha));
			}
		}
		double[] subsolar = sphere.project(bodyX, bodyY, bodyRadius, sunDir);
		if (subsolar[2] > 0.0) {
			drawLabel(context, font, "hud.starradiance.zoom.subsolar", subsolar[0] + 5, subsolar[1] - 4,
				SUBSOLAR, alpha);
		}
		double tilt = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, Math.abs(axis[2])))));
		OrbitDiagram.drawLabel(context, font,
			Text.translatable("hud.starradiance.zoom.axis",
				String.format(Locale.ROOT, "%.1f", tilt)).getString(),
			bodyX + bodyRadius * 0.52, bodyY - bodyRadius * 1.02, fade(AXIS, alpha));
		if (isEarth) {
			drawLabel(context, font, "hud.starradiance.zoom.moonFace", moonX - moonRadius - 30,
				moonY + moonRadius + 4, LABEL_DIM, alpha);
			String distance = Text.translatable("hud.starradiance.zoom.distanceNote",
				String.format(Locale.ROOT, "%.0f", state.moonDistanceKm)).getString();
			OrbitDiagram.drawLabel(context, font, distance,
				(bodyX + moonX) / 2.0 - font.getWidth(distance) / 2.0,
				(bodyY + moonY) / 2.0 + 8, fade(LABEL_DIM, alpha));
			if (state.eclipseKind != EclipseCalculator.NONE) {
				String eclipse = Text.translatable("hud.starradiance.zoom.eclipse",
					Text.translatable(state.eclipseKind == EclipseCalculator.SOLAR
						? "hud.starradiance.eclipse.solar" : "hud.starradiance.eclipse.lunar").getString(),
					String.format(Locale.ROOT, "%.2f", state.eclipseMagnitude)).getString();
				OrbitDiagram.drawLabel(context, font, eclipse, 12, 12, fade(SUBSOLAR, alpha));
			}
		}
	}

	private static void drawLabel(DrawContext context, TextRenderer font, String key, double x,
			double y, int color, float alpha) {
		OrbitDiagram.drawLabel(context, font, Text.translatable(key).getString(), x, y, fade(color, alpha));
	}

	private static int fade(int color, float factor) {
		int alpha = Math.round(((color >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, factor)));
		return (alpha << 24) | (color & 0x00FFFFFF);
	}
}
