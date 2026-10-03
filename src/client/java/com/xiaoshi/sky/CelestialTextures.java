package com.xiaoshi.sky;

import net.minecraft.util.Identifier;

/**
 * Textures the mod substitutes for vanilla's celestial sprites while an Iris shaderpack is active.
 *
 * <p>Iris renders a pack's sun/moon from the vanilla quad geometry and samples whatever texture the
 * vanilla renderer bound ({@code SUN} / {@code MOON_PHASES}). Cancelling {@code renderSky} would
 * remove the pack's sky entirely, so instead we redirect those two binds to these baked textures —
 * the pack then draws our circular sun and a phase-correct NASA moon.
 */
public final class CelestialTextures {
	private static final Identifier VANILLA_SUN = Identifier.ofVanilla("textures/environment/sun.png");
	private static final Identifier VANILLA_MOON = Identifier.ofVanilla("textures/environment/moon_phases.png");

	public static final Identifier SUN = Identifier.of("starradiance", "textures/environment/sun_disc.png");
	public static final Identifier MOON = Identifier.of("starradiance", "textures/environment/moon_phases_nasa.png");

	private static int logged;

	private CelestialTextures() {
	}

	/** One-time diagnostic so the substitution is observable in the log (not spammy per-frame). */
	public static void logSubstitution(Identifier from, Identifier to) {
		if (logged < 2) {
			logged++;
			com.xiaoshi.StarRadiance.LOGGER.info("Iris sky: substituted {} -> {}", from, to);
		}
	}

	/** Maps a vanilla celestial sprite id to ours, or returns the input unchanged for anything else. */
	public static Identifier substitute(Identifier vanilla) {
		if (VANILLA_SUN.equals(vanilla)) {
			return SUN;
		}
		if (VANILLA_MOON.equals(vanilla)) {
			return MOON;
		}
		return vanilla;
	}
}
