package com.xiaoshi;

import com.xiaoshi.iris.IrisCompat;
import com.xiaoshi.screen.StarMapScreen;
import com.xiaoshi.screen.StarRadianceConfigScreen;
import com.xiaoshi.screen.StarChartScreen;
import com.xiaoshi.sky.MoonRenderer;
import com.xiaoshi.sky.MoonTextures;
import com.xiaoshi.sky.SkyDomeRenderer;
import com.xiaoshi.sky.StarFieldRenderer;
import com.xiaoshi.sky.SunRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//? if <1.21.4 {
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
//?}
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.InputUtil;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class StarRadianceClient implements ClientModInitializer {
	private static final Identifier MOON_SHADER = Identifier.of("starradiance", "moon");
	private static final Identifier SUN_SHADER = Identifier.of("starradiance", "sun");
	private static final Identifier SKY_SHADER = Identifier.of("starradiance", "sky");
	private static final Identifier PANEL_BLUR_SHADER = Identifier.of("starradiance", "panel_blur");

	private static final KeyBinding SKY_DEBUG_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
		"key.starradiance.skydebug", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.starradiance"));

	private static final KeyBinding CONFIG_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
		"key.starradiance.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_P, "key.categories.starradiance"));

	private static final KeyBinding STAR_CHART_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
		"key.starradiance.starchart", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.categories.starradiance"));

	@Override
	public void onInitializeClient() {
		// Clear the cached Iris shader state when the client starts (a pack may have been toggled).
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> IrisCompat.invalidateCache());

		// Register the custom sun/moon shaders; the callback is (re)invoked on every resource reload.
		// 1.21.4 dropped this Fabric API: core shaders are resolved from a ShaderProgramKey instead
		// (see CoreShaderKeys), so there is nothing to register here.
		//? if <1.21.4 {
		CoreShaderRegistrationCallback.EVENT.register(context -> {
			context.register(MOON_SHADER, VertexFormats.POSITION_TEXTURE, program -> {
				MoonRenderer.setProgram(program);
				StarRadiance.LOGGER.info("Loaded core shader {}", MOON_SHADER);
			});
			context.register(SUN_SHADER, VertexFormats.POSITION_TEXTURE, program -> {
				SunRenderer.setProgram(program);
				StarRadiance.LOGGER.info("Loaded core shader {}", SUN_SHADER);
			});
			// The dome and starfield share this one: both are plain POSITION_COLOR geometry. Using
			// our own program keeps Iris from remapping them onto the pack's procedural sky shader.
			context.register(SKY_SHADER, VertexFormats.POSITION_COLOR, program -> {
				SkyDomeRenderer.setProgram(program);
				StarFieldRenderer.setProgram(program);
				StarRadiance.LOGGER.info("Loaded core shader {}", SKY_SHADER);
			});
			// Used by the star chart to frost the background behind its info panel.
			context.register(PANEL_BLUR_SHADER, VertexFormats.POSITION_TEXTURE_COLOR, program -> {
				com.xiaoshi.hud.PanelBlur.setProgram(program);
				StarRadiance.LOGGER.info("Loaded core shader {}", PANEL_BLUR_SHADER);
			});
		});
		//?}

		// Register the moon textures with a mip chain (once per resource reload).
		ResourceManagerHelper.get(net.minecraft.resource.ResourceType.CLIENT_RESOURCES)
			.registerReloadListener(new SimpleSynchronousResourceReloadListener() {
				@Override
				public Identifier getFabricId() {
					return Identifier.of("starradiance", "moon_textures");
				}

				@Override
				public void reload(ResourceManager manager) {
					MoonTextures.register(manager);
					com.xiaoshi.sky.StarMeta.invalidate();
					com.xiaoshi.sky.ConstellationCatalog.invalidate();
					com.xiaoshi.sky.DeepSkyCatalog.invalidate();
				}
			});

		// K opens the full-screen star map (a non-pausing screen); P opens the config.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			IrisCompat.tick();
			if (client.world != null) {
				com.xiaoshi.astro.SkyContext.set(com.xiaoshi.sky.WorldEpoch.contextFor(client.world));
			}
			if (SKY_DEBUG_KEY.wasPressed() && client.currentScreen == null && client.world != null) {
				client.setScreen(new StarMapScreen(null));
			}
			if (STAR_CHART_KEY.wasPressed() && client.currentScreen == null && client.world != null) {
				client.setScreen(new StarChartScreen(null));
			}
			if (CONFIG_KEY.wasPressed() && client.currentScreen == null) {
				client.setScreen(new StarRadianceConfigScreen(null));
			}
		});
	}
}
