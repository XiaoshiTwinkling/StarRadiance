package com.xiaoshi.iris;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Optional Iris integration. Everything here is safe to call whether or not Iris is present: the
 * iris classes are only touched through {@link Class#forName} reflection so the mod runs fine
 * without Iris on the classpath.
 */
public final class IrisCompat {
	private static Boolean irisLoaded;
	private static Boolean packInUse;
	private static long ticks;
	private static boolean unknownShadersAllowed;
	private static int unknownShaderAttempts;

	private static Object pipeline;
	private static Class<?> phaseClass;
	private static java.lang.reflect.Method setPhase;
	private static boolean phaseUnavailable;

	private IrisCompat() {
	}

	/** True when the Iris mod is on the classpath (regardless of shader state). */
	public static boolean irisModLoaded() {
		if (irisLoaded == null) {
			irisLoaded = FabricLoader.getInstance().isModLoaded("iris");
		}
		return irisLoaded;
	}

	/**
	 * True when a shader pipeline is actually active (Iris present + user has enabled shaders and
	 * selected a pack). While true, vanilla's sky programs belong to the pack, so StarRadiance draws
	 * its sky with its own core shaders instead; {@link #customSkyAvailable()} reports whether that
	 * is possible right now.
	 */
	public static boolean shaderPackActive() {
		if (!irisModLoaded()) {
			return false;
		}
		if (packInUse != null) {
			return packInUse;
		}
		try {
			Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object api = apiClass.getMethod("getInstance").invoke(null);
			packInUse = (Boolean) apiClass.getMethod("isShaderPackInUse").invoke(api);
		} catch (ReflectiveOperationException | RuntimeException e) {
			packInUse = false;
		}
		return packInUse;
	}

	/**
	 * Called each client tick. Iris exposes no event for a pack toggle, so we drop the cached flag
	 * roughly once a second; the next {@link #shaderPackActive()} then re-polls the live value.
	 */
	public static void tick() {
		if (!irisModLoaded()) {
			return;
		}
		if (++ticks % 20L == 0L) {
			packInUse = null;
		}
		if (com.xiaoshi.config.StarRadianceConfig.get().customSkyWithShaders && shaderPackActive()) {
			ensureUnknownShadersAllowed();
		}
	}

	/** Resets cached state (e.g. when the user switches pack in Iris's UI at runtime). */
	public static void invalidateCache() {
		packInUse = null;
		pipeline = null;
		unknownShadersAllowed = false;
		unknownShaderAttempts = 0;
	}

	/**
	 * True when StarRadiance may draw its own sky right now: either no shaderpack is active, or the
	 * user asked for the custom sky under shaders and Iris lets our own core shaders through.
	 */
	public static boolean customSkyAvailable() {
		if (!shaderPackActive()) {
			return true;
		}
		if (!com.xiaoshi.config.StarRadianceConfig.get().customSkyWithShaders) {
			return false;
		}
		ensureUnknownShadersAllowed();
		return unknownShadersAllowed;
	}

	/** True once Iris has been confirmed to pass unknown shaders (our sun/moon/star/sky programs) through. */
	public static boolean unknownShadersAllowed() {
		return unknownShadersAllowed;
	}

	/**
	 * Iris normally suppresses any shader program it did not build itself: unknown programs get their
	 * depth and color writes disabled, so our custom sun/moon/star/dome shaders would simply draw
	 * nothing under a pack. Its "allow unknown shaders" option exists exactly for mods like this one;
	 * turn it on (and persist it) the first time we actually need it while a pack is active.
	 *
	 * <p>Best effort and reflective: Iris internals move between versions, so a failure only logs and
	 * leaves the pack drawing its own sky.
	 */
	public static void ensureUnknownShadersAllowed() {
		if (unknownShadersAllowed || !irisModLoaded() || unknownShaderAttempts >= 5) {
			return;
		}
		unknownShaderAttempts++;
		try {
			Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
			Object config = irisClass.getMethod("getIrisConfig").invoke(null);
			Class<?> configClass = config.getClass();
			boolean allowed = (Boolean) configClass.getMethod("shouldAllowUnknownShaders").invoke(config);
			if (!allowed) {
				configClass.getMethod("setUnknown", boolean.class).invoke(config, true);
				com.xiaoshi.StarRadiance.LOGGER.info(
					"Enabled Iris 'allow unknown shaders' so StarRadiance's sky shaders render under a shaderpack");
			}
			unknownShadersAllowed = true;
		} catch (ReflectiveOperationException | RuntimeException exception) {
			com.xiaoshi.StarRadiance.LOGGER.warn(
				"Could not enable Iris 'allow unknown shaders'; the shaderpack's own sky will be used", exception);
		}
	}

	/**
	 * Switches Iris's current render phase, used only by the textured-quad fallback that runs when one
	 * of our own celestial shaders is unavailable. Iris picks the pack program off this phase, and
	 * drawing the fallback while the phase says SUN/MOON makes the pack treat it like its own
	 * sun/moon stage instead of a third-party skybox.
	 *
	 * <p>Reflective and best-effort — Iris internals move between versions, so a failure logs once
	 * and leaves the phase alone.
	 */
	public static void setPhase(String phase) {
		if (!irisModLoaded() || phaseUnavailable) {
			return;
		}
		try {
			if (pipeline == null) {
				Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
				Object manager = iris.getMethod("getPipelineManager").invoke(null);
				pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
				if (pipeline == null) {
					return;
				}
				phaseClass = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPhase");
				setPhase = pipeline.getClass().getMethod("setPhase", phaseClass);
			}
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Object value = Enum.valueOf((Class) phaseClass.asSubclass(Enum.class), phase);
			setPhase.invoke(pipeline, value);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			phaseUnavailable = true;
			com.xiaoshi.StarRadiance.LOGGER.warn("Iris render phase control unavailable", exception);
		}
	}
}
