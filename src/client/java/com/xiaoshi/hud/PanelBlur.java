package com.xiaoshi.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xiaoshi.StarRadiance;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;

/**
 * Frosted-glass background for the star chart's info panel: whatever is already on screen behind
 * the panel is copied out, blurred with a separable Gaussian and drawn back over the panel rect.
 *
 * <p>The blur runs through our own core shader ({@code starradiance:panel_blur}) and two
 * window-sized framebuffers that are reused across frames. Frame allocation only happens when the
 * window size changes. If the shader is unavailable (or the framebuffers cannot be created) the
 * caller keeps its plain translucent fill, so the panel stays readable either way.
 */
public final class PanelBlur {
	private static volatile ShaderProgram program;
	private static SimpleFramebuffer horizontal;
	private static SimpleFramebuffer vertical;
	private static int bufferWidth = -1;
	private static int bufferHeight = -1;

	private PanelBlur() {
	}

	public static void setProgram(ShaderProgram shader) {
		program = shader;
	}

	public static boolean ready() {
		return program != null;
	}

	/**
	 * Blurs what is currently behind {@code [x, y, width, height]} and paints it back into that
	 * rectangle. Returns false when the blur is unavailable and the caller should draw its own fill.
	 */
	public static boolean draw(DrawContext context, int x, int y, int width, int height) {
		ShaderProgram shader = program;
		MinecraftClient client = MinecraftClient.getInstance();
		if (shader == null || client == null || client.getFramebuffer() == null) {
			return false;
		}
		Framebuffer main = client.getFramebuffer();
		int framebufferWidth = main.textureWidth;
		int framebufferHeight = main.textureHeight;
		if (framebufferWidth <= 0 || framebufferHeight <= 0) {
			return false;
		}
		// Half resolution is plenty for a frosted backdrop and quarters the blur cost.
		if (!ensureBuffers(Math.max(1, framebufferWidth / 2), Math.max(1, framebufferHeight / 2))) {
			return false;
		}
		int guiWidth = client.getWindow().getScaledWidth();
		int guiHeight = client.getWindow().getScaledHeight();
		if (guiWidth <= 0 || guiHeight <= 0) {
			return false;
		}
		x = Math.max(0, x);
		y = Math.max(0, y);
		width = Math.min(width, guiWidth - x);
		height = Math.min(height, guiHeight - y);
		if (width <= 0 || height <= 0) {
			return false;
		}

		// Pass 1: screen -> horizontal blur. Pass 2: that result -> vertical blur.
		blurPass(shader, main.getColorAttachment(), horizontal, 1.0F, 0.0F, guiWidth, guiHeight);
		blurPass(shader, horizontal.getColorAttachment(), vertical, 0.0F, 1.0F, guiWidth, guiHeight);

		// Paint the blurred copy over the panel rectangle on the main framebuffer again.
		// beginWrite only binds and sets the viewport (it does not clear), so the frame survives.
		main.beginWrite(true);
		//? if >=1.21.4 {
		/*RenderSystem.setShader(net.minecraft.client.gl.ShaderProgramKeys.POSITION_TEX);
		*///?} else {
		RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionTexProgram);
		//?}
		RenderSystem.setShaderTexture(0, vertical.getColorAttachment());
		RenderSystem.disableDepthTest();
		RenderSystem.disableBlend();
		float u0 = (float) x / guiWidth;
		float u1 = (float) (x + width) / guiWidth;
		// Framebuffer textures put texture row 0 at the bottom of the window, so GUI y maps to
		// 1 - y/height. (Using y/height directly sampled a completely different strip of the screen.)
		float vTop = 1.0F - (float) y / guiHeight;
		float vBottom = 1.0F - (float) (y + height) / guiHeight;
		BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS,
			VertexFormats.POSITION_TEXTURE);
		builder.vertex(x, y + height, 0.0F).texture(u0, vBottom);
		builder.vertex(x + width, y + height, 0.0F).texture(u1, vBottom);
		builder.vertex(x + width, y, 0.0F).texture(u1, vTop);
		builder.vertex(x, y, 0.0F).texture(u0, vTop);
		BufferRenderer.drawWithGlobalProgram(builder.end());
		RenderSystem.enableBlend();
		RenderSystem.enableDepthTest();
		return true;
	}

	/** Runs one blur direction over the whole window into {@code target}. */
	private static void blurPass(ShaderProgram shader, int sourceTexture, Framebuffer target,
			float directionX, float directionY, int guiWidth, int guiHeight) {
		RenderSystem.disableScissor();
		RenderSystem.disableDepthTest();
		RenderSystem.disableBlend();
		//? if >=1.21.4 {
		/*RenderSystem.setShader(shader);
		*///?} else {
		RenderSystem.setShader(() -> shader);
		//?}
		RenderSystem.setShaderTexture(0, sourceTexture);
		target.beginWrite(true);
		BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS,
			VertexFormats.POSITION_TEXTURE_COLOR);
		builder.vertex(0.0F, (float) guiHeight, 0.0F).texture(0.0F, 0.0F).color(directionX, directionY, 0.0F, 1.0F);
		builder.vertex((float) guiWidth, (float) guiHeight, 0.0F).texture(1.0F, 0.0F).color(directionX, directionY, 0.0F, 1.0F);
		builder.vertex((float) guiWidth, 0.0F, 0.0F).texture(1.0F, 1.0F).color(directionX, directionY, 0.0F, 1.0F);
		builder.vertex(0.0F, 0.0F, 0.0F).texture(0.0F, 1.0F).color(directionX, directionY, 0.0F, 1.0F);
		BufferRenderer.drawWithGlobalProgram(builder.end());
	}

	/** Creates the ping-pong buffers, recreating them whenever the window size changes. */
	private static boolean ensureBuffers(int width, int height) {
		if (horizontal != null && vertical != null && bufferWidth == width && bufferHeight == height) {
			return true;
		}
		releaseBuffers();
		try {
			horizontal = new SimpleFramebuffer(width, height, false
				//? if <1.21.4
				, false
			);
			vertical = new SimpleFramebuffer(width, height, false
				//? if <1.21.4
				, false
			);
			bufferWidth = width;
			bufferHeight = height;
			return true;
		} catch (RuntimeException exception) {
			StarRadiance.LOGGER.warn("Could not create the panel blur buffers", exception);
			releaseBuffers();
			return false;
		}
	}

	private static void releaseBuffers() {
		if (horizontal != null) {
			horizontal.delete();
			horizontal = null;
		}
		if (vertical != null) {
			vertical.delete();
			vertical = null;
		}
		bufferWidth = -1;
		bufferHeight = -1;
	}
}
