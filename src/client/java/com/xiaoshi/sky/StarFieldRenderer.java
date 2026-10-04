package com.xiaoshi.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xiaoshi.config.StarRadianceConfig;
import com.xiaoshi.astro.Precession;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Renders the real star catalog as a static coloured point field.
 *
 * <p>Each star is stored as a tiny POSITION_COLOR quad centred on its equatorial base direction
 * ({@code (cosδcosα, cosδsinα, −sinδ)·R}). The whole buffer is drawn each frame under
 * {@code align·S_{−Z}(−s)}: {@code align} maps the star pole −Z to the observer's celestial pole,
 * and the spin advances the star field with the sidereal clock. Brightness is by apparent
 * magnitude (vertex alpha), colour by B-V, and a single global factor dims everything with
 * twilight/moonlight/weather.
 */
public final class StarFieldRenderer {
	private static final float RADIUS = 100.0F;
	/** Global multiplier on the star field's alpha; the raw value reads as too bright at night. */
	private static final double STAR_BRIGHTNESS = 0.72;

	private static VertexBuffer buffer;
	private static boolean built;
	private static boolean catalogMissing;
	/** StarRadiance's own {@code starradiance:sky} core shader; null before registration. */
	private static volatile ShaderProgram program;
	/** Number of stars actually placed into the vertex buffer (after the config magnitude cap). */
	public static int drawnStars;
	/** The star the player marked in the star chart, as a J2000 right ascension/declination. */
	private static double markedRaDeg = Double.NaN;
	private static double markedDecDeg = Double.NaN;

	/** Short diagnostic string for the sky debug HUD. */
	public static String status() {
		return "starfield built=" + built + " drawn=" + drawnStars + " missing=" + catalogMissing
			+ " shader=" + (program != null ? "ok" : "null");
	}

	private StarFieldRenderer() {
	}

	public static boolean drawDiagnostics = false; // temporary debug markers

	//? if >=1.21.4 {
	/*/^* Core shader key for the star field and the sky dome (1.21.4 resolves core shaders by key). ^/
	public static final net.minecraft.client.gl.ShaderProgramKey SKY_KEY =
		new net.minecraft.client.gl.ShaderProgramKey(
			net.minecraft.util.Identifier.of("starradiance", "core/sky"), VertexFormats.POSITION_COLOR,
			net.minecraft.client.gl.Defines.EMPTY);
	*///?}

	/**
	 * Binds and returns the star field program: our own core shader when it is available, vanilla's
	 * position_color otherwise.
	 */
	private static ShaderProgram currentShader() {
		//? if >=1.21.4 {
		/*return RenderSystem.setShader(SKY_KEY);
		*///?} else {
		return program != null ? program : GameRenderer.getPositionColorProgram();
		//?}
	}

	/** Uses our own shader so Iris cannot remap the starfield onto the pack's procedural sky. */
	public static void setProgram(ShaderProgram shaderProgram) {
		program = shaderProgram;
	}

	public static boolean shaderReady() {
		return program != null;
	}

	public static boolean catalogMissing() {
		return catalogMissing;
	}

	/** Marks one catalogue star so it is drawn as a bright, pulsing beacon in the sky. */
	public static void setMarkedStar(double raDeg, double decDeg) {
		markedRaDeg = raDeg;
		markedDecDeg = decDeg;
	}

	/** Forgets the marked star. The star chart calls this every time it is opened. */
	public static void clearMarkedStar() {
		markedRaDeg = Double.NaN;
		markedDecDeg = Double.NaN;
	}

	public static boolean hasMarkedStar() {
		return !Double.isNaN(markedRaDeg);
	}

	/** Attempts to draw the real star field; returns true when it did (catalog present + buffer drawn). */
	public static boolean draw(MatrixStack matrices, Matrix4f projection, Celestial.SkyState state, double cloud) {
		if (!ensureBuilt()) {
			return false;
		}

		double twilight = SkyPalette.starBrightness(state);
		double moonless = SkyPalette.moonlessFactor(state);
		// The star quads blend additively, so a full-strength field blows out to white on a dark
		// night. Held a little under 1.0 the bright stars still read clearly without washing out.
		double env = Math.max(0.0, Math.min(1.0, twilight * moonless * cloud)) * STAR_BRIGHTNESS;
		ShaderProgram shader = currentShader();

		if (drawDiagnostics) {
			// Full-brightness markers independent of our star buffer/rotation/colour so we can tell
			// "star layer not drawn at all" apart from "stars are too small/rotated away".
			// Red = straight up (+Y); cyan = world north horizon (−Z); both 100 blocks out.
			drawMarker(matrices, shader, 0.0F, 1.0F, 0.0F, 1.0F, 0.0F, 0.0F); // up (red)
			drawMarker(matrices, shader, 0.0F, 0.0F, -1.0F, 0.0F, 1.0F, 1.0F); // north (cyan)
		}

		matrices.push();
		// The catalogue is J2000, so the state carries the whole precession + nutation + horizontal
		// rotation. The vertex buffer stores directions with the pole on -Z (the old convention), so
		// that axis is flipped when the matrix is handed to the GPU.
		double[] star = state.starMatrix;
		Matrix4f rotation = new Matrix4f(
			(float) star[0], (float) star[1], (float) -star[2], 0.0F,
			(float) star[3], (float) star[4], (float) -star[5], 0.0F,
			(float) star[6], (float) star[7], (float) -star[8], 0.0F,
			0.0F, 0.0F, 0.0F, 1.0F);
		matrices.multiplyPositionMatrix(rotation);

		//? if <1.21.4
		RenderSystem.setShader(() -> shader);
		RenderSystem.setShaderColor((float) env, (float) env, (float) env, 1.0F);
		RenderSystem.disableDepthTest();
		RenderSystem.disableCull();
		buffer.bind();
		buffer.draw(matrices.peek().getPositionMatrix(), projection, shader);
		buffer.unbind();
		RenderSystem.enableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		matrices.pop();

		// Drawn after the catalogue so the beacon is never hidden behind a star quad of its own.
		if (hasMarkedStar()) {
			double[] base = Precession.equatorialUnitVector(markedRaDeg, markedDecDeg);
			double[] world = Precession.apply(state.starMatrix, base[0], base[1], base[2]);
			drawMarkedStar(matrices, shader, world, env);
		}
		return true;
	}

	/**
	 * Draws the beacon for the marked star: a soft pulsing halo, a warm core and a white centre, so
	 * it stands out against both the star field and a moonlit sky.
	 */
	private static void drawMarkedStar(MatrixStack matrices, ShaderProgram shader, double[] world,
			double env) {
		Vector3f direction = new Vector3f((float) world[0], (float) world[1], (float) world[2]);
		float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 190.0));
		// Kept partly visible in daylight so a star marked at night can still be found by day.
		float brightness = (float) Math.max(0.45, Math.min(1.0, env));
		RenderSystem.disableDepthTest();
		RenderSystem.disableCull();
		drawQuad(matrices, shader, direction, 13.0F + 5.0F * pulse, 1.0F, 0.85F, 0.25F,
			(0.18F + 0.16F * pulse) * brightness);
		drawQuad(matrices, shader, direction, 5.5F, 1.0F, 0.93F, 0.55F, 0.85F * brightness);
		drawQuad(matrices, shader, direction, 2.4F, 1.0F, 1.0F, 1.0F, brightness);
		RenderSystem.enableCull();
		RenderSystem.enableDepthTest();
	}

	/** Full-brightness, unrotated diagnostic marker (a coloured square) at a fixed world direction. */
	private static void drawMarker(MatrixStack matrices, ShaderProgram shader, float dx, float dy, float dz, float r, float g, float b) {
		drawQuad(matrices, shader, new Vector3f(dx, dy, dz), 8.0F, r, g, b, 1.0F);
	}

	/** A camera-facing square of half-extent {@code half} at radius {@link #RADIUS}, centred on a world direction. */
	private static void drawQuad(MatrixStack matrices, ShaderProgram shader, Vector3f direction,
			float half, float r, float g, float b, float alpha) {
		//? if <1.21.4
		RenderSystem.setShader(() -> shader);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS,
			VertexFormats.POSITION_COLOR);
		appendQuad(builder, matrices.peek().getPositionMatrix(), direction, half, r, g, b, alpha);
		net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(builder.end());
	}

	/** Appends one camera-facing square centred on a world direction to an open batch. */
	private static void appendQuad(BufferBuilder builder, Matrix4f m, Vector3f direction, float half,
			float r, float g, float b, float alpha) {
		Vector3f dir = new Vector3f(direction).normalize();
		Vector3f center = new Vector3f(dir).mul(RADIUS);
		Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);
		up.fma(-dir.dot(up), dir);
		if (up.lengthSquared() < 1.0E-6F) {
			up.set(1.0F, 0.0F, 0.0F);
		}
		up.normalize();
		Vector3f right = new Vector3f(dir).cross(up).normalize();
		builder.vertex(m, center.x - right.x * half - up.x * half, center.y - right.y * half - up.y * half, center.z - right.z * half - up.z * half).color(r, g, b, alpha);
		builder.vertex(m, center.x + right.x * half - up.x * half, center.y + right.y * half - up.y * half, center.z + right.z * half - up.z * half).color(r, g, b, alpha);
		builder.vertex(m, center.x + right.x * half + up.x * half, center.y + right.y * half + up.y * half, center.z + right.z * half + up.z * half).color(r, g, b, alpha);
		builder.vertex(m, center.x - right.x * half + up.x * half, center.y - right.y * half + up.y * half, center.z - right.z * half + up.z * half).color(r, g, b, alpha);
	}

	/** Drops the cached buffer so the next frame rebuilds it (e.g. after a config change). */
	public static void invalidate() {
		if (buffer != null) {
			buffer.close();
			buffer = null;
		}
		built = false;
		catalogMissing = false;
		StarCatalog.invalidate();
	}

	private static boolean ensureBuilt() {
		if (built) {
			return true;
		}
		if (catalogMissing) {
			return false;
		}
		StarCatalog catalog = StarCatalog.load();
		if (catalog == null) {
			catalogMissing = true;
			return false;
		}
		build(catalog);
		built = true;
		return true;
	}

	private static void build(StarCatalog catalog) {
		BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
		float[] rgb = new float[3];
		drawnStars = 0;
		for (int i = 0; i < catalog.count; i++) {
			if (catalog.magnitude[i] > (float) StarRadianceConfig.get().maxRenderMagnitude) {
				continue;
			}
			drawnStars++;
			double a = Math.toRadians(catalog.raDeg[i]);
			double d = Math.toRadians(catalog.decDeg[i]);
			double cosD = Math.cos(d);
			Vector3f base = new Vector3f(
				(float) (Math.cos(a) * cosD),
				(float) (Math.sin(a) * cosD),
				(float) -Math.sin(d));

			colorFromBv(catalog.bv[i], rgb);
			float alpha = luminance(catalog.magnitude[i]);
			float half = sizeScale(catalog.magnitude[i]);

			Vector3f center = new Vector3f(base).mul(RADIUS);
			Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);
			Vector3f t1 = new Vector3f(base).cross(up);
			if (t1.lengthSquared() < 1.0E-6F) {
				up.set(0.0F, 0.0F, 1.0F);
				t1 = new Vector3f(base).cross(up);
			}
			t1.normalize();
			Vector3f t2 = new Vector3f(base).cross(t1).normalize();

			emitCorner(builder, center, t1, t2, half, half, rgb, alpha);
			emitCorner(builder, center, t1, t2, -half, half, rgb, alpha);
			emitCorner(builder, center, t1, t2, -half, -half, rgb, alpha);
			emitCorner(builder, center, t1, t2, half, -half, rgb, alpha);
		}
		buffer = new VertexBuffer(
			//? if >=1.21.4 {
			/*net.minecraft.client.gl.GlUsage.STATIC
			*///?} else {
			VertexBuffer.Usage.STATIC
			//?}
		);
		net.minecraft.client.render.BuiltBuffer builtBuffer = builder.end();
		buffer.bind();
		buffer.upload(builtBuffer);
		VertexBuffer.unbind();
	}

	private static void emitCorner(BufferBuilder builder, Vector3f center, Vector3f t1, Vector3f t2, float u, float v, float[] rgb, float alpha) {
		float x = center.x + t1.x * u + t2.x * v;
		float y = center.y + t1.y * u + t2.y * v;
		float z = center.z + t1.z * u + t2.z * v;
		builder.vertex(x, y, z).color(rgb[0], rgb[1], rgb[2], alpha);
	}

	/** Visible brightness 0..1 from apparent magnitude (mag 3.5 ⇒ 1, faintest stars get a small floor). */
	public static float luminance(float magnitude) {
		double lum = Math.pow(10.0, 0.4 * (3.5 - magnitude));
		return (float) Math.max(0.03, Math.min(1.0, lum));
	}

	/** World half-extent of a star's quad at radius 100 (brighter ⇒ bigger). */
	public static float sizeScale(float magnitude) {
		return (float) Math.max(0.03, Math.min(0.21, 0.21 - 0.028 * magnitude));
	}

	/** Linear colour interpolation along the Johnson B-V axis. */
	public static void colorFromBv(float bv, float[] out) {
		float v = Math.max(-0.4F, Math.min(2.0F, bv));
		float[][] anchors = {
			{ -0.4F, 0.71F, 0.80F, 1.00F },
			{ 0.0F, 0.97F, 0.97F, 1.00F },
			{ 0.3F, 1.00F, 0.95F, 0.84F },
			{ 0.6F, 1.00F, 0.85F, 0.62F },
			{ 1.0F, 1.00F, 0.72F, 0.45F },
			{ 1.5F, 1.00F, 0.62F, 0.35F },
			{ 2.0F, 1.00F, 0.55F, 0.32F },
		};
		float[] prev = anchors[0];
		for (int i = 1; i < anchors.length; i++) {
			float[] next = anchors[i];
			if (v <= next[0]) {
				float t = (v - prev[0]) / Math.max(1.0E-6F, next[0] - prev[0]);
				out[0] = prev[1] + (next[1] - prev[1]) * t;
				out[1] = prev[2] + (next[2] - prev[2]) * t;
				out[2] = prev[3] + (next[3] - prev[3]) * t;
				return;
			}
			prev = next;
		}
		out[0] = prev[1];
		out[1] = prev[2];
		out[2] = prev[3];
	}
}
