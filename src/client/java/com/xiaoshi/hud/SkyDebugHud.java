package com.xiaoshi.hud;

import com.xiaoshi.astro.AstroTime;
import com.xiaoshi.astro.EclipseCalculator;
import com.xiaoshi.config.StarRadianceConfig;
import com.xiaoshi.iris.IrisCompat;
import com.xiaoshi.sky.Celestial;
import com.xiaoshi.sky.MoonRenderer;
import com.xiaoshi.sky.SkyDomeRenderer;
import com.xiaoshi.sky.StarFieldRenderer;
import com.xiaoshi.sky.SunRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.Text;

/**
 * The hold-K debug overlay: translated data lines plus the orbit diagram.
 *
 * <p>Every line comes from a {@code hud.starradiance.*} translation key. Numbers are pre-formatted
 * with {@link Locale#ROOT} into strings and substituted with {@code %s} placeholders, so translators
 * never have to deal with format specifiers and the output does not depend on the system locale.
 */
public final class SkyDebugHud {
	private static final int LINE_HEIGHT = 9;

	private SkyDebugHud() {
	}

	/** The translated data block, drawn by the star map screen in its see-through mode. */
	public static void renderTextBlock(DrawContext context, MinecraftClient client, Celestial.SkyState state,
			double latitudeDeg) {
		TextRenderer font = client.textRenderer;
		List<Text> lines = buildLines(client, state, latitudeDeg);
		int x = 8;
		int y = 8;
		int lineY = y;
		for (Text line : lines) {
			context.drawText(font, line, x, lineY, 0xFFFFFF, true);
			lineY += LINE_HEIGHT;
		}
	}

	/** The controls hint in the bottom-right corner. */
	public static void renderHint(DrawContext context, MinecraftClient client, boolean transparent,
			boolean zoomed, int screenWidth, int screenHeight) {
		TextRenderer font = client.textRenderer;
		Text hint = zoomed
			? Text.translatable("hud.starradiance.zoom.hint")
			: Text.translatable("hud.starradiance.map.hint",
				tr(transparent ? "hud.starradiance.map.opaque" : "hud.starradiance.map.transparent"));
		int hintWidth = font.getWidth(hint);
		int hintX = screenWidth - hintWidth - 10;
		int hintY = screenHeight - 14;
		if (!transparent) {
			context.fill(hintX - 4, hintY - 3, screenWidth - 4, hintY + 11, 0x90000000);
		}
		context.drawText(font, hint, hintX, hintY, 0xFFFFFF, true);
	}

	private static List<Text> buildLines(MinecraftClient client, Celestial.SkyState s, double latitudeDeg) {
		List<Text> lines = new ArrayList<>();
		StarRadianceConfig cfg = StarRadianceConfig.get();
		double playerY = client.player != null ? client.player.getY() : 0.0;
		double playerZ = client.player != null ? client.player.getZ() : 0.0;

		lines.add(Text.translatable("hud.starradiance.time",
			String.valueOf(s.timeOfDay), String.valueOf(s.day), num("%.1f", playerY),
			num("%.1f", playerZ), num("%.2f", latitudeDeg)));
		lines.add(Text.translatable("hud.starradiance.year",
			num("%.3f", s.yearFraction), num("%.2f", s.sunDeclinationDeg), num("%.2f", s.moonDeclinationDeg)));
		lines.add(Text.translatable("hud.starradiance.utc",
			AstroTime.calendarString(s.julianDateUtc), num("%.1f", s.longitudeDeg),
			num("%.2f", s.localSiderealTimeDeg / 15.0), num("%.1f", s.equationOfTimeMinutes)));
		lines.add(Text.translatable("hud.starradiance.sun.radec",
			num("%.2f", s.sunRightAscensionDeg), num("%.2f", s.sunDeclinationDeg),
			num("%.4f", s.sunDistanceAu), num("%.3f", s.sunAngularRadiusDeg)));
		lines.add(Text.translatable("hud.starradiance.sun.altaz",
			num("%.2f", s.sunAltitudeDeg), num("%.2f", s.sunAzimuthDeg),
			num("%.2f", s.dayLengthFraction), num("%.2f", s.dailyInsolation)));

		double moonAltitude = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, s.moonY))));
		double sunDotMoon = Math.max(-1.0, Math.min(1.0, s.sunX * s.moonX + s.sunY * s.moonY + s.sunZ * s.moonZ));
		double renderedIllumination = (1.0 - sunDotMoon) / 2.0;
		double trueIllumination = (1.0 - Math.cos(s.moonElongationDeg * Math.PI / 180.0)) / 2.0;
		String minMarker = renderedIllumination > trueIllumination + 0.01
			? tr("hud.starradiance.marker.min") : "";
		String polar = s.polarDay ? tr("hud.starradiance.marker.polarDay")
			: (s.polarNight ? tr("hud.starradiance.marker.polarNight") : "");
		lines.add(Text.translatable("hud.starradiance.moon.phase",
			num("%.1f", s.moonElongationDeg), String.valueOf(s.moonPhase), num("%.1f", moonAltitude),
			num("%.0f", renderedIllumination * 100.0), minMarker,
			num("%.1f", s.moonEclipticLatitudeDeg), polar, moonRiseSet(client, latitudeDeg, s)));
		lines.add(Text.translatable("hud.starradiance.moon.radec",
			num("%.2f", s.moonRightAscensionDeg), num("%.2f", s.moonDeclinationDeg),
			num("%.0f", s.moonDistanceKm), num("%.3f", s.moonAngularRadiusDeg),
			num("%.2f", s.moonHorizontalParallaxDeg), num("%.1f", s.moonNodeDeg)));

		String eclipseKey = s.eclipseKind == EclipseCalculator.SOLAR ? "hud.starradiance.eclipse.solar"
			: (s.eclipseKind == EclipseCalculator.LUNAR ? "hud.starradiance.eclipse.lunar"
				: "hud.starradiance.eclipse.none");
		lines.add(Text.translatable(eclipseKey, num("%.2f", s.eclipseMagnitude),
			num("%.0f", s.eclipseObscuration * 100.0), num("%.2f", s.umbraRadiusDeg),
			num("%.2f", s.penumbraRadiusDeg)));

		lines.add(Text.translatable("hud.starradiance.starfield",
			String.valueOf(StarFieldRenderer.drawnStars),
			tr(StarFieldRenderer.catalogMissing() ? "hud.starradiance.value.missing" : "hud.starradiance.value.ok")));
		lines.add(Text.translatable("hud.starradiance.moonstate", onOff(cfg.realisticMoon)));
		lines.add(Text.translatable("hud.starradiance.shader.sun", shaderValue(SunRenderer.shaderReady())));
		lines.add(Text.translatable("hud.starradiance.shader.moon", shaderValue(MoonRenderer.shaderReady())));
		lines.add(Text.translatable("hud.starradiance.shader.sky", shaderValue(SkyDomeRenderer.shaderReady())));
		boolean iris = IrisCompat.shaderPackActive();
		lines.add(Text.translatable("hud.starradiance.iris", onOff(iris),
			tr(IrisCompat.customSkyAvailable() ? "hud.starradiance.sky.own" : "hud.starradiance.sky.pack"),
			onOff(IrisCompat.unknownShadersAllowed())));
		return lines;
	}

	private static String moonRiseSet(MinecraftClient client, double latitudeDeg, Celestial.SkyState now) {
		if (client.world == null) {
			return "";
		}
		long start = client.world.getTimeOfDay();
		boolean up = now.moonY > 0.0;
		for (long t = 200L; t <= Celestial.TICKS_PER_DAY; t += 200L) {
			Celestial.SkyState later = Celestial.compute(start + t, latitudeDeg);
			if ((later.moonY > 0.0) != up) {
				return Text.translatable(up ? "hud.starradiance.moon.sets" : "hud.starradiance.moon.rises",
					num("%.1f", t / 1200.0)).getString();
			}
		}
		return tr(up ? "hud.starradiance.moon.upAllDay" : "hud.starradiance.moon.downAllDay");
	}

	private static String shaderValue(boolean ready) {
		return tr(ready ? "hud.starradiance.value.ok" : "hud.starradiance.value.broken");
	}

	private static String onOff(boolean value) {
		return tr(value ? "hud.starradiance.value.on" : "hud.starradiance.value.off");
	}

	private static String num(String format, double value) {
		return String.format(Locale.ROOT, format, value);
	}

	/** Raw translated string, for embedding one translation inside another. */
	static String tr(String key) {
		return I18n.translate(key);
	}
}
