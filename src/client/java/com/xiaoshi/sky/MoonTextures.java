package com.xiaoshi.sky;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.xiaoshi.StarRadiance;
import com.xiaoshi.config.StarRadianceConfig;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/**
 * Resolves, mipmaps and registers the Moon's albedo/normal maps.
 *
 * <p>Minecraft's ordinary resource textures are uploaded without mipmaps, so a 4K map minified
 * onto a ~300 px moon on screen samples roughly one texel per pixel and aliases into visible
 * noise. Here we build a full mip chain on the CPU ({@link MipmapHelper}), upload every level and
 * switch to trilinear filtering, which is what makes the moon read as a surface instead of dots.
 *
 * <p>Re-registered on every client resource reload.
 */
public final class MoonTextures {
	private static volatile Identifier albedoId = Identifier.of("starradiance", "textures/environment/moon_albedo_4k.png");
	private static volatile Identifier normalId = Identifier.of("starradiance", "textures/environment/moon_normal_4k.png");

	private MoonTextures() {
	}

	/** The albedo map actually in use (resolved to the best shipped resolution). */
	public static Identifier albedo() {
		return albedoId;
	}

	/** The normal map actually in use. */
	public static Identifier normal() {
		return normalId;
	}

	public static void register(ResourceManager manager) {
		int quality = (int) Math.round(StarRadianceConfig.get().moonTextureQuality);
		albedoId = resolve(manager, "moon_albedo", quality);
		normalId = resolve(manager, "moon_normal", quality);
		registerOne(albedoId);
		registerOne(normalId);
	}

	private static void registerOne(Identifier id) {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if >=1.21.4 {
		/*if (client.getTextureManager().getTexture(id) != null) {
			return;
		}*/
		//?} else {
		if (client.getTextureManager().getOrDefault(id, null) != null) {
			return;
		}
		//?}
		client.getTextureManager().registerTexture(id, new MipmappedTexture(id));
		StarRadiance.LOGGER.info("Registered mipmapped moon texture {}", id);
	}

	/** Highest available resolution at or below the configured quality. */
	private static Identifier resolve(ResourceManager manager, String base, int quality) {
		int[] candidates = quality >= 8 ? new int[] { 8, 4, 2 } : quality >= 4 ? new int[] { 4, 2 } : new int[] { 2 };
		for (int k : candidates) {
			Identifier id = Identifier.of("starradiance", "textures/environment/" + base + "_" + k + "k.png");
			if (manager.getResource(id).isPresent()) {
				return id;
			}
		}
		return Identifier.of("starradiance", "textures/environment/" + base + "_2k.png");
	}

	/** A texture that uploads a full CPU-built mip chain and filters trilinearly. */
	private static final class MipmappedTexture
		//? if >=1.21.4 {
		/*extends net.minecraft.client.texture.ReloadableTexture*/
		//?} else {
		extends AbstractTexture
		//?}
	{
		//? if <1.21.4 {
		private final Identifier location;
		//?}

		MipmappedTexture(Identifier location) {
			//? if >=1.21.4 {
			/*super(location);*/
			//?} else {
			this.location = location;
			//?}
		}

		//? if >=1.21.4 {
		/*@Override
		public net.minecraft.client.texture.TextureContents loadContents(ResourceManager manager)
				throws IOException {
			return net.minecraft.client.texture.TextureContents.load(manager, getId());
		}*/
		//?} else {
		@Override
		public void load(ResourceManager manager) throws IOException {
			NativeImage image = null;
			try {
				var resource = manager.getResourceOrThrow(this.location);
				try (InputStream stream = resource.getInputStream()) {
					image = NativeImage.read(stream);
				}

				int width = image.getWidth();
				int height = image.getHeight();
				// Allocate level 0..maxLevel in one call, upload only level 0, then let the driver fill
				// the rest. Manufacturing/uploading each level by hand trips a fault on some Intel GPUs.
				int maxLevel = Integer.numberOfTrailingZeros(Integer.highestOneBit(Math.min(width, height)));

				TextureUtil.prepareImage(NativeImage.InternalFormat.RGBA, this.getGlId(), maxLevel, width, height);
				GlStateManager._bindTexture(this.getGlId());
				image.upload(0, 0, 0, false);
				org.lwjgl.opengl.GL30C.glGenerateMipmap(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);

				// Trilinear minification so the 4K map doesn't alias into dots when the moon is small.
				this.setFilter(true, true);
			} catch (IOException | RuntimeException exception) {
				StarRadiance.LOGGER.warn("Failed to load mipmapped moon texture {}", this.location, exception);
				throw exception instanceof IOException io ? io : new IOException(exception);
			} finally {
				if (image != null) {
					image.close();
				}
			}
		}
		//?}
	}
}
