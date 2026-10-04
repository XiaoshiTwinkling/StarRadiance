package com.xiaoshi.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Draws StarRadiance's own smooth zenith-to-horizon sky dome.
 *
 * <p>This deliberately does <em>not</em> use vanilla's {@code position_color} program. Iris remaps
 * that program to the shaderpack's {@code gbuffers_skybasic} while a pack is active, and packs like
 * Complementary compute their own sky gradient and their own procedural stars per fragment,
 * ignoring the geometry we submit (and even discarding vanilla-style star quads). So the dome and
 * the starfield go through our own core shader ({@code starradiance:sky}) instead; with Iris's
 * "allow unknown shaders" option enabled those draws are passed through to the pack's color target,
 * which means the pack's post-processing still sees them.
 *
 * <p>The vanilla program remains a fallback for the case where our shader failed to load.
 */
public final class SkyDomeRenderer {
	private static volatile ShaderProgram program;

	private SkyDomeRenderer() {
	}

	public static void setProgram(ShaderProgram shaderProgram) {
		program = shaderProgram;
	}

	public static String status() {
		return "skydome shader=" + (program != null ? "ok" : "null");
	}

	public static boolean shaderReady() {
		return program != null;
	}

	/** Draws the dome; returns true when our own core shader could be used for it. */
	public static boolean draw(MatrixStack matrices, Celestial.SkyState state) {
		// The dome shares the star field's core shader (both are plain POSITION_COLOR geometry).
		ShaderProgram shader = currentShader();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		Matrix4f m = matrices.peek().getPositionMatrix();

		double day = SkyPalette.skyBrightness(state);
		double night = 1.0 - day;
		Vec3d zenith = SkyPalette.skyColor(state);
		Vec3d horizon = lerp(zenith, new Vec3d(0.84, 0.80, 0.72), 0.55);
		Vec3d belowHorizon = new Vec3d(0.03, 0.04, 0.08);

		double sunAz = state.sunAzimuthDeg;
		int azimuths = 32;
		int elevSteps = 14;
		double eMin = -8.0;
		double eMax = 90.0;
		double radius = 640.0;

		BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);
		for (int ring = 0; ring < elevSteps; ring++) {
			double e0 = eMin + (eMax - eMin) * ring / elevSteps;
			double e1 = eMin + (eMax - eMin) * (ring + 1) / elevSteps;
			for (int i = 0; i <= azimuths; i++) {
				double a = (i % azimuths) / (double) azimuths * 2.0 * Math.PI;
				skyVertex(builder, m, e0, a, radius, state, sunAz, day, night, zenith, horizon, belowHorizon);
				skyVertex(builder, m, e1, a, radius, state, sunAz, day, night, zenith, horizon, belowHorizon);
			}
		}
		BufferRenderer.drawWithGlobalProgram(builder.end());
		return shader != null;
	}

	/** Binds and returns the dome program; 1.21.4 resolves it from the shared sky key. */
	private static ShaderProgram currentShader() {
		//? if >=1.21.2 {
		/*return RenderSystem.setShader(StarFieldRenderer.SKY_KEY);
		*///?} else {
		ShaderProgram shader = program != null ? program : GameRenderer.getPositionColorProgram();
		RenderSystem.setShader(() -> shader);
		return shader;
		//?}
	}

	private static void skyVertex(BufferBuilder builder, Matrix4f m, double elevationDeg, double azimuthRad, double radius, Celestial.SkyState state, double sunAz, double day, double night, Vec3d zenith, Vec3d horizon, Vec3d belowHorizon) {
		double e = elevationDeg * Math.PI / 180.0;
		double ce = Math.cos(e);
		double dirX = Math.sin(azimuthRad) * ce;
		double dirY = Math.sin(e);
		double dirZ = -Math.cos(azimuthRad) * ce;

		Vec3d color;
		if (elevationDeg < 0.0) {
			color = lerp(belowHorizon, zenith, 0.25 + 0.15 * (elevationDeg + 8.0) / 8.0);
		} else {
			color = lerp(horizon, zenith, Math.min(1.0, elevationDeg / 90.0));
		}
		// Dim toward night.
		color = lerp(color, lerp(new Vec3d(0, 0, 0), color, 0.25), night * 0.85);
		// Warm glow toward the (low) sun.
		double azDeg = azimuthRad * 180.0 / Math.PI;
		double delta = Math.abs(azDeg - sunAz);
		delta = Math.min(delta, 360.0 - delta);
		double warm = Math.exp(-delta / 30.0) * Math.exp(-Math.abs(elevationDeg) / 18.0) * day;
		color = color.add(new Vec3d(0.6 * warm, 0.32 * warm, 0.06 * warm));

		builder.vertex(m, (float) (dirX * radius), (float) (dirY * radius), (float) (dirZ * radius))
			.color((float) color.x, (float) color.y, (float) color.z, 1.0F);
	}

	private static Vec3d lerp(Vec3d a, Vec3d b, double t) {
		t = Math.max(0.0, Math.min(1.0, t));
		return new Vec3d(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
	}
}
