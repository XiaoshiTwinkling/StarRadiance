package com.xiaoshi;

import com.xiaoshi.sky.Celestial;
import com.xiaoshi.sky.WorldEpoch;
import com.xiaoshi.astro.SkyContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StarRadiance implements ModInitializer {
	public static final String MOD_ID = "starradiance";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		com.xiaoshi.config.StarRadianceConfig.load();
		WorldEpoch.register();

		// The calendar of a world is anchored to the real UTC clock: a new world starts with its own
		// epoch, and the clock is moved to the real local time so the first sky is the real one.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			for (ServerWorld world : server.getWorlds()) {
				if (!world.getRegistryKey().equals(World.OVERWORLD)) {
					continue;
				}
				boolean brandNew = world.getTime() == 0L && WorldEpoch.get(world) == null;
				long epoch = WorldEpoch.ensure(world);
				if (brandNew) {
					com.xiaoshi.config.StarRadianceConfig cfg = com.xiaoshi.config.StarRadianceConfig.get();
					world.setTimeOfDay(WorldEpoch.localTickOfDay(epoch, cfg.longitudeDeg, cfg.clockMode));
				}
			}
		});
	}
}
