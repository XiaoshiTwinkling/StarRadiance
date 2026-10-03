package com.xiaoshi.sky;

import com.xiaoshi.astro.EclipseCalculator;
import com.xiaoshi.config.StarRadianceConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws the Moon as a real UV sphere textured with NASA LROC imagery, lit per-pixel by the actual
 * sun direction so real phases and the terminator emerge from geometry alone.
 *
 * <p>The sphere geometry is a unit sphere built once into a static {@link VertexBuffer}; the drawn
 * size is {@code sunMoonScale ×} the true angular semidiameter of this frame, so the Moon's apparent
 * size follows its real distance. During a lunar eclipse the Earth's shadow is applied per fragment
 * from the real umbra/penumbra geometry (again magnified with the disc), which keeps the eclipse
 * shape correct.
 */
public final class MoonRenderer {
	private static final double DISTANCE = 100.0;

	private static final int LON_SEGMENTS = 96;
	private static final int LAT_SEGMENTS = 48;

	private static VertexBuffer buffer;
	private static boolean built;
	private static boolean shaderBroken;

	/** Set by {@link StarRadianceClient} from {@code CoreShaderRegistrationCallback}; null before load. */
	private static volatile ShaderProgram program;

	public static void setProgram(ShaderProgram shaderProgram) {
		program = shaderProgram;
		shaderBroken = false;
	}

	/** Number of vertices in the sphere buffer (for the debug HUD). */
	public static int vertexCount;

	public static String status() {
		return "moon built=" + built + " verts=" + vertexCount + " shader="
			+ (program != null ? (shaderBroken ? "broken" : "ok") : "null");
	}

	public static boolean shaderReady() {
		return program != null && !shaderBroken;
	}

	public static double sizeScale() {
		return Math.max(1.0, Math.min(40.0, StarRadianceConfig.get().sunMoonScale));
	}

	/** Drawn angular semidiameter for this frame. */
	public static double drawnAngularRadiusDeg(Celestial.SkyState state) {
		return state.moonAngularRadiusDeg * sizeScale();
	}

	private MoonRenderer() {
	}

	/**
	 * Draws the sphere at the moon's direction. Returns false when the caller should draw the
	 * vanilla billboard instead (shader not ready, or a draw fault).
	 *
	 * @param brightness extra gain on the lunar albedo while a shaderpack renders an HDR frame.
	 * @param alpha      glare fade (0 = hidden behind the Sun's disc).
	 */
	public static boolean draw(MatrixStack matrices, Matrix4f projection, Celestial.SkyState state,
			float cloud, float brightness, float alpha) {
		if (program == null || shaderBroken || !ensureBuilt()) {
			return false;
		}
		try {
			Vector3f dir = new Vector3f((float) state.moonX, (float) state.moonY, (float) state.moonZ).normalize();

			// Local frame: +Z is the sub-Earth point (u=0.5,v=0.5), +Y the moon's north pole. The
			// pole is projected onto the celestial pole so the moon carries the seasonal roll.
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
			Vector3f back = new Vector3f(dir).negate();

			Matrix4f rot = new Matrix4f(
				right.x, right.y, right.z, 0.0F,
				up.x, up.y, up.z, 0.0F,
				back.x, back.y, back.z, 0.0F,
				0.0F, 0.0F, 0.0F, 1.0F);

			// Sun direction in the moon's local frame: R^T * sun.
			Vector3f sun = new Vector3f((float) state.sunX, (float) state.sunY, (float) state.sunZ).normalize();
			Vector3f sunLocal = new Vector3f(right.dot(sun), up.dot(sun), back.dot(sun)).normalize();

			// "Always visible" guarantee: never let the Moon go fully dark. The local +Z axis is the
			// sub-Earth point, so the illuminated fraction is (1 + sunLocal.z) / 2; if it would fall
			// below the configured minimum, rotate the lighting direction towards the observer by the
			// missing angle. Skipped during a real eclipse, which must keep its true geometry.
			double minIllumination = Math.max(0.0, Math.min(0.45, StarRadianceConfig.get().moonMinIllumination));
			if (state.eclipseKind == EclipseCalculator.NONE && minIllumination > 0.0) {
				float minZ = (float) (2.0 * minIllumination - 1.0);
				if (sunLocal.z < minZ) {
					float angle = (float) Math.acos(Math.max(-1.0F, Math.min(1.0F, sunLocal.z)));
					float target = (float) Math.acos(minZ);
					Vector3f axis = new Vector3f(0.0F, 0.0F, 1.0F).cross(sunLocal);
					if (axis.lengthSquared() < 1.0E-8F) {
						axis.set(1.0F, 0.0F, 0.0F);
					}
					axis.normalize();
					sunLocal.rotateAxis(target - angle, axis.x, axis.y, axis.z);
				}
			}

			// Lunar eclipse reddens and dims the moon; the tint rides in MoonParams.rgb.
			float[] tint = { 1.0F, 1.0F, 1.0F };
			if (StarRadianceConfig.get().eclipseEffects && state.eclipseKind == EclipseCalculator.LUNAR
				&& state.eclipseMagnitude > 0.0) {
				float m = (float) Math.min(1.0, state.eclipseMagnitude);
				tint[0] = lerp(1.0F, 0.62F, m);
				tint[1] = lerp(1.0F, 0.30F, m);
				tint[2] = lerp(1.0F, 0.22F, m);
			}
			float normalStrength = StarRadianceConfig.get().normalMappedMoon ? 2.0F : 0.0F;

			// Earth's shadow geometry in moon-radius units (the scale factor cancels out, so the
			// shadow shape stays correct even though the sphere is drawn enlarged).
			float shadowX = 0.0F;
			float shadowY = 0.0F;
			float umbraRatio = 0.0F;
			float penumbraRatio = 0.0F;
			if (state.eclipseKind == EclipseCalculator.LUNAR && state.moonAngularRadiusDeg > 0.0) {
				Vector3f antisolar = new Vector3f(sun).negate();
				double separation = Math.acos(Math.max(-1.0, Math.min(1.0, dir.dot(antisolar))));
				double moonRadiusRad = Math.toRadians(state.moonAngularRadiusDeg);
				shadowX = (float) (right.dot(antisolar) * separation / moonRadiusRad);
				shadowY = (float) (up.dot(antisolar) * separation / moonRadiusRad);
				umbraRatio = (float) (state.umbraRadiusDeg / state.moonAngularRadiusDeg);
				penumbraRatio = (float) (state.penumbraRadiusDeg / state.moonAngularRadiusDeg);
			}

			double radiusDeg = drawnAngularRadiusDeg(state);
			float worldRadius = (float) (DISTANCE * Math.tan(Math.toRadians(radiusDeg)));

			Matrix4f modelView = new Matrix4f(matrices.peek().getPositionMatrix());
			modelView.translate(dir.x * (float) DISTANCE, dir.y * (float) DISTANCE, dir.z * (float) DISTANCE);
			modelView.scale(worldRadius);
			modelView.mul(rot);

			Identifier albedo = MoonTextures.albedo();
			Identifier normal = MoonTextures.normal();

			net.minecraft.client.gl.GlUniform sunU = program.getUniform("SunDirLocal");
			if (sunU != null) {
				sunU.set(sunLocal);
			}
			net.minecraft.client.gl.GlUniform paramsU = program.getUniform("MoonParams");
			if (paramsU != null) {
				paramsU.set(tint[0], tint[1], tint[2], normalStrength);
			}
			net.minecraft.client.gl.GlUniform shadowU = program.getUniform("MoonShadow");
			if (shadowU != null) {
				shadowU.set(shadowX, shadowY, umbraRatio, penumbraRatio);
			}

			com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, albedo);
			com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(1, normal);
			float level = cloud * brightness;
			com.mojang.blaze3d.systems.RenderSystem.setShaderColor(level, level, level, alpha);
			com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(
				com.mojang.blaze3d.platform.GlStateManager.SrcFactor.SRC_ALPHA,
				com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA,
				com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE,
				com.mojang.blaze3d.platform.GlStateManager.DstFactor.ZERO);
			com.mojang.blaze3d.systems.RenderSystem.enableCull();
			com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
			buffer.bind();
			buffer.draw(modelView, projection, program);
			buffer.unbind();
			// Restore the additive blend the sky pass uses for the glow bodies.
			com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(
				com.mojang.blaze3d.platform.GlStateManager.SrcFactor.SRC_ALPHA,
				com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE,
				com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE,
				com.mojang.blaze3d.platform.GlStateManager.DstFactor.ZERO);
			com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
			return true;
		} catch (RuntimeException exception) {
			shaderBroken = true;
			return false;
		}
	}

	/** Drops the cached buffer so the next frame rebuilds it (e.g. after a config change). */
	public static void invalidate() {
		if (buffer != null) {
			buffer.close();
			buffer = null;
		}
		built = false;
		shaderBroken = false;
	}

	private static boolean ensureBuilt() {
		if (built) {
			return true;
		}
		build();
		built = true;
		return true;
	}

	private static void build() {
		BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_TEXTURE);
		int verts = 0;
		for (int iy = 0; iy < LAT_SEGMENTS; iy++) {
			float v0 = iy / (float) LAT_SEGMENTS;
			float v1 = (iy + 1) / (float) LAT_SEGMENTS;
			for (int ix = 0; ix < LON_SEGMENTS; ix++) {
				float u0 = ix / (float) LON_SEGMENTS;
				float u1 = (ix + 1) / (float) LON_SEGMENTS;
				emit(builder, u0, v0);
				emit(builder, u0, v1);
				emit(builder, u1, v1);
				emit(builder, u0, v0);
				emit(builder, u1, v1);
				emit(builder, u1, v0);
				verts += 6;
			}
		}
		vertexCount = verts;
		buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
		BuiltBuffer builtBuffer = builder.end();
		buffer.bind();
		buffer.upload(builtBuffer);
		VertexBuffer.unbind();
	}

	/** Unit sphere; u∈[0,1] maps to longitude -180°..180° (u=0.5 ⇒ 0°, the sub-Earth meridian). */
	private static void emit(BufferBuilder builder, float u, float v) {
		double phi = Math.PI / 2.0 - Math.PI * v;
		double lambda = 2.0 * Math.PI * u - Math.PI;
		double cosPhi = Math.cos(phi);
		float x = (float) (Math.sin(lambda) * cosPhi);
		float y = (float) Math.sin(phi);
		float z = (float) (Math.cos(lambda) * cosPhi);
		builder.vertex(x, y, z).texture(u, v);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}
}
