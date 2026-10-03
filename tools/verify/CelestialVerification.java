import com.xiaoshi.astro.AstroTime;
import com.xiaoshi.astro.EclipseCalculator;
import com.xiaoshi.astro.SkyContext;
import com.xiaoshi.sky.Celestial;

/**
 * Headless verification of the StarRadiance ephemeris against published events.
 *
 *   javac -d build/verify src/main/java/com/xiaoshi/astro/*.java src/main/java/com/xiaoshi/sky/Celestial.java \
 *         tools/verify/CelestialVerification.java && java -cp build/verify CelestialVerification
 *
 * (Gradle task {@code verifyEphemeris} runs exactly that.)
 */
public final class CelestialVerification {
	private static int failures;

	public static void main(String[] args) {
		// --- Moon phases: published instants (UTC) of syzygies -------------------------------
		checkPhase("2024-04-08 new moon", 2024, 4, 8, 18, 21, false, 20.0);
		checkPhase("2026-08-12 new moon", 2026, 8, 12, 17, 46, false, 20.0);
		checkPhase("2025-03-14 full moon", 2025, 3, 14, 6, 55, true, 20.0);
		checkPhase("2022-11-08 full moon", 2022, 11, 8, 10, 59, true, 20.0);
		checkPhase("2024-09-18 full moon", 2024, 9, 18, 2, 34, true, 20.0);

		// --- Equinoxes: the Sun's apparent longitude crosses 0 / 180 --------------------------
		checkEquinox("2024-03-20 equinox", 2024, 3, 20, 3, 6, 0.0, 30.0);
		checkEquinox("2024-09-22 equinox", 2024, 9, 22, 12, 44, 180.0, 30.0);
		checkEquinox("2025-03-20 equinox", 2025, 3, 20, 9, 1, 0.0, 30.0);

		// --- Eclipses ------------------------------------------------------------------------
		checkSolarTotality("2024-04-08 total solar eclipse over Dallas", 2024, 4, 8, 18, 42, 32.78, -96.80, 0.95);
		checkSolarTotality("2026-08-12 total solar eclipse over Iceland", 2026, 8, 12, 17, 46, 64.15, -21.94, 0.90);
		checkLunarEclipse("2025-03-14 total lunar eclipse", 2025, 3, 14, 6, 58, 1.0);
		checkLunarEclipse("2022-11-08 total lunar eclipse", 2022, 11, 8, 10, 59, 1.0);
		checkNoEclipse("2026-02-17 (no eclipse)", 2026, 2, 17, 12, 0);

		// --- Ranges and constants ------------------------------------------------------------
		SkyContext.set(new SkyContext(jd(2026, 1, 1, 0, 0), 116.4, SkyContext.ClockMode.UTC, true));
		double minDistance = Double.MAX_VALUE;
		double maxDistance = 0.0;
		double maxLatitude = 0.0;
		double maxDeclination = 0.0;
		for (long tick = 0; tick < 400L * Celestial.TICKS_PER_DAY; tick += 250L) {
			Celestial.SkyState s = Celestial.compute(tick, 23.5);
			minDistance = Math.min(minDistance, s.moonDistanceKm);
			maxDistance = Math.max(maxDistance, s.moonDistanceKm);
			maxLatitude = Math.max(maxLatitude, Math.abs(s.moonEclipticLatitudeDeg));
			maxDeclination = Math.max(maxDeclination, Math.abs(s.sunDeclinationDeg));
			if (s.moonIllumination < 0.0 || s.moonIllumination > 1.0) {
				fail("moon illumination out of range: " + s.moonIllumination);
			}
			if (Double.isNaN(s.moonX) || Double.isNaN(s.sunX)) {
				fail("NaN direction vector");
			}
		}
		check("moon distance range", minDistance > 350000.0 && maxDistance < 412000.0,
			String.format("%.0f..%.0f km", minDistance, maxDistance));
		check("moon ecliptic latitude amplitude", maxLatitude > 4.9 && maxLatitude < 5.4,
			String.format("%.2f deg", maxLatitude));
		check("sun declination amplitude", maxDeclination > 23.3 && maxDeclination < 23.6,
			String.format("%.2f deg", maxDeclination));

		// --- Rates: a sidereal day must be 23h56m of solar time ---------------------------------
		SkyContext.set(new SkyContext(jd(2026, 1, 1, 0, 0), 0.0, SkyContext.ClockMode.UTC, false));
		double lst0 = Celestial.compute(0L, 0.0).localSiderealTimeDeg;
		double lst1 = Celestial.compute(Celestial.TICKS_PER_DAY, 0.0).localSiderealTimeDeg;
		double advance = AstroTime.mod360(lst1 - lst0);
		check("sidereal advance per solar day", Math.abs(advance - 0.9856) < 0.01,
			String.format("%.4f deg (expect 0.9856)", advance));

		// --- Star frame: Polaris must sit at altitude ≈ latitude, azimuth ≈ north ---------------
		Celestial.SkyState polarisState = Celestial.compute(0L, 45.0);
		double polarisRa = 2.530301 * 15.0;
		double polarisDec = 89.264109;
		double[] polarisEq = com.xiaoshi.astro.Precession.equatorialUnitVector(polarisRa, polarisDec);
		double[] polarisWorld = com.xiaoshi.astro.Precession.apply(polarisState.starMatrix,
			polarisEq[0], polarisEq[1], polarisEq[2]);
		double polarisAlt = Math.toDegrees(Math.asin(polarisWorld[1]));
		double polarisAz = AstroTime.mod360(Math.toDegrees(Math.atan2(polarisWorld[0], -polarisWorld[2])));
		check("Polaris altitude ~ latitude", Math.abs(polarisAlt - 45.0) < 2.0,
			String.format("%.2f deg (expect ~45)", polarisAlt));
		check("Polaris azimuth ~ north", polarisAz < 3.0 || polarisAz > 357.0,
			String.format("%.2f deg", polarisAz));

		// --- Game clock <-> sky: MC tick 0 = 06:00 local, 6000 = local noon ---------------------
		double alignedEpoch = SkyContext.alignEpochToGameClock(jd(2026, 3, 20, 0, 0));
		SkyContext.set(new SkyContext(alignedEpoch, 116.4, SkyContext.ClockMode.LOCAL_MEAN_SOLAR, false));
		Celestial.SkyState sunrise = Celestial.compute(0L, 23.5);
		check("tick 0 = sunrise in the east", sunrise.sunAzimuthDeg > 80.0 && sunrise.sunAzimuthDeg < 100.0
				&& Math.abs(sunrise.sunAltitudeDeg) < 2.0,
			String.format("alt=%.2f az=%.2f", sunrise.sunAltitudeDeg, sunrise.sunAzimuthDeg));
		Celestial.SkyState noonState = Celestial.compute(6000L, 23.5);
		check("tick 6000 = local noon in the south", noonState.sunAzimuthDeg > 170.0 && noonState.sunAzimuthDeg < 190.0
				&& noonState.sunAltitudeDeg > 60.0,
			String.format("alt=%.2f az=%.2f", noonState.sunAltitudeDeg, noonState.sunAzimuthDeg));
		Celestial.SkyState midnight = Celestial.compute(18000L, 23.5);
		check("tick 18000 = sun far below the horizon", midnight.sunAltitudeDeg < -60.0,
			String.format("alt=%.2f", midnight.sunAltitudeDeg));

		// --- Orbit diagram geometry -------------------------------------------------------------
		com.xiaoshi.astro.OrbitProjection projection =
			new com.xiaoshi.astro.OrbitProjection(35.0, 25.0);
		double minX = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (int i = 0; i < 720; i++) {
			double angle = 2.0 * Math.PI * i / 720.0;
			double[] p = projection.project(Math.cos(angle), Math.sin(angle), 0.0, 100.0, 0.0, 0.0);
			minX = Math.min(minX, p[0]);
			maxX = Math.max(maxX, p[0]);
			minY = Math.min(minY, p[1]);
			maxY = Math.max(maxY, p[1]);
		}
		double ratio = (maxY - minY) / (maxX - minX);
		check("ecliptic circle projects with ratio sin(elevation)",
			Math.abs(ratio - Math.sin(Math.toRadians(25.0))) < 1.0E-6,
			String.format("ratio=%.6f expect=%.6f", ratio, Math.sin(Math.toRadians(25.0))));
		double[] normal = projection.project(0.0, 0.0, 1.0, 100.0, 0.0, 0.0);
		check("ecliptic normal projects vertically", Math.abs(normal[0]) < 1.0E-9 && normal[1] < 0.0,
			String.format("dx=%.3e dy=%.2f", normal[0], normal[1]));

		double jdDiagram = jd(2026, 3, 20, 12, 0);
		double[][] earthOrbit = com.xiaoshi.astro.OrbitSampler.earthHeliocentricOrbit(
			AstroTime.julianDateTt(jdDiagram), 0L);
		double minRadius = Double.MAX_VALUE;
		double maxRadius = 0.0;
		double perihelionLongitude = 0.0;
		for (double[] point : earthOrbit) {
			double radius = Math.hypot(point[0], point[1]);
			if (radius < minRadius) {
				minRadius = radius;
				perihelionLongitude = AstroTime.mod360(Math.toDegrees(Math.atan2(point[1], point[0])));
			}
			maxRadius = Math.max(maxRadius, radius);
		}
		check("earth orbit radius range",
			minRadius > 0.980 && minRadius < 0.987 && maxRadius > 1.013 && maxRadius < 1.020,
			String.format("%.4f..%.4f AU", minRadius, maxRadius));
		double closure = Math.hypot(earthOrbit[0][0] - earthOrbit[earthOrbit.length - 1][0],
			earthOrbit[0][1] - earthOrbit[earthOrbit.length - 1][1]);
		check("earth orbit closes", closure < 0.02, String.format("%.4f AU", closure));
		check("perihelion longitude ~103 deg",
			Math.abs(AstroTime.mod360(perihelionLongitude - 103.0 + 180.0) - 180.0) < 8.0,
			String.format("%.1f deg", perihelionLongitude));

		double[][] moonOrbit = com.xiaoshi.astro.OrbitSampler.moonGeocentricOrbit(
			AstroTime.julianDateTt(jdDiagram), 0L);
		double moonMin = Double.MAX_VALUE;
		double moonMax = 0.0;
		for (double[] point : moonOrbit) {
			double radius = Math.sqrt(point[0] * point[0] + point[1] * point[1] + point[2] * point[2]);
			moonMin = Math.min(moonMin, radius);
			moonMax = Math.max(moonMax, radius);
		}
		check("moon orbit distance range", moonMin > 350000.0 && moonMax < 412000.0,
			String.format("%.0f..%.0f km", moonMin, moonMax));
		double normalError = 0.0;
		for (int i = 0; i < 8; i++) {
			double[] a = moonOrbit[i * 4];
			double[] b = moonOrbit[i * 4 + 1];
			double nx = a[1] * b[2] - a[2] * b[1];
			double ny = a[2] * b[0] - a[0] * b[2];
			double nz = a[0] * b[1] - a[1] * b[0];
			double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
			double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, Math.abs(nz) / length))));
			normalError = Math.max(normalError, Math.abs(angle - 5.145));
		}
		check("moon orbit inclination ~5.145 deg", normalError < 0.6,
			String.format("max deviation %.3f deg", normalError));

		double[] axis = com.xiaoshi.astro.OrbitSampler.earthRotationAxis();
		double axisAngle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, axis[2]))));
		check("earth axis tilt ~23.44 deg", Math.abs(axisAngle - 23.4392911) < 0.01,
			String.format("%.5f deg", axisAngle));

		// --- Sphere projection (the zoomed globe) ------------------------------------------------
		com.xiaoshi.astro.SphereProjection sphere = new com.xiaoshi.astro.SphereProjection(
			com.xiaoshi.astro.SphereProjection.normalize(new double[] { 0.3, -0.4, 0.86 }));
		double[] sunScreen = sphere.inScreenBasis(
			com.xiaoshi.astro.SphereProjection.normalize(new double[] { 0.5, 0.2, 0.84 }));
		double unlitArea = 0.0;
		double totalArea = 0.0;
		int bands = 200;
		for (int i = 0; i < bands; i++) {
			double dy = -1.0 + 2.0 * (i + 0.5) / bands;
			double w = Math.sqrt(Math.max(0.0, 1.0 - dy * dy));
			if (w <= 1.0E-9) {
				continue;
			}
			totalArea += 2.0 * w;
			double[] night = com.xiaoshi.astro.SphereProjection.nightSpan(dy, w, sunScreen);
			if (night != null) {
				unlitArea += night[1] - night[0];
			}
		}
		double rasterLit = 1.0 - unlitArea / totalArea;
		double analyticLit = com.xiaoshi.astro.SphereProjection.litFraction(sunScreen);
		check("globe terminator area matches analytic",
			Math.abs(rasterLit - analyticLit) < 0.01,
			String.format("raster=%.4f analytic=%.4f", rasterLit, analyticLit));

		double observerLat = 39.9;
		double observerLon = 116.4;
		double localSidereal = 123.4;
		double[] viaSurface = com.xiaoshi.astro.SphereProjection.earthSurfaceDirection(observerLat,
			observerLon, AstroTime.mod360(localSidereal - observerLon));
		double[] viaObserver = com.xiaoshi.astro.OrbitSampler.observerDirection(observerLat, localSidereal);
		double observerError = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0,
			com.xiaoshi.astro.SphereProjection.dot(viaSurface, viaObserver)))));
		check("globe observer direction matches observer frame", observerError < 0.05,
			String.format("%.4f deg", observerError));

		SkyContext.set(new SkyContext(jd(2026, 6, 21, 12, 0), 0.0, SkyContext.ClockMode.UTC, false));
		Celestial.SkyState subsolarState = Celestial.compute(0L, 0.0);
		double subsolarLambda = Math.toRadians(subsolarState.sunApparentLongitudeDeg);
		double[] sunEcliptic = { Math.cos(subsolarLambda), Math.sin(subsolarLambda), 0.0 };
		double subsolarLatitude = 90.0 - Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0,
			com.xiaoshi.astro.SphereProjection.dot(sunEcliptic, com.xiaoshi.astro.OrbitSampler.earthRotationAxis())))));
		check("subsolar latitude equals sun declination",
			Math.abs(subsolarLatitude - subsolarState.sunDeclinationDeg) < 0.1,
			String.format("%.3f vs %.3f deg", subsolarLatitude, subsolarState.sunDeclinationDeg));

		// --- Star chart projection (the U screen's star wheel) -----------------------------------
		com.xiaoshi.astro.SkyChartProjection wheel =
			new com.xiaoshi.astro.SkyChartProjection(400.0, 300.0, 260.0, 1.0, 0.0, 0.0);
		double[] wheelZenith = wheel.project(90.0, 123.0);
		check("star wheel: zenith at the centre",
			Math.hypot(wheelZenith[0] - 400.0, wheelZenith[1] - 300.0) < 1.0E-9,
			String.format("(%.3f, %.3f)", wheelZenith[0], wheelZenith[1]));
		double[] wheelNorth = wheel.project(0.0, 0.0);
		check("star wheel: north on the rim, straight up",
			Math.abs(wheelNorth[0] - 400.0) < 1.0E-9 && Math.abs(wheelNorth[1] - 40.0) < 1.0E-9,
			String.format("(%.3f, %.3f)", wheelNorth[0], wheelNorth[1]));
		double[] wheelEast = wheel.project(0.0, 90.0);
		check("star wheel: east on the rim, straight right",
			Math.abs(wheelEast[0] - 660.0) < 1.0E-9 && Math.abs(wheelEast[1] - 300.0) < 1.0E-9,
			String.format("(%.3f, %.3f)", wheelEast[0], wheelEast[1]));
		double[] wheelMid = wheel.project(45.0, 180.0);
		check("star wheel: radius linear in altitude",
			Math.abs(wheelMid[1] - 430.0) < 1.0E-9,
			String.format("y=%.3f (expect 430)", wheelMid[1]));
		com.xiaoshi.astro.SkyChartProjection wheelZoomed =
			new com.xiaoshi.astro.SkyChartProjection(400.0, 300.0, 260.0, 4.0, 0.0, 0.0);
		check("star wheel: zoom scales the rim linearly",
			Math.abs(wheelZoomed.radiusOf(0.0) - 4.0 * wheel.radiusOf(0.0)) < 1.0E-9,
			String.format("%.3f px", wheelZoomed.radiusOf(0.0)));
		double roundTripError = 0.0;
		for (double altitude = 0.0; altitude <= 89.0; altitude += 7.0) {
			for (double azimuth = 0.0; azimuth < 360.0; azimuth += 23.0) {
				double[] screen = wheel.project(altitude, azimuth);
				double[] back = wheel.unproject(screen[0], screen[1]);
				double azimuthError = Math.abs(AstroTime.mod360(back[1] - azimuth + 180.0) - 180.0);
				roundTripError = Math.max(roundTripError, Math.abs(back[0] - altitude));
				if (altitude > 0.05) {
					roundTripError = Math.max(roundTripError, azimuthError);
				}
			}
		}
		check("star wheel: screen <-> alt/az round trip", roundTripError < 0.01,
			String.format("%.6f deg", roundTripError));

		SkyContext.set(new SkyContext(alignedEpoch, 116.4, SkyContext.ClockMode.LOCAL_MEAN_SOLAR, false));
		Celestial.SkyState chartState = Celestial.compute(6000L, 45.0);
		double[] polarisChartWorld = com.xiaoshi.astro.Precession.apply(chartState.starMatrix,
			polarisEq[0], polarisEq[1], polarisEq[2]);
		double[] polarisChart = wheel.unproject(wheel.projectWorld(polarisChartWorld)[0],
			wheel.projectWorld(polarisChartWorld)[1]);
		check("star wheel: Polaris above the horizon at 45N",
			Math.abs(polarisChart[0] - 45.0) < 2.0,
			String.format("alt=%.2f az=%.2f", polarisChart[0], polarisChart[1]));

		// --- Planets: positions, orbits and axial tilts ------------------------------------------
		double planetT = AstroTime.centuriesSinceJ2000(AstroTime.julianDateTt(jd(2026, 1, 1, 0, 0)));
		double[] planetEarth = com.xiaoshi.astro.PlanetPosition.heliocentricAu(
			com.xiaoshi.astro.PlanetPosition.EARTH, planetT);
		com.xiaoshi.astro.SunPosition planetSun = com.xiaoshi.astro.SunPosition.compute(planetT);
		double earthLongitude = AstroTime.mod360(Math.toDegrees(Math.atan2(planetEarth[1], planetEarth[0])));
		double sunDerived = AstroTime.mod360(planetSun.apparentLongitudeDeg + 180.0);
		double longitudeError = Math.abs(AstroTime.mod360(earthLongitude - sunDerived + 180.0) - 180.0);
		check("planet Earth longitude matches the solar ephemeris", longitudeError < 0.05,
			String.format("%.4f deg (%.3f vs %.3f)", longitudeError, earthLongitude, sunDerived));
		check("planet Earth distance matches the solar ephemeris",
			Math.abs(Math.hypot(planetEarth[0], planetEarth[1]) - planetSun.distanceAu) < 0.001,
			String.format("%.6f vs %.6f AU", Math.hypot(planetEarth[0], planetEarth[1]),
				planetSun.distanceAu));

		for (int planet = 0; planet < com.xiaoshi.astro.PlanetPosition.COUNT; planet++) {
			double[][] orbit = com.xiaoshi.astro.PlanetPosition.orbitAu(planet, planetT, 64);
			double a = com.xiaoshi.astro.PlanetPosition.meanDistanceAu(planet);
			double orbitMin = Double.MAX_VALUE;
			double orbitMax = 0.0;
			for (double[] point : orbit) {
				double radius = Math.sqrt(point[0] * point[0] + point[1] * point[1] + point[2] * point[2]);
				orbitMin = Math.min(orbitMin, radius);
				orbitMax = Math.max(orbitMax, radius);
			}
			double orbitClosure = Math.hypot(orbit[0][0] - orbit[orbit.length - 1][0],
				orbit[0][1] - orbit[orbit.length - 1][1]);
			check("orbit " + com.xiaoshi.astro.PlanetPosition.KEYS[planet] + " closes and fits its axis",
				orbitClosure < 1.0E-9 && orbitMin > 0.02 * a && orbitMax < 2.1 * a,
				String.format("%.4f..%.4f AU (a=%.4f, closure %.2e)", orbitMin, orbitMax, a,
					orbitClosure));
		}

		// Small bodies: their heliocentric distances in 2026 pin down the orbital phase, because
		// several of them are close to a turning point of their orbit right now.
		checkSmallBody("pluto", 34.0, 37.5, planetT);
		checkSmallBody("ceres", 2.50, 3.00, planetT);
		checkSmallBody("eris", 85.0, 100.0, planetT);
		checkSmallBody("haumea", 45.0, 52.0, planetT);
		checkSmallBody("makemake", 48.0, 54.0, planetT);
		checkSmallBody("halley", 34.5, 35.6, planetT);

		double[] earthAxis = com.xiaoshi.astro.PlanetPosition.poleJ2000(
			com.xiaoshi.astro.PlanetPosition.EARTH, 0.0);
		check("planet Earth axial tilt ~23.44 deg",
			Math.abs(Math.toDegrees(Math.acos(Math.abs(earthAxis[2]))) - 23.4392911) < 0.01,
			String.format("%.5f deg", Math.toDegrees(Math.acos(Math.abs(earthAxis[2])))));
		double[] uranusAxis = com.xiaoshi.astro.PlanetPosition.poleJ2000(
			com.xiaoshi.astro.PlanetPosition.URANUS, 0.0);
		check("planet Uranus lies on its side (82 deg from the ecliptic pole)",
			Math.abs(Math.toDegrees(Math.acos(Math.abs(uranusAxis[2]))) - 82.3) < 0.5,
			String.format("%.2f deg", Math.toDegrees(Math.acos(Math.abs(uranusAxis[2])))));

		// Rotation rates: Mars turns +350.9 deg per day, Venus backwards at -1.48 deg per day.
		double[] marsDay0 = com.xiaoshi.astro.PlanetPosition.surfaceDirection(
			com.xiaoshi.astro.PlanetPosition.MARS, 0.0, 0.0, 0.0);
		double[] marsDay1 = com.xiaoshi.astro.PlanetPosition.surfaceDirection(
			com.xiaoshi.astro.PlanetPosition.MARS, 0.0, 0.0, 1.0);
		double marsTurn = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0,
			com.xiaoshi.astro.SphereProjection.dot(marsDay0, marsDay1)))));
		check("planet Mars turns ~9 deg per day (350.9 deg of rotation)",
			Math.abs(marsTurn - 9.108) < 0.05, String.format("%.3f deg", marsTurn));
		double[] venusDay0 = com.xiaoshi.astro.PlanetPosition.surfaceDirection(
			com.xiaoshi.astro.PlanetPosition.VENUS, 0.0, 0.0, 0.0);
		double[] venusDay1 = com.xiaoshi.astro.PlanetPosition.surfaceDirection(
			com.xiaoshi.astro.PlanetPosition.VENUS, 0.0, 0.0, 1.0);
		double venusTurn = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0,
			com.xiaoshi.astro.SphereProjection.dot(venusDay0, venusDay1)))));
		check("planet Venus turns ~1.5 deg per day (retrograde)",
			Math.abs(venusTurn - 1.481) < 0.05, String.format("%.3f deg", venusTurn));

		// End-to-end: the drawn planets must reproduce the real elongations seen from the Earth.
		double maxVenus = 0.0;
		double maxMercury = 0.0;
		double minMars = 180.0;
		double startJd = jd(2026, 1, 1, 0, 0);
		for (int day = 0; day <= 800; day++) {
			double t = AstroTime.centuriesSinceJ2000(AstroTime.julianDateTt(startJd + day));
			double[] earth = com.xiaoshi.astro.PlanetPosition.heliocentricAu(
				com.xiaoshi.astro.PlanetPosition.EARTH, t);
			maxVenus = Math.max(maxVenus, elongation(earth, t, com.xiaoshi.astro.PlanetPosition.VENUS));
			maxMercury = Math.max(maxMercury, elongation(earth, t, com.xiaoshi.astro.PlanetPosition.MERCURY));
			minMars = Math.min(minMars, elongation(earth, t, com.xiaoshi.astro.PlanetPosition.MARS));
		}
		check("drawn Venus reaches its real 46 deg elongation",
			maxVenus > 44.0 && maxVenus < 48.0, String.format("%.2f deg", maxVenus));
		check("drawn Mercury reaches its real ~28 deg elongation",
			maxMercury > 17.0 && maxMercury < 29.0, String.format("%.2f deg", maxMercury));
		check("drawn Mars reaches opposition within a synodic period",
			minMars < 5.0, String.format("%.2f deg", minMars));

		System.out.println();
		if (failures == 0) {
			System.out.println("ALL CHECKS PASSED");
		} else {
			System.out.println(failures + " CHECK(S) FAILED");
		}
	}

	private static void checkPhase(String label, int year, int month, int day, int hour, int minute,
			boolean full, double toleranceMinutes) {
		double target = jd(year, month, day, hour, minute);
		double best = 0.0;
		double bestDiff = Double.MAX_VALUE;
		double stepMinutes = 0.25;
		for (double offset = -180.0; offset <= 180.0; offset += stepMinutes) {
			double jd = target + offset / 1440.0;
			SkyContext.set(new SkyContext(jd, 0.0, SkyContext.ClockMode.UTC, false));
			Celestial.SkyState s = Celestial.compute(0L, 0.0);
			double value = full ? Math.abs(AstroTime.mod360(s.moonElongationDeg) - 180.0)
				: Math.min(AstroTime.mod360(s.moonElongationDeg), 360.0 - AstroTime.mod360(s.moonElongationDeg));
			if (value < bestDiff) {
				bestDiff = value;
				best = jd;
			}
		}
		double errorMinutes = (best - target) * 1440.0;
		check(label, Math.abs(errorMinutes) <= toleranceMinutes,
			String.format("error %+.1f min (elong %.3f deg)", errorMinutes, bestDiff));
	}

	private static void checkEquinox(String label, int year, int month, int day, int hour, int minute,
			double targetLongitude, double toleranceMinutes) {
		double target = jd(year, month, day, hour, minute);
		double best = 0.0;
		double bestDiff = Double.MAX_VALUE;
		for (double offset = -720.0; offset <= 720.0; offset += 0.5) {
			double jd = target + offset / 1440.0;
			SkyContext.set(new SkyContext(jd, 0.0, SkyContext.ClockMode.UTC, false));
			Celestial.SkyState s = Celestial.compute(0L, 0.0);
			double diff = Math.abs(AstroTime.mod360(s.sunApparentLongitudeDeg - targetLongitude + 180.0) - 180.0);
			if (diff < bestDiff) {
				bestDiff = diff;
				best = jd;
			}
		}
		double errorMinutes = (best - target) * 1440.0;
		check(label, Math.abs(errorMinutes) <= toleranceMinutes,
			String.format("error %+.1f min (longitude %.4f deg)", errorMinutes, bestDiff));
	}

	private static void checkSolarTotality(String label, int year, int month, int day, int hour, int minute,
			double latitude, double longitude, double minimumObscuration) {
		SkyContext.set(new SkyContext(jd(year, month, day, hour, minute), longitude,
			SkyContext.ClockMode.UTC, false));
		Celestial.SkyState s = Celestial.compute(0L, latitude);
		check(label, s.eclipseKind == EclipseCalculator.SOLAR && s.eclipseObscuration >= minimumObscuration,
			String.format("kind=%d obscuration=%.3f magnitude=%.3f separation=%.4f deg",
				s.eclipseKind, s.eclipseObscuration, s.eclipseMagnitude, s.moonShadowSeparationDeg));
	}

	private static void checkLunarEclipse(String label, int year, int month, int day, int hour, int minute,
			double minimumMagnitude) {
		SkyContext.set(new SkyContext(jd(year, month, day, hour, minute), 0.0, SkyContext.ClockMode.UTC, false));
		Celestial.SkyState s = Celestial.compute(0L, 0.0);
		check(label, s.eclipseKind == EclipseCalculator.LUNAR && s.eclipseMagnitude >= minimumMagnitude,
			String.format("kind=%d magnitude=%.3f obscuration=%.3f umbra=%.3f penumbra=%.3f",
				s.eclipseKind, s.eclipseMagnitude, s.eclipseObscuration, s.umbraRadiusDeg, s.penumbraRadiusDeg));
	}

	private static void checkNoEclipse(String label, int year, int month, int day, int hour, int minute) {
		SkyContext.set(new SkyContext(jd(year, month, day, hour, minute), 0.0, SkyContext.ClockMode.UTC, false));
		Celestial.SkyState s = Celestial.compute(0L, 0.0);
		check(label, s.eclipseKind == EclipseCalculator.NONE,
			String.format("kind=%d separation=%.2f deg", s.eclipseKind, s.moonShadowSeparationDeg));
	}

	/** Geocentric elongation of a planet from the Sun, as drawn in the orbit chart. */
	/** Checks that a small body's heliocentric distance on 2026-01-01 falls in the expected range. */
	private static void checkSmallBody(String key, double min, double max, double t) {
		int index = -1;
		for (int i = 0; i < com.xiaoshi.astro.PlanetPosition.KEYS.length; i++) {
			if (com.xiaoshi.astro.PlanetPosition.KEYS[i].equals(key)) {
				index = i;
			}
		}
		double[] position = com.xiaoshi.astro.PlanetPosition.heliocentricAu(index, t);
		double distance = Math.sqrt(position[0] * position[0] + position[1] * position[1]
			+ position[2] * position[2]);
		check("small body " + key + " distance in 2026",
			distance >= min && distance <= max,
			String.format("%.2f AU (expect %.1f..%.1f)", distance, min, max));
	}

	private static double elongation(double[] earth, double t, int planet) {
		double[] body = com.xiaoshi.astro.PlanetPosition.heliocentricAu(planet, t);
		double dx = body[0] - earth[0];
		double dy = body[1] - earth[1];
		double dz = body[2] - earth[2];
		double toSunX = -earth[0];
		double toSunY = -earth[1];
		double toSunZ = -earth[2];
		double dot = dx * toSunX + dy * toSunY + dz * toSunZ;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz)
			* Math.sqrt(toSunX * toSunX + toSunY * toSunY + toSunZ * toSunZ);
		return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot / length))));
	}

	private static void check(String label, boolean ok, String detail) {
		if (!ok) {
			failures++;
			System.out.printf("FAIL %-48s %s%n", label, detail);
		} else {
			System.out.printf("ok   %-48s %s%n", label, detail);
		}
	}

	private static void fail(String message) {
		failures++;
		System.out.println("FAIL " + message);
	}

	/** Julian date (UTC) for a calendar instant, valid for the Gregorian calendar. */
	static double jd(int year, int month, int day, int hour, int minute) {
		int y = year;
		int m = month;
		if (m <= 2) {
			y--;
			m += 12;
		}
		int a = y / 100;
		int b = 2 - a + a / 4;
		double dayFraction = (hour + minute / 60.0) / 24.0;
		return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1))
			+ day + dayFraction + b - 1524.5;
	}
}
