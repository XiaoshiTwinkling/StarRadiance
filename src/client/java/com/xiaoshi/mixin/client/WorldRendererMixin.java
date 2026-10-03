package com.xiaoshi.mixin.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.xiaoshi.astro.EclipseCalculator;
import com.xiaoshi.config.StarRadianceConfig;
import com.xiaoshi.iris.IrisCompat;
import com.xiaoshi.sky.Celestial;
import com.xiaoshi.sky.MoonRenderer;
import com.xiaoshi.sky.SkyDomeRenderer;
import com.xiaoshi.sky.StarFieldRenderer;
import com.xiaoshi.sky.SunRenderer;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla's celestial drawing (sky, sun, moon, stars) with the StarRadiance star-sky
 * system, including while an Iris shaderpack is active. Only the overworld NORMAL sky type is
 * affected, and only when we are not underwater/in fog/blind (those paths keep vanilla).
 *
 * <p>Under a shaderpack the pack owns renderSky: its {@code gbuffers_skybasic} program computes a
 * procedural sky (and its own stars) from the fragment's screen position and ignores whatever
 * geometry we submit, while {@code gbuffers_skytextured} may discard the sun/moon outright
 * (Complementary does exactly that in its "custom sun/moon" mode). There is no Iris API to give a
 * pack our geometry and have the pack shade it as our content. So StarRadiance renders its own sky
 * with its own core shaders and cancels renderSky, which removes the pack's sky entirely; Iris's
 * "allow unknown shaders" option (switched on by {@link IrisCompat}) lets those draws through and
 * binds them to the pack's color target, so the pack's post-processing still applies.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
	private static final Identifier SUN_TEXTURE = Identifier.of("minecraft", "textures/environment/sun.png");
	private static final Identifier MOON_PHASES_TEXTURE = Identifier.of("minecraft", "textures/environment/moon_phases.png");

	@Shadow
	private ClientWorld world;

	@Shadow
	@Final
	private MinecraftClient client;

	@Shadow
	private VertexBuffer starsBuffer;

	@Shadow
	protected abstract boolean hasBlindnessOrDarkness(Camera camera);

	/**
	 * Substitutes our baked sun/moon textures for vanilla's inside {@code renderSky}. This only
	 * matters when the user hands the sky back to the pack ({@code customSkyWithShaders = false}):
	 * packs that still sample vanilla's celestial sprites then draw our circular sun and
	 * phase-correct NASA moon instead of the vanilla ones.
	 */
	@Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/util/Identifier;)V"))
	private void starradiance$substituteCelestialTexture(int unit, Identifier texture) {
		if (!StarRadianceConfig.get().customSkyWithShaders && IrisCompat.shaderPackActive()) {
			Identifier swapped = com.xiaoshi.sky.CelestialTextures.substitute(texture);
			if (!swapped.equals(texture)) {
				com.xiaoshi.sky.CelestialTextures.logSubstitution(texture, swapped);
				texture = swapped;
			}
		}
		RenderSystem.setShaderTexture(unit, texture);
	}

	/**
	 * Keeps the moon's phase consistent with {@link Celestial} while a pack is active. Vanilla picks
	 * the atlas cell from its own 8-day moon cycle, which does not match our 27.3-day model.
	 */
	@Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;getMoonPhase()I"))
	private int starradiance$moonPhase(ClientWorld instance) {
		if (!StarRadianceConfig.get().customSkyWithShaders && IrisCompat.shaderPackActive()) {
			ClientPlayerEntity player = this.client.player;
			double lat = player != null ? Celestial.latitudeOf(player.getZ()) : Celestial.TROPIC_LATITUDE;
			return Celestial.compute(instance.getTimeOfDay(), lat).moonPhase;
		}
		return instance.getMoonPhase();
	}

	@Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
	private void starradiance$renderOurSky(Matrix4f positionMatrix, Matrix4f projectionMatrix, float tickDelta, Camera camera, boolean thickFog, Runnable runnable, CallbackInfo ci) {
		// Hand the sky back to the pack when the user asks for that, or when Iris will not let our
		// own core shaders through: cancelling renderSky without being able to draw would leave a
		// black sky where the pack's sky used to be.
		if (!IrisCompat.customSkyAvailable()) {
			return;
		}
		if (this.world == null || this.world.getDimensionEffects().getSkyType() != net.minecraft.client.render.DimensionEffects.SkyType.NORMAL) {
			return;
		}
		if (thickFog) {
			return;
		}
		CameraSubmersionType sub = camera.getSubmersionType();
		if (sub == CameraSubmersionType.LAVA || sub == CameraSubmersionType.POWDER_SNOW) {
			return;
		}
		if (this.hasBlindnessOrDarkness(camera)) {
			return;
		}
		ci.cancel();
		this.starradiance$renderSky(positionMatrix, projectionMatrix, camera, runnable);
	}

	private void starradiance$renderSky(Matrix4f positionMatrix, Matrix4f projectionMatrix, Camera camera, Runnable runnable) {
		ClientPlayerEntity player = this.client.player;
		double latDeg = player != null ? Celestial.latitudeOf(player.getZ()) : Celestial.TROPIC_LATITUDE;
		Celestial.SkyState state = Celestial.compute(this.world.getTimeOfDay(), latDeg);
		// True only while a pack is active; our sun/moon core shaders are used under a pack now, and
		// the vanilla textured-quad fallback below is kept for when one of those shaders failed.
		boolean underShaderPack = IrisCompat.shaderPackActive();
		StarRadianceConfig cfg = StarRadianceConfig.get();

		MatrixStack matrices = new MatrixStack();
		matrices.multiplyPositionMatrix(positionMatrix);

		// Sky dome: our own smooth zenith->horizon gradient (no vanilla single-colour disc, and no
		// pack procedural sky either). Drawn through our own shader so Iris cannot remap it.
		RenderSystem.depthMask(false);
		SkyDomeRenderer.draw(matrices, state);

		RenderSystem.enableBlend();
		RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE, GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ZERO);

		float rain = this.world.getRainGradient(1.0F);
		float cloud = 1.0F - rain;
		double sizeScale = Math.max(1.0, Math.min(40.0, cfg.sunMoonScale));
		float moonFade = moonGlareFade(state, sizeScale);
		boolean solarEclipse = state.eclipseKind == EclipseCalculator.SOLAR;
		// A risen Sun washes the Moon out: veiling glare from the bright sky lowers its contrast, so
		// the disc is blended further into the sky the brighter the sky gets. Twilight keeps most of
		// the contrast, midday loses it.
		double veil = Math.max(0.0, Math.min(1.0, cfg.daytimeMoonFade))
			* com.xiaoshi.sky.SkyPalette.skyBrightness(state);
		float moonAlpha = moonFade * (float) (1.0 - veil);
		// Nothing below the horizon is visible: the sky pass draws with depth testing off, so without
		// this guard a sun/moon that has already set would still be painted over the dark sky.
		boolean sunVisible = state.sunAltitudeDeg + state.sunAngularRadiusDeg * sizeScale > 0.0;
		double moonAltitude = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, state.moonY))));
		boolean moonVisible = moonAltitude + state.moonAngularRadiusDeg * sizeScale > 0.0;

		// Stars first: the sun/moon are drawn opaquely afterwards and must occlude them.
		starField(matrices, projectionMatrix, state, cloud);

		// Sun: limb-darkened disk with a soft corona (eclipse-aware), through our own shader. The
		// textured-quad fallback below is only used when that shader is missing or broken.
		boolean sunDrawn = true;
		if (sunVisible) {
			sunDrawn = false;
			if (cfg.realisticMoon) {
				matrices.push();
				sunDrawn = SunRenderer.draw(matrices, projectionMatrix, state, cloud);
				matrices.pop();
			}
			if (!sunDrawn) {
				Identifier sunTexture = underShaderPack ? com.xiaoshi.sky.CelestialTextures.SUN : SUN_TEXTURE;
				if (underShaderPack) {
					IrisCompat.setPhase("SUN");
				}
				// The baked sun atlas has its disc at 0.62 of the quad half-size.
				double sunHalf = 100.0 * Math.tan(Math.toRadians(state.sunAngularRadiusDeg * sizeScale)) / 0.62;
				drawBody(matrices, projectionMatrix, state.sunX, state.sunY, state.sunZ, 100.0, sunHalf, state.latitudeDeg, sunTexture, 0.0F, 0.0F, 1.0F, 1.0F, cloud);
			}
		}

		// Moon: a real textured sphere lit per-pixel by the sun (real phases), through our own shader.
		// During a solar eclipse the Sun's shader already carves the Moon's silhouette out of the
		// disk using the true relative geometry, so the sphere is skipped and no second, misplaced
		// disc appears.
		boolean moonDrawn = solarEclipse || !moonVisible;
		if (moonVisible && !solarEclipse && cfg.realisticMoon) {
			matrices.push();
			// A pack's pipeline is HDR + tonemapped and its own moon sits well above 1.0, so the real
			// lunar albedo needs a gain there to read as a moon instead of a faint grey disc.
			float moonBrightness = underShaderPack ? (float) cfg.shaderMoonBrightness : 1.0F;
			moonDrawn = MoonRenderer.draw(matrices, projectionMatrix, state, cloud, moonBrightness, moonAlpha);
			matrices.pop();
		}
		if (!moonDrawn && !solarEclipse && moonVisible) {
			// Same "never fully dark" rule as the 3D moon, so the atlas fallback stays visible too.
			int phase = Celestial.phaseFromElongation(Celestial.clampElongationForMinIllumination(
				state.moonElongationDeg, cfg.moonMinIllumination));
			float u0 = (phase % 4) / 4.0F;
			float v0 = ((phase / 4) % 2) / 2.0F;
			Identifier moonTexture = underShaderPack ? com.xiaoshi.sky.CelestialTextures.MOON : MOON_PHASES_TEXTURE;
			if (underShaderPack) {
				IrisCompat.setPhase("MOON");
			}
			double moonHalf = 100.0 * Math.tan(Math.toRadians(state.moonAngularRadiusDeg * sizeScale));
			drawBody(matrices, projectionMatrix, state.moonX, state.moonY, state.moonZ, 100.0, moonHalf, state.latitudeDeg, moonTexture, u0, v0, u0 + 0.25F, v0 + 0.5F, cloud * moonAlpha);
		}

		if (underShaderPack) {
			// Back to the phase Iris had for renderSky, so the rest of the frame is untouched.
			IrisCompat.setPhase("CUSTOM_SKY");
		}

		runnable.run();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderSystem.disableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.depthMask(true);
	}

	/**
	 * How visible the Moon is against the Sun's glare: 0 when its disc is buried in the Sun's disc,
	 * 1 when the two discs are clear of each other. Vanilla-sized celestial discs overlap for days
	 * around every new Moon, and a crescent drawn on top of the Sun looks broken, so the Moon fades
	 * out as it enters the glare instead. A real solar eclipse is exempt: there the dark disc on the
	 * Sun is exactly what should be seen.
	 */
	private static float moonGlareFade(Celestial.SkyState state, double sizeScale) {
		if (state.eclipseKind == EclipseCalculator.SOLAR) {
			return 1.0F;
		}
		double sunRadius = state.sunAngularRadiusDeg * sizeScale;
		double moonRadius = state.moonAngularRadiusDeg * sizeScale;
		double separation = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0,
			state.sunX * state.moonX + state.sunY * state.moonY + state.sunZ * state.moonZ))));
		double outer = sunRadius + moonRadius;
		double inner = sunRadius * 0.5;
		if (separation >= outer) {
			return 1.0F;
		}
		if (separation <= inner) {
			return 0.0F;
		}
		double t = (separation - inner) / (outer - inner);
		return (float) (t * t * (3.0 - 2.0 * t));
	}

	/** Draws a textured billboard (sun disk or one moon-phase cell) at a world direction. */
	private static void drawBody(MatrixStack matrices, Matrix4f projectionMatrix, double dx, double dy, double dz, double distance, double half, double latDeg, Identifier texture, float u0, float v0, float u1, float v1, float cloud) {
		Vector3f dir = new Vector3f((float) dx, (float) dy, (float) dz).normalize();
		Vector3f center = new Vector3f(dir).mul((float) distance);

		// Natural orientation: the texture "up" tracks the projected celestial pole (the small,
		// seasonally varying roll), plus a fixed aesthetic roll about the view vector.
		double latRad = latDeg * Math.PI / 180.0;
		Vector3f pole = new Vector3f(0.0F, (float) Math.sin(latRad), (float) -Math.cos(latRad));
		Vector3f up = new Vector3f(pole);
		up.fma(-dir.dot(pole), dir);
		if (up.lengthSquared() < 1.0E-6F) {
			up.set(0.0F, 0.0F, 1.0F);
			up.fma(-dir.dot(up), dir);
			if (up.lengthSquared() < 1.0E-6F) {
				up.set(1.0F, 0.0F, 0.0F);
			}
		}
		up.normalize();
		Vector3f right = new Vector3f(dir).cross(up).normalize();

		float roll = (float) Math.toRadians(60.0);
		float cosR = (float) Math.cos(roll);
		float sinR = (float) Math.sin(roll);
		Vector3f rolledRight = new Vector3f();
		rolledRight.set(right.x * cosR + up.x * sinR, right.y * cosR + up.y * sinR, right.z * cosR + up.z * sinR);
		Vector3f rolledUp = new Vector3f();
		rolledUp.set(-right.x * sinR + up.x * cosR, -right.y * sinR + up.y * cosR, -right.z * sinR + up.z * cosR);
		right = rolledRight;
		up = rolledUp;

		RenderSystem.setShader(GameRenderer::getPositionTexProgram);
		RenderSystem.setShaderTexture(0, texture);
		RenderSystem.setShaderColor(cloud, cloud, cloud, 1.0F);

		// Depth testing off, matching the 3D sun/moon renderers. The star pass leaves it enabled, and
		// the celestial bodies are drawn far enough out that anything already in the depth buffer
		// (terrain near the horizon) hides them.
		RenderSystem.disableDepthTest();
		BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
		Matrix4f matrix = matrices.peek().getPositionMatrix();
		// Texture v grows downwards, so v0 is the top of the sprite, which lands on the billboard's
		// +up side, i.e. the pole direction (the moon's north pole).
		addCorner(builder, matrix, center, right, up, -half, -half, u0, v1);
		addCorner(builder, matrix, center, right, up, half, -half, u1, v1);
		addCorner(builder, matrix, center, right, up, half, half, u1, v0);
		addCorner(builder, matrix, center, right, up, -half, half, u0, v0);
		BufferRenderer.drawWithGlobalProgram(builder.end());
		RenderSystem.enableDepthTest();
	}

	private static void addCorner(BufferBuilder builder, Matrix4f matrix, Vector3f center, Vector3f right, Vector3f up, double sx, double sy, float u, float v) {
		float x = center.x + right.x * (float) sx + up.x * (float) sy;
		float y = center.y + right.y * (float) sx + up.y * (float) sy;
		float z = center.z + right.z * (float) sx + up.z * (float) sy;
		builder.vertex(matrix, x, y, z).texture(u, v);
	}

	private void starField(MatrixStack matrices, Matrix4f projectionMatrix, Celestial.SkyState state, float cloud) {
		// Real star catalog (when stars.dat is present); otherwise fall back to vanilla's stars.
		if (StarFieldRenderer.draw(matrices, projectionMatrix, state, cloud)) {
			return;
		}
		double latRad = state.latitudeDeg * Math.PI / 180.0;
		double alt = state.sunAltitudeDeg;
		// Stars brighten once the sun is a few degrees below the horizon.
		double brightness = MathHelper.clamp((-alt - 4.0) / 10.0, 0.0, 1.0) * cloud;

		Vector3f pole = new Vector3f(0.0F, (float) Math.sin(latRad), (float) -Math.cos(latRad));

		matrices.push();
		// Align world -Z to our tilted celestial pole, then spin about it.
		Quaternionf align = new Quaternionf().rotateTo(new Vector3f(0.0F, 0.0F, -1.0F), pole);
		Quaternionf spin = new Quaternionf().rotateAxis((float) (state.starAngleDeg * Math.PI / 180.0), pole);
		align.mul(spin, align);
		matrices.multiply(align);

		RenderSystem.setShaderColor((float) brightness, (float) brightness, (float) brightness, 1.0F);
		net.minecraft.client.render.BackgroundRenderer.clearFog();
		this.starsBuffer.bind();
		this.starsBuffer.draw(matrices.peek().getPositionMatrix(), projectionMatrix, GameRenderer.getPositionProgram());
		this.starsBuffer.unbind();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		matrices.pop();
	}
}
