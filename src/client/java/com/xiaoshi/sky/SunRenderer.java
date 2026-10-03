package com.xiaoshi.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xiaoshi.astro.EclipseCalculator;
import com.xiaoshi.config.StarRadianceConfig;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws the Sun as a limb-darkened disk with a soft corona, using the custom
 * {@code starradiance:sun} shader.
 *
 * <p>The disk is drawn at {@code sunMoonScale ×} the true angular semidiameter (the real Sun is
 * ~0.27° across), so it stays visible while its geometry still follows the ephemeris. During a solar
 * eclipse the Moon's silhouette is carved out of the disk by the shader, using the real relative
 * geometry (offset and radius ratio), which keeps the eclipse shape correct even though the disks
 * themselves are enlarged.
 */
public final class SunRenderer {
	private static final double DISTANCE = 100.0;
	/** Disk radius as a fraction of the quad half-size, mirrored from sun.json / sun.fsh. */
	private static final double DISK_FRACTION = 0.28;

	private static volatile ShaderProgram program;
	private static boolean shaderBroken;
	private static boolean loggedNull;

	public static void setProgram(ShaderProgram shaderProgram) {
		program = shaderProgram;
		shaderBroken = false;
	}

	public static String status() {
		return "sun shader=" + (program != null ? (shaderBroken ? "broken" : "ok") : "null");
	}

	public static boolean shaderReady() {
		return program != null && !shaderBroken;
	}

	public static double sizeScale() {
		return Math.max(1.0, Math.min(40.0, StarRadianceConfig.get().sunMoonScale));
	}

	/** Drawn angular semidiameter for this frame. */
	public static double drawnAngularRadiusDeg(Celestial.SkyState state) {
		return state.sunAngularRadiusDeg * sizeScale();
	}

	private SunRenderer() {
	}

	public static boolean draw(MatrixStack matrices, Matrix4f projection, Celestial.SkyState state, float cloud) {
		if (program == null || shaderBroken) {
			if (!loggedNull) {
				loggedNull = true;
				com.xiaoshi.StarRadiance.LOGGER.warn("Sun: custom shader unavailable (program={}, broken={})",
					program != null, shaderBroken);
			}
			return false;
		}
		try {
			Vector3f dir = new Vector3f((float) state.sunX, (float) state.sunY, (float) state.sunZ).normalize();
			Vector3f center = new Vector3f(dir).mul((float) DISTANCE);

			double latRad = Math.toRadians(state.latitudeDeg);
			Vector3f pole = new Vector3f(0.0F, (float) Math.sin(latRad), (float) -Math.cos(latRad));
			Vector3f up = new Vector3f(pole);
			up.fma(-dir.dot(pole), dir);
			if (up.lengthSquared() < 1.0E-6F) {
				up.set(0.0F, 0.0F, 1.0F).fma(-dir.dot(0.0F, 0.0F, 1.0F), dir);
				if (up.lengthSquared() < 1.0E-6F) {
					up.set(1.0F, 0.0F, 0.0F);
				}
			}
			up.normalize();
			Vector3f right = new Vector3f(dir).cross(up).normalize();

			double radiusDeg = drawnAngularRadiusDeg(state);
			double radiusWorld = DISTANCE * Math.tan(Math.toRadians(radiusDeg));
			double half = radiusWorld / DISK_FRACTION;

			// Solar eclipse: place the Moon's silhouette using the true radius ratio and the real
			// separation, both expressed in units of the Sun's own semidiameter.
			float shadowX = 0.0F;
			float shadowY = 0.0F;
			float shadowRatio = 0.0F;
			float shadowOn = 0.0F;
			if (state.eclipseKind == EclipseCalculator.SOLAR && state.sunAngularRadiusDeg > 0.0) {
				Vector3f moonDir = new Vector3f((float) state.moonX, (float) state.moonY, (float) state.moonZ)
					.normalize();
				double separation = Math.acos(Math.max(-1.0, Math.min(1.0, dir.dot(moonDir))));
				double sunRadiusRad = Math.toRadians(state.sunAngularRadiusDeg);
				shadowX = (float) (right.dot(moonDir) * separation / sunRadiusRad);
				shadowY = (float) (up.dot(moonDir) * separation / sunRadiusRad);
				shadowRatio = (float) (state.moonAngularRadiusDeg / state.sunAngularRadiusDeg);
				shadowOn = 1.0F;
			}

			// Solar eclipse dimming (the shader already removes the covered part of the disk).
			float eclipse = 1.0F;
			float glow = 0.25F;
			if (StarRadianceConfig.get().eclipseEffects && state.eclipseKind == EclipseCalculator.SOLAR
				&& state.eclipseMagnitude > 0.0) {
				float m = (float) Math.min(1.0, state.eclipseMagnitude);
				eclipse = 1.0F - 0.96F * m;
				glow = 0.25F * (1.0F - 0.8F * m);
			}

			net.minecraft.client.gl.GlUniform params = program.getUniform("SunParams");
			if (params != null) {
				params.set(eclipse * cloud, glow, (float) DISK_FRACTION, 1.0F);
			}
			net.minecraft.client.gl.GlUniform shadow = program.getUniform("SunShadow");
			if (shadow != null) {
				shadow.set(shadowX, shadowY, shadowRatio, shadowOn);
			}

			Matrix4f m = matrices.peek().getPositionMatrix();
			BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
			addCorner(builder, m, center, right, up, half, 0.0F, 0.0F);
			addCorner(builder, m, center, right, up, half, 1.0F, 0.0F);
			addCorner(builder, m, center, right, up, half, 1.0F, 1.0F);
			addCorner(builder, m, center, right, up, half, 0.0F, 1.0F);
			RenderSystem.setShader(() -> program);
			BufferRenderer.drawWithGlobalProgram(builder.end());
			return true;
		} catch (RuntimeException exception) {
			shaderBroken = true;
			com.xiaoshi.StarRadiance.LOGGER.warn("Sun: custom draw failed, falling back to vanilla quad", exception);
			return false;
		}
	}

	private static void addCorner(BufferBuilder builder, Matrix4f matrix, Vector3f center, Vector3f right,
			Vector3f up, double half, float u, float v) {
		double sx = (u == 1.0F ? half : -half);
		double sy = (v == 1.0F ? half : -half);
		float x = center.x + right.x * (float) sx + up.x * (float) sy;
		float y = center.y + right.y * (float) sx + up.y * (float) sy;
		float z = center.z + right.z * (float) sx + up.z * (float) sy;
		builder.vertex(matrix, x, y, z).texture(u, v);
	}
}
