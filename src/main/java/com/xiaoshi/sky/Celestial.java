package com.xiaoshi.sky;

import com.xiaoshi.astro.AstroTime;
import com.xiaoshi.astro.EclipseCalculator;
import com.xiaoshi.astro.MoonPosition;
import com.xiaoshi.astro.Nutation;
import com.xiaoshi.astro.ObservingFrame;
import com.xiaoshi.astro.Precession;
import com.xiaoshi.astro.SkyContext;
import com.xiaoshi.astro.SunPosition;

/**
 * The sky state for one moment and place, computed from real ephemerides.
 *
 * <p>World conventions (shared with the renderers): +Y up, −Z north, +X east. Geographic latitude
 * still comes from world Z (Z = 0 sits on the Tropic of Cancer, 100000 blocks per degree); longitude
 * comes from the config. The Sun and Moon use the apparent geocentric positions from
 * {@link SunPosition} / {@link MoonPosition}, corrected for topocentric parallax and (optionally)
 * atmospheric refraction; the star catalogue is rotated from J2000 by precession + nutation and the
 * observer's local sidereal time.
 *
 * <p>Time compression: one game day (24000 ticks) is one real day, so a game year is 365.2422 game
 * days and every astronomical period keeps its real value.
 */
public final class Celestial {
	public static final double TROPIC_LATITUDE = 23.5;
	public static final long TICKS_PER_DAY = 24000L;
	/** Tropical year in game days (identical to the real tropical year in days). */
	public static final double YEAR_DAYS = 365.2422;
	public static final double MOON_SIDEREAL_PERIOD_DAYS = 27.321661;
	public static final double MOON_INCLINATION = 5.145;
	/** World blocks per degree of latitude (world Z = 0 sits on the Tropic of Cancer). */
	public static final double BLOCKS_PER_DEGREE = 100_000.0;
	/** Brightest magnitude included in the shipped catalogue. */
	public static final double CATALOG_MAX_MAGNITUDE = 8.5;
	/** Epoch of the shipped star catalogue (HYG v4.2 is J2000). */
	public static final double CATALOG_EPOCH_JD = AstroTime.J2000;
	/** Mean obliquity of the ecliptic at J2000, degrees (kept for reference/UI). */
	public static final double OBLIQUITY = 23.4392911;

	private static final double TO_RAD = Math.PI / 180.0;
	private static final double TO_DEG = 180.0 / Math.PI;

	private static long cachedTimeOfDay = Long.MIN_VALUE;
	private static double cachedLatitude = Double.NaN;
	private static SkyContext cachedContext;
	private static SkyState cachedState;

	private Celestial() {
	}

	/** Geographic latitude (°, + = north) for a world Z coordinate. */
	public static double latitudeOf(double worldZ) {
		double lat = TROPIC_LATITUDE - worldZ / BLOCKS_PER_DEGREE;
		return Math.max(-90.0, Math.min(90.0, lat));
	}

	/** A frozen snapshot of the sky at one moment and place. */
	public static final class SkyState {
		public final long timeOfDay;
		public final long withinDay;
		public final long day;
		/** Fraction of the tropical year since the vernal equinox (from the Sun's true longitude). */
		public final double yearFraction;
		public final double latitudeDeg;
		public final double longitudeDeg;
		public final double julianDateUtc;

		public final double sunDeclinationDeg;
		public final double sunAltitudeDeg;
		public final double sunAzimuthDeg;
		public final double sunRightAscensionDeg;
		public final double sunApparentLongitudeDeg;
		public final double sunDistanceAu;
		public final double sunAngularRadiusDeg;
		public final double equationOfTimeMinutes;

		public final double moonDeclinationDeg;
		public final double moonRightAscensionDeg;
		public final double moonEclipticLongitudeDeg;
		public final double moonEclipticLatitudeDeg;
		public final double moonElongationDeg;
		public final double moonNodeDeg;
		public final double moonDistanceKm;
		public final double moonAngularRadiusDeg;
		public final double moonHorizontalParallaxDeg;
		public final double moonIllumination;
		public final double moonPhaseAngleDeg;
		/** 0..7, 0 = full, 4 = new (vanilla atlas convention). */
		public final int moonPhase;

		public final double localSiderealTimeDeg;
		/** Kept for the debug HUD: the local apparent sidereal angle in degrees. */
		public final double starAngleDeg;
		/** Row-major 3×3 rotation taking J2000 equatorial unit vectors to world directions. */
		public final double[] starMatrix;
		/** World direction of the north celestial pole. */
		public final double poleX, poleY, poleZ;

		public final double dayLengthFraction;
		public final double dailyInsolation;
		public final boolean polarDay;
		public final boolean polarNight;

		public final int eclipseKind; // 0 none, 1 solar, 2 lunar
		public final double eclipseMagnitude;
		public final double eclipseObscuration;
		public final double umbraRadiusDeg;
		public final double penumbraRadiusDeg;
		/** Angular separation between the Moon and the antisolar point (Earth's shadow axis). */
		public final double moonShadowSeparationDeg;

		public final double sunX, sunY, sunZ, moonX, moonY, moonZ;

		SkyState(long timeOfDay, long withinDay, long day, double yearFraction, double latitudeDeg,
				double longitudeDeg, double julianDateUtc, double sunDeclinationDeg, double sunAltitudeDeg,
				double sunAzimuthDeg, double sunRightAscensionDeg, double sunApparentLongitudeDeg,
				double sunDistanceAu, double sunAngularRadiusDeg, double equationOfTimeMinutes,
				double moonDeclinationDeg, double moonRightAscensionDeg, double moonEclipticLongitudeDeg,
				double moonEclipticLatitudeDeg, double moonElongationDeg, double moonNodeDeg,
				double moonDistanceKm, double moonAngularRadiusDeg, double moonHorizontalParallaxDeg,
				double moonIllumination, double moonPhaseAngleDeg, int moonPhase, double localSiderealTimeDeg,
				double[] starMatrix, double poleX, double poleY, double poleZ, double dayLengthFraction,
				double dailyInsolation, boolean polarDay, boolean polarNight, int eclipseKind,
				double eclipseMagnitude, double eclipseObscuration, double umbraRadiusDeg,
				double penumbraRadiusDeg, double moonShadowSeparationDeg,
				double sunX, double sunY, double sunZ, double moonX, double moonY, double moonZ) {
			this.timeOfDay = timeOfDay;
			this.withinDay = withinDay;
			this.day = day;
			this.yearFraction = yearFraction;
			this.latitudeDeg = latitudeDeg;
			this.longitudeDeg = longitudeDeg;
			this.julianDateUtc = julianDateUtc;
			this.sunDeclinationDeg = sunDeclinationDeg;
			this.sunAltitudeDeg = sunAltitudeDeg;
			this.sunAzimuthDeg = sunAzimuthDeg;
			this.sunRightAscensionDeg = sunRightAscensionDeg;
			this.sunApparentLongitudeDeg = sunApparentLongitudeDeg;
			this.sunDistanceAu = sunDistanceAu;
			this.sunAngularRadiusDeg = sunAngularRadiusDeg;
			this.equationOfTimeMinutes = equationOfTimeMinutes;
			this.moonDeclinationDeg = moonDeclinationDeg;
			this.moonRightAscensionDeg = moonRightAscensionDeg;
			this.moonEclipticLongitudeDeg = moonEclipticLongitudeDeg;
			this.moonEclipticLatitudeDeg = moonEclipticLatitudeDeg;
			this.moonElongationDeg = moonElongationDeg;
			this.moonNodeDeg = moonNodeDeg;
			this.moonDistanceKm = moonDistanceKm;
			this.moonAngularRadiusDeg = moonAngularRadiusDeg;
			this.moonHorizontalParallaxDeg = moonHorizontalParallaxDeg;
			this.moonIllumination = moonIllumination;
			this.moonPhaseAngleDeg = moonPhaseAngleDeg;
			this.moonPhase = moonPhase;
			this.localSiderealTimeDeg = localSiderealTimeDeg;
			this.starAngleDeg = localSiderealTimeDeg;
			this.starMatrix = starMatrix;
			this.poleX = poleX;
			this.poleY = poleY;
			this.poleZ = poleZ;
			this.dayLengthFraction = dayLengthFraction;
			this.dailyInsolation = dailyInsolation;
			this.polarDay = polarDay;
			this.polarNight = polarNight;
			this.eclipseKind = eclipseKind;
			this.eclipseMagnitude = eclipseMagnitude;
			this.eclipseObscuration = eclipseObscuration;
			this.umbraRadiusDeg = umbraRadiusDeg;
			this.penumbraRadiusDeg = penumbraRadiusDeg;
			this.moonShadowSeparationDeg = moonShadowSeparationDeg;
			this.sunX = sunX;
			this.sunY = sunY;
			this.sunZ = sunZ;
			this.moonX = moonX;
			this.moonY = moonY;
			this.moonZ = moonZ;
		}
	}

	public static SkyState compute(long timeOfDay) {
		return compute(timeOfDay, TROPIC_LATITUDE);
	}

	public static SkyState compute(long timeOfDay, double latitudeDeg) {
		SkyContext context = SkyContext.get();
		if (cachedState != null && cachedTimeOfDay == timeOfDay && cachedLatitude == latitudeDeg
			&& cachedContext == context) {
			return cachedState;
		}
		SkyState state = evaluate(timeOfDay, latitudeDeg, context);
		cachedTimeOfDay = timeOfDay;
		cachedLatitude = latitudeDeg;
		cachedContext = context;
		cachedState = state;
		return state;
	}

	private static SkyState evaluate(long timeOfDay, double latitudeDeg, SkyContext context) {
		long within = Math.floorMod(timeOfDay, TICKS_PER_DAY);
		long day = Math.floorDiv(timeOfDay, TICKS_PER_DAY);

		double jdUtc = context.julianDateUtc(timeOfDay);
		double jdTt = AstroTime.julianDateTt(jdUtc);
		double t = AstroTime.centuriesSinceJ2000(jdTt);

		double[] nutation = Nutation.compute(t);
		double trueObliquity = Nutation.trueObliquityDegrees(t);

		SunPosition sun = SunPosition.compute(t);
		MoonPosition moon = MoonPosition.compute(t);
		double[] moonRaDec = moon.equatorial(trueObliquity, nutation[0]);

		ObservingFrame frame = new ObservingFrame(latitudeDeg, context.longitudeDeg(), jdUtc);
		double[] sunWorld = refract(frame.toWorld(sun.rightAscensionDeg, sun.declinationDeg), context);
		// The Moon's geocentric position carries a ~1° parallax; the topocentric one is what you see
		// and what decides whether an eclipse is visible from here.
		double[] moonTopo = frame.topocentric(moonRaDec[0], moonRaDec[1], moon.distanceKm);
		double[] moonWorld = refract(frame.toWorld(moonTopo[0], moonTopo[1]), context);
		double[] moonWorldGeo = frame.toWorld(moonRaDec[0], moonRaDec[1]);

		double sunAltitude = ObservingFrame.altitudeOf(sunWorld);
		double sunAzimuth = ObservingFrame.azimuthOf(sunWorld);
		double moonAltitude = ObservingFrame.altitudeOf(moonWorld);
		double moonAzimuth = ObservingFrame.azimuthOf(moonWorld);

		double elong = AstroTime.mod360(moon.eclipticLongitudeDeg - sun.apparentLongitudeDeg);
		double moonRadiusTopo = MoonPosition.angularRadiusDeg(moonTopo[2]);

		// Eclipses: solar uses the topocentric separation of the two discs; lunar uses the separation
		// between the Moon and the antisolar point (the Earth's shadow axis).
		double antisolarX = -sunWorld[0];
		double antisolarY = -sunWorld[1];
		double antisolarZ = -sunWorld[2];
		double lunarSeparation = ObservingFrame.angularSeparationDeg(moonWorldGeo,
			new double[] { antisolarX, antisolarY, antisolarZ });
		EclipseCalculator.Result solar = EclipseCalculator.solar(
			ObservingFrame.angularSeparationDeg(sunWorld, moonWorld), sun.angularRadiusDeg, moonRadiusTopo);
		EclipseCalculator.Result lunar = EclipseCalculator.lunar(
			lunarSeparation, moon.angularRadiusDeg(), sun.distanceAu, moon.distanceKm);
		EclipseCalculator.Result eclipse = solar.kind != EclipseCalculator.NONE ? solar : lunar;

		double dPsi = nutation[0];
		double dEps = nutation[1];
		double[] starMatrix = Precession.multiply(frame.starMatrix(Precession.j2000ToDate(t)),
			Precession.nutation(dPsi, dEps, trueObliquity));

		double phi = latitudeDeg * TO_RAD;
		double sinPhi = Math.sin(phi);
		double cosPhi = Math.cos(phi);
		double sunDeclRad = sun.declinationDeg * TO_RAD;
		double cosH0 = -Math.tan(phi) * Math.tan(sunDeclRad);
		double h0;
		boolean polarDay = false;
		boolean polarNight = false;
		if (cosH0 <= -1.0) {
			h0 = Math.PI;
			polarDay = true;
		} else if (cosH0 >= 1.0) {
			h0 = 0.0;
			polarNight = true;
		} else {
			h0 = Math.acos(cosH0);
		}
		double dayLength = h0 / Math.PI;
		double insolation = (h0 * sinPhi * Math.sin(sunDeclRad) + cosPhi * Math.cos(sunDeclRad) * Math.sin(h0))
			* (2.0 / Math.PI);

		double moonPhaseAngle = ObservingFrame.angularSeparationDeg(sunWorld, moonWorld);
		double moonIllumination = (1.0 - Math.cos(moonPhaseAngle * TO_RAD)) / 2.0;
		// Mean lunar node, for the debug HUD (real orbital geometry is the moon's own latitude).
		double node = AstroTime.mod360(125.0445479 - 1934.1362891 * t + 0.0020754 * t * t
			+ t * t * t / 467441.0 - t * t * t * t / 60616000.0);

		double[] pole = frame.toWorld(new double[] { 0.0, 0.0, 1.0 });

		return new SkyState(timeOfDay, within, day, AstroTime.mod360(sun.apparentLongitudeDeg) / 360.0,
			latitudeDeg, context.longitudeDeg(), jdUtc, sun.declinationDeg, sunAltitude, sunAzimuth,
			sun.rightAscensionDeg, sun.apparentLongitudeDeg, sun.distanceAu, sun.angularRadiusDeg,
			sun.equationOfTimeMinutes, moonRaDec[1], moonTopo[0], moon.eclipticLongitudeDeg,
			moon.eclipticLatitudeDeg, elong, node, moon.distanceKm, moonRadiusTopo,
			moon.horizontalParallaxDeg(), moonIllumination, moonPhaseAngle, phaseFromElongation(elong),
			frame.localSiderealTimeDeg, starMatrix, pole[0], pole[1], pole[2], dayLength, insolation,
			polarDay, polarNight, eclipse.kind, eclipse.magnitude, eclipse.obscuration,
			eclipse.umbraRadiusDeg, eclipse.penumbraRadiusDeg, lunarSeparation,
			sunWorld[0], sunWorld[1], sunWorld[2], moonWorld[0], moonWorld[1], moonWorld[2]);
	}

	/** Applies Bennett refraction to a world direction (only the part above the horizon). */
	private static double[] refract(double[] world, SkyContext context) {
		if (!context.refraction()) {
			return world;
		}
		double altitude = ObservingFrame.altitudeOf(world);
		double correction = SkyContext.refractionDegrees(altitude);
		if (correction <= 0.0) {
			return world;
		}
		double azimuth = ObservingFrame.azimuthOf(world);
		double alt = (altitude + correction) * TO_RAD;
		double az = azimuth * TO_RAD;
		double cosAlt = Math.cos(alt);
		return new double[] { cosAlt * Math.sin(az), Math.sin(alt), -cosAlt * Math.cos(az) };
	}

	/** Vanilla-style 8-cell Moon phase for an elongation (0 = full, 4 = new). */
	public static int phaseFromElongation(double elongDeg) {
		double e = AstroTime.mod360(elongDeg);
		return (int) ((Math.floor(e / 45.0) + 4.0) % 8.0 + 8.0) % 8;
	}

	/**
	 * Clamps an elongation so the illuminated fraction never drops below {@code minIllumination}.
	 * E.g. 0.08 keeps a thin crescent instead of a fully dark new Moon.
	 */
	public static double clampElongationForMinIllumination(double elongDeg, double minIllumination) {
		if (minIllumination <= 0.0) {
			return elongDeg;
		}
		double clamped = Math.max(0.0, Math.min(0.45, minIllumination));
		double minSeparation = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, 1.0 - 2.0 * clamped))));
		double e = AstroTime.mod360(elongDeg);
		if (e <= 180.0) {
			return Math.max(e, minSeparation);
		}
		return Math.min(e, 360.0 - minSeparation);
	}
}
