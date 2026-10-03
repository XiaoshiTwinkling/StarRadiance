package com.xiaoshi.screen;

import com.xiaoshi.config.StarRadianceConfig;
import com.xiaoshi.sky.MoonRenderer;
import com.xiaoshi.sky.StarFieldRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** In-game configuration screen (config key; intended for ModMenu too). */
public class StarRadianceConfigScreen extends Screen {
	private static final double[] MAG_LEVELS = { 4.0, 5.0, 6.5, 8.0, 10.0 };
	private static final double[] DARKNESS_LEVELS = { 0.25, 0.5, 0.75, 1.0 };
	private static final double[] MOON_LEVELS = { 2.0, 4.0, 8.0 };
	private static final double[] MOON_BRIGHTNESS_LEVELS = { 1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0 };
	private static final double[] MIN_ILLUMINATION_LEVELS = { 0.0, 0.05, 0.08, 0.12, 0.2 };
	private static final double[] SIZE_LEVELS = { 4.0, 8.0, 12.0, 16.0, 18.0, 24.0, 32.0 };
	private static final double[] DAYTIME_FADE_LEVELS = { 0.0, 0.3, 0.5, 0.6, 0.75, 0.9 };

	private final Screen parent;
	private ButtonWidget darkerNights;
	private ButtonWidget nightDarkness;
	private ButtonWidget magLimit;
	private ButtonWidget eclipseEffects;
	private ButtonWidget shaderMoonBrightness;
	private ButtonWidget sunMoonScale;
	private ButtonWidget realisticMoon;
	private ButtonWidget normalMappedMoon;
	private ButtonWidget moonQuality;
	private ButtonWidget customSkyWithShaders;
	private ButtonWidget moonMinIllumination;
	private ButtonWidget atmosphericRefraction;
	private ButtonWidget epochMode;
	private ButtonWidget clockMode;
	private ButtonWidget daytimeMoonFade;

	public StarRadianceConfigScreen(Screen parent) {
		super(Text.translatable("screen.starradiance.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int colWidth = 200;
		int leftX = this.width / 2 - colWidth - 4;
		int rightX = this.width / 2 + 4;
		int rowStep = 22;
		int top = 30;

		darkerNights = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.darkerNights = !cfg.darkerNights;
			refresh();
		}).dimensions(leftX, top, colWidth, 20).build();

		nightDarkness = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.nightDarkness = next(cfg.nightDarkness, DARKNESS_LEVELS);
			refresh();
		}).dimensions(leftX, top + rowStep, colWidth, 20).build();

		magLimit = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.maxRenderMagnitude = next(cfg.maxRenderMagnitude, MAG_LEVELS);
			refresh();
		}).dimensions(leftX, top + rowStep * 2, colWidth, 20).build();

		eclipseEffects = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.eclipseEffects = !cfg.eclipseEffects;
			refresh();
		}).dimensions(leftX, top + rowStep * 3, colWidth, 20).build();

		shaderMoonBrightness = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.shaderMoonBrightness = next(cfg.shaderMoonBrightness, MOON_BRIGHTNESS_LEVELS);
			refresh();
		}).dimensions(leftX, top + rowStep * 4, colWidth, 20).build();

		sunMoonScale = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.sunMoonScale = next(cfg.sunMoonScale, SIZE_LEVELS);
			MoonRenderer.invalidate();
			refresh();
		}).dimensions(leftX, top + rowStep * 5, colWidth, 20).build();

		realisticMoon = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.realisticMoon = !cfg.realisticMoon;
			MoonRenderer.invalidate();
			refresh();
		}).dimensions(rightX, top, colWidth, 20).build();

		normalMappedMoon = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.normalMappedMoon = !cfg.normalMappedMoon;
			refresh();
		}).dimensions(rightX, top + rowStep, colWidth, 20).build();

		moonQuality = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.moonTextureQuality = next(cfg.moonTextureQuality, MOON_LEVELS);
			refresh();
		}).dimensions(rightX, top + rowStep * 2, colWidth, 20).build();

		customSkyWithShaders = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.customSkyWithShaders = !cfg.customSkyWithShaders;
			refresh();
		}).dimensions(rightX, top + rowStep * 3, colWidth, 20).build();

		moonMinIllumination = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.moonMinIllumination = next(cfg.moonMinIllumination, MIN_ILLUMINATION_LEVELS);
			refresh();
		}).dimensions(rightX, top + rowStep * 4, colWidth, 20).build();

		atmosphericRefraction = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.atmosphericRefraction = !cfg.atmosphericRefraction;
			refresh();
		}).dimensions(rightX, top + rowStep * 5, colWidth, 20).build();

		epochMode = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.epochMode = cfg.epochMode == StarRadianceConfig.EpochMode.WORLD_CREATION
				? StarRadianceConfig.EpochMode.FIXED_DATE
				: StarRadianceConfig.EpochMode.WORLD_CREATION;
			refresh();
		}).dimensions(leftX, top + rowStep * 6, colWidth, 20).build();

		clockMode = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.clockMode = cfg.clockMode == com.xiaoshi.astro.SkyContext.ClockMode.LOCAL_MEAN_SOLAR
				? com.xiaoshi.astro.SkyContext.ClockMode.UTC
				: com.xiaoshi.astro.SkyContext.ClockMode.LOCAL_MEAN_SOLAR;
			refresh();
		}).dimensions(rightX, top + rowStep * 6, colWidth, 20).build();

		daytimeMoonFade = ButtonWidget.builder(Text.empty(), b -> {
			StarRadianceConfig cfg = StarRadianceConfig.get();
			cfg.daytimeMoonFade = next(cfg.daytimeMoonFade, DAYTIME_FADE_LEVELS);
			refresh();
		}).dimensions(leftX, top + rowStep * 7, colWidth, 20).build();

		this.addDrawableChild(darkerNights);
		this.addDrawableChild(nightDarkness);
		this.addDrawableChild(magLimit);
		this.addDrawableChild(eclipseEffects);
		this.addDrawableChild(shaderMoonBrightness);
		this.addDrawableChild(sunMoonScale);
		this.addDrawableChild(realisticMoon);
		this.addDrawableChild(normalMappedMoon);
		this.addDrawableChild(moonQuality);
		this.addDrawableChild(customSkyWithShaders);
		this.addDrawableChild(moonMinIllumination);
		this.addDrawableChild(atmosphericRefraction);
		this.addDrawableChild(epochMode);
		this.addDrawableChild(clockMode);
		this.addDrawableChild(daytimeMoonFade);
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("screen.starradiance.done"), b -> this.close())
			.dimensions(this.width / 2 - 75, top + rowStep * 8 + 4, 150, 20).build());
		refresh();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float tickDelta) {
		super.render(context, mouseX, mouseY, tickDelta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFF);
	}

	@Override
	public void close() {
		StarRadianceConfig.save();
		StarFieldRenderer.invalidate();
		MoonRenderer.invalidate();
		if (this.client != null) {
			this.client.setScreen(this.parent);
		}
	}

	private void refresh() {
		StarRadianceConfig cfg = StarRadianceConfig.get();
		darkerNights.setMessage(Text.translatable("screen.starradiance.darkerNights")
			.append(": ").append(Text.literal(cfg.darkerNights ? "ON" : "OFF")));
		nightDarkness.setMessage(Text.translatable("screen.starradiance.nightDarkness")
			.append(": ").append(Text.literal(fmt(cfg.nightDarkness))));
		magLimit.setMessage(Text.translatable("screen.starradiance.magLimit")
			.append(": ").append(Text.literal(fmt(cfg.maxRenderMagnitude))));
		eclipseEffects.setMessage(Text.translatable("screen.starradiance.eclipseEffects")
			.append(": ").append(Text.literal(cfg.eclipseEffects ? "ON" : "OFF")));
		shaderMoonBrightness.setMessage(Text.translatable("screen.starradiance.shaderMoonBrightness")
			.append(": ").append(Text.literal(fmt(cfg.shaderMoonBrightness))));
		sunMoonScale.setMessage(Text.translatable("screen.starradiance.sunMoonScale")
			.append(": ").append(Text.literal(fmt(cfg.sunMoonScale) + "x")));
		realisticMoon.setMessage(Text.translatable("screen.starradiance.realisticMoon")
			.append(": ").append(Text.literal(cfg.realisticMoon ? "ON" : "OFF")));
		normalMappedMoon.setMessage(Text.translatable("screen.starradiance.normalMappedMoon")
			.append(": ").append(Text.literal(cfg.normalMappedMoon ? "ON" : "OFF")));
		moonQuality.setMessage(Text.translatable("screen.starradiance.moonQuality")
			.append(": ").append(Text.literal(fmt(cfg.moonTextureQuality) + "K")));
		customSkyWithShaders.setMessage(Text.translatable("screen.starradiance.customSkyWithShaders")
			.append(": ").append(Text.literal(cfg.customSkyWithShaders ? "ON" : "OFF")));
		moonMinIllumination.setMessage(Text.translatable("screen.starradiance.moonMinIllumination")
			.append(": ").append(Text.literal(fmt(cfg.moonMinIllumination * 100.0) + "%")));
		atmosphericRefraction.setMessage(Text.translatable("screen.starradiance.atmosphericRefraction")
			.append(": ").append(Text.literal(cfg.atmosphericRefraction ? "ON" : "OFF")));
		epochMode.setMessage(Text.translatable("screen.starradiance.epochMode")
			.append(": ").append(Text.translatable(cfg.epochMode == StarRadianceConfig.EpochMode.WORLD_CREATION
				? "screen.starradiance.epochMode.worldCreation" : "screen.starradiance.epochMode.fixedDate")));
		clockMode.setMessage(Text.translatable("screen.starradiance.clockMode")
			.append(": ").append(Text.translatable(cfg.clockMode == com.xiaoshi.astro.SkyContext.ClockMode.LOCAL_MEAN_SOLAR
				? "screen.starradiance.clockMode.local" : "screen.starradiance.clockMode.utc")));
		daytimeMoonFade.setMessage(Text.translatable("screen.starradiance.daytimeMoonFade")
			.append(": ").append(Text.literal(fmt(cfg.daytimeMoonFade * 100.0) + "%")));
	}

	private static double next(double value, double[] levels) {
		for (int i = 0; i < levels.length; i++) {
			if (levels[i] >= value) {
				return levels[(i + 1) % levels.length];
			}
		}
		return levels[0];
	}

	private static String fmt(double v) {
		if (v == Math.rint(v)) {
			return String.valueOf((long) v);
		}
		return String.valueOf(Math.round(v * 100.0) / 100.0);
	}
}
