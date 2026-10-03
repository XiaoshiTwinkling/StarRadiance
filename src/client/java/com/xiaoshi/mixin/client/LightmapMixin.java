package com.xiaoshi.mixin.client;

import com.xiaoshi.config.StarRadianceConfig;
import com.xiaoshi.sky.Celestial;
import com.xiaoshi.sky.SkyPalette;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Night-time lightmap contrast, applied while the lightmap colour is computed (before it is packed
 * into the texture — no post-upload rewriting). A smooth night factor (from the real solar altitude)
 * raises the gamma exponent: dark and mid tones fall faster while near-white light sources stay
 * bright, so torch light reads much brighter against darker surroundings. The factor fades in
 * gradually through dusk and out at dawn.
 */
@Mixin(LightmapTextureManager.class)
public abstract class LightmapMixin {
	/** 0 = day, up to ~0.7 = deep night; smoothed by the real solar altitude. */
	@Unique
	private float starradiance$nightContrast;

	@Inject(method = "update", at = @At("HEAD"))
	private void starradiance$computeNightFactor(float tickDelta, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			this.starradiance$nightContrast = 0.0F;
			return;
		}
		StarRadianceConfig cfg = StarRadianceConfig.get();
		if (!cfg.darkerNights) {
			this.starradiance$nightContrast = 0.0F;
			return;
		}
		double lat = client.player != null ? Celestial.latitudeOf(client.player.getZ()) : Celestial.TROPIC_LATITUDE;
		Celestial.SkyState state = Celestial.compute(client.world.getTimeOfDay(), lat);
		double night = 1.0 - SkyPalette.skyBrightness(state);
		if (night <= 0.02) {
			this.starradiance$nightContrast = 0.0F;
			return;
		}
		this.starradiance$nightContrast = (float) (0.85 * cfg.nightDarkness * night);
	}

	/** Applies the extra gamma to each tone-mapped colour channel (value in 0..1). */
	@Inject(method = "easeOutQuart", at = @At("RETURN"), cancellable = true)
	private void starradiance$darkenDark(CallbackInfoReturnable<Float> cir) {
		float contrast = this.starradiance$nightContrast;
		if (contrast <= 0.0F) {
			return;
		}
		float value = cir.getReturnValueF();
		if (value <= 0.0F) {
			cir.setReturnValue(0.0F);
			return;
		}
		cir.setReturnValue((float) Math.pow(value, 1.0F + contrast));
	}
}
