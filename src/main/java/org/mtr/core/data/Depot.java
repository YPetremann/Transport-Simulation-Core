package org.mtr.core.data;

import org.mtr.core.Main;
import org.mtr.core.generated.DepotSchema;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.serializers.ReaderBase;
import org.mtr.core.serializers.WriterBase;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tools.Angle;
import org.mtr.core.tools.DataFixer;
import org.mtr.core.tools.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;

import java.util.Collections;

public final class Depot extends DepotSchema implements Utilities {

	public final ObjectArrayList<Route> routes = new ObjectArrayList<>();

	private final ObjectArrayList<PathData> path = new ObjectArrayList<>();
	/**
	 * A temporary list to store all platforms of the vehicle instructions as well as the route used to get to each platform.
	 * Repeated platforms are ignored.
	 */
	private final ObjectArrayList<ObjectObjectImmutablePair<Platform, Route>> platformsInRoute = new ObjectArrayList<>();
	private final ObjectArrayList<SidingPathFinder<Station, Platform, Station, Platform>> sidingPathFinders = new ObjectArrayList<>();
	private final LongAVLTreeSet generatingSidingIds = new LongAVLTreeSet();

	public static final int CONTINUOUS_MOVEMENT_FREQUENCY = 8000;
	private static final String KEY_PATH = "path";

	public Depot(TransportMode transportMode, Data data) {
		super(transportMode, data);
	}

	public Depot(ReaderBase readerBase, Data data) {
		super(readerBase, data);
		readerBase.iterateReaderArray(KEY_PATH, path::clear, readerBaseChild -> path.add(new PathData(readerBaseChild)));
		updateData(readerBase);
		DataFixer.unpackDepotDepartures(readerBase, realTimeDepartures);
	}

	@Override
	public void serializeFullData(WriterBase writerBase) {
		super.serializeFullData(writerBase);
		writerBase.writeDataset(path, KEY_PATH);
	}

	public void init() {
		writePathCache(true);
		savedRails.forEach(Siding::init);
		generatePlatformDirectionsAndWriteDeparturesToSidings();
	}

	public void writePathCache(boolean removePathIfInvalid) {
		PathData.writePathCache(path, data, removePathIfInvalid);
		savedRails.forEach(siding -> siding.writePathCache(removePathIfInvalid));
	}

	public void setUseRealTime(boolean useRealTime) {
		this.useRealTime = useRealTime;
	}

	public void setFrequency(int hour, int frequency) {
		if (hour >= 0 && hour < HOURS_PER_DAY) {
			while (frequencies.size() < HOURS_PER_DAY) {
				frequencies.add(0);
			}
			frequencies.set(hour, Math.max(0, frequency));
		}
	}

	public void setRepeatInfinitely(boolean repeatInfinitely) {
		this.repeatInfinitely = repeatInfinitely;
	}

	public void setCruisingAltitude(long cruisingAltitude) {
		this.cruisingAltitude = cruisingAltitude;
	}

	public LongArrayList getRouteIds() {
		return routeIds;
	}

	public boolean getRepeatInfinitely() {
		return repeatInfinitely;
	}

	public long getCruisingAltitude() {
		return cruisingAltitude;
	}

	public boolean getUseRealTime() {
		return useRealTime;
	}

	public long getFrequency(int hour) {
		return hour >= 0 && hour < Math.min(HOURS_PER_DAY, frequencies.size()) ? frequencies.getLong(hour) : 0;
	}

	public LongArrayList getRealTimeDepartures() {
		return realTimeDepartures;
	}

	public ObjectArrayList<PathData> getPath() {
		return path;
	}

	public void writeRouteCache(Long2ObjectOpenHashMap<Route> routeIdMap) {
		routes.clear();
		routeIds.forEach(id -> routes.add(routeIdMap.get(id)));
		for (int i = routes.size() - 1; i >= 0; i--) {
			if (routes.get(i) == null) {
				routeIds.removeLong(i);
				routes.remove(i);
			} else {
				routes.get(i).depots.add(this);
			}
		}

		platformsInRoute.clear();
		long previousPlatformId = 0;
		for (final Route route : routes) {
			for (int i = 0; i < route.getRoutePlatforms().size(); i++) {
				final Platform platform = route.getRoutePlatforms().get(i).platform;
				if (platform != null && platform.getId() != previousPlatformId) {
					platformsInRoute.add(new ObjectObjectImmutablePair<>(platform, i == 0 ? null : route));
					previousPlatformId = platform.getId();
				}
			}
		}
	}

	public void generateMainRoute() {
		if (savedRails.isEmpty()) {
			Main.LOGGER.info(String.format("No sidings in %s", name));
		} else {
			Main.LOGGER.info(String.format("Starting path generation for %s...", name));
			path.clear();
			sidingPathFinders.clear();
			generatingSidingIds.clear();
			for (int i = 0; i < platformsInRoute.size() - 1; i++) {
				sidingPathFinders.add(new SidingPathFinder<>(data, platformsInRoute.get(i).left(), platformsInRoute.get(i + 1).left(), i));
			}
			if (sidingPathFinders.isEmpty()) {
				Main.LOGGER.info("At least two platforms are required for path generation");
			}
		}
	}

	public void tick() {
		SidingPathFinder.findPathTick(path, sidingPathFinders, () -> {
			if (!platformsInRoute.isEmpty()) {
				savedRails.forEach(siding -> {
					siding.generateRoute(Utilities.getElement(platformsInRoute, 0).left(), repeatInfinitely ? null : Utilities.getElement(platformsInRoute, -1).left(), platformsInRoute.size(), cruisingAltitude);
					generatingSidingIds.add(siding.getId());
				});
			}
		}, () -> Main.LOGGER.info(String.format("Path not found for %s", name)));
	}

	public void finishGeneratingPath(long sidingId) {
		generatingSidingIds.remove(sidingId);
		if (generatingSidingIds.isEmpty()) {
			Main.LOGGER.info(String.format("Path generation complete for %s", name));
			generatePlatformDirectionsAndWriteDeparturesToSidings();
		}
	}

	public VehicleExtraData.VehiclePlatformRouteInfo getVehiclePlatformRouteInfo(int stopIndex) {
		final int platformCount = platformsInRoute.size();
		final ObjectObjectImmutablePair<Platform, Route> previousData;
		final ObjectObjectImmutablePair<Platform, Route> thisData;
		final ObjectObjectImmutablePair<Platform, Route> nextData;

		if (platformCount == 0) {
			previousData = null;
			thisData = null;
			nextData = null;
		} else if (repeatInfinitely) {
			previousData = Utilities.getElement(platformsInRoute, (stopIndex - 1 + platformCount) % platformCount);
			thisData = Utilities.getElement(platformsInRoute, stopIndex % platformCount);
			nextData = Utilities.getElement(platformsInRoute, (stopIndex + 1) % platformCount);
		} else {
			previousData = Utilities.getElement(platformsInRoute, stopIndex - 1);
			thisData = Utilities.getElement(platformsInRoute, stopIndex);
			nextData = Utilities.getElement(platformsInRoute, stopIndex + 1);
		}

		return new VehicleExtraData.VehiclePlatformRouteInfo(
				previousData == null ? null : previousData.left(),
				thisData == null ? null : thisData.left(),
				nextData == null ? null : nextData.left(),
				thisData == null ? null : thisData.right(),
				nextData == null ? null : nextData.right()
		);
	}

	/**
	 * The first part generates platform directions (N, NE, etc.) for OBA data.
	 * The second part reads from real-time departures and in-game frequencies and converts them to departures.
	 * Each departure is mapped to a siding and siding time segments must be generated beforehand.
	 * Should only be called during initialization (but after siding initialization), when setting world time, and after path generation of all sidings.
	 */
	public void generatePlatformDirectionsAndWriteDeparturesToSidings() {
		final Long2ObjectOpenHashMap<Angle> platformDirections = new Long2ObjectOpenHashMap<>();

		for (int i = 1; i < path.size(); i++) {
			final long platformId = path.get(i - 1).getSavedRailBaseId();
			if (platformId != 0) {
				final Angle newAngle = path.get(i).getFacingStart();
				if (!platformDirections.containsKey(platformId)) {
					platformDirections.put(platformId, newAngle);
				} else if (newAngle != platformDirections.get(platformId)) {
					platformDirections.put(platformId, null);
				}
			}
		}

		platformDirections.forEach((platformId, angle) -> {
			final Platform platform = data.platformIdMap.get(platformId.longValue());
			if (platform != null) {
				platform.setAngles(id, angle);
			}
		});

		final LongArrayList departures = new LongArrayList();

		if (transportMode.continuousMovement) {
			for (int i = 0; i < savedRails.size(); i += CONTINUOUS_MOVEMENT_FREQUENCY) {
				departures.add(i);
			}
		} else {
			if (useRealTime) {
				departures.addAll(realTimeDepartures);
			} else if (data instanceof Simulator && ((Simulator) data).getGameMillisPerDay() > 0) {
				final Simulator simulator = (Simulator) data;
				final long offsetMillis = simulator.getMillisOfGameMidnight();
				final LongArrayList gameDepartures = new LongArrayList();

				for (int i = 0; i < HOURS_PER_DAY; i++) {
					if (getFrequency(i) == 0) {
						continue;
					}

					final long intervalMillis = 14400000 / getFrequency(i);
					final long hourMinMillis = MILLIS_PER_HOUR * i;
					final long hourMaxMillis = MILLIS_PER_HOUR * (i + 1);

					while (true) {
						final long newDeparture = Math.max(hourMinMillis, Utilities.getElement(gameDepartures, -1, Long.MIN_VALUE) + intervalMillis);
						if (newDeparture < hourMaxMillis) {
							departures.add(offsetMillis + newDeparture * simulator.getGameMillisPerDay() / MILLIS_PER_DAY);
							gameDepartures.add(newDeparture);
						} else {
							break;
						}
					}
				}
			}
		}

		final ObjectArrayList<Siding> sidingsInDepot = new ObjectArrayList<>(savedRails);
		if (!sidingsInDepot.isEmpty()) {
			Collections.shuffle(sidingsInDepot);
			Collections.sort(sidingsInDepot);
			sidingsInDepot.forEach(Siding::startGeneratingDepartures);
			int sidingIndex = 0;
			for (final long departure : departures) {
				for (int i = 0; i < sidingsInDepot.size(); i++) {
					if (sidingsInDepot.get((sidingIndex + i) % sidingsInDepot.size()).addDeparture(departure)) {
						sidingIndex++;
						break;
					}
				}
			}
		}
	}
}
