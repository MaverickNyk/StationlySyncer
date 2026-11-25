package com.stationly.backend.notices;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stationly.backend.client.TflApi;
import com.stationly.backend.service.LiveStreamPublisher;
import com.stationly.backend.service.LocalDatabaseService;
import com.stationly.backend.service.NotificationService;
import com.stationly.backend.service.TflPollingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Which stations are closed. Asks TfL, decides ({@link StationStatusRules}), tells
 * phones on each stop's FCM topic, and posts everything to the backend to serve
 * (stationly-backend/docs/STATION_STATUS.md). The same shape as line status.
 *
 * Not behind a flag: apps that do not know the message ignore it, and the app's own
 * flag decides whether one that does acts on it.
 *
 * <h2>Being certain</h2>
 * <ul>
 * <li>A state must be in two polls running before it counts. Clearing needs one.
 * <li>A failed or implausible answer changes nothing. It is not "all reopened".
 * <li>Without a good answer for a while we know nothing, and say nothing.
 * <li>What the last poll meant, and what phones were told, survive a restart: a
 * station that reopened meanwhile is still cleared.
 * </ul>
 *
 * On its own thread: a slow TfL must not hold up arrivals or line status. Like
 * arrivals it naps while nobody has such a stop on a board.
 */
@Service
@Slf4j
public class StationNoticeService {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    /** After the last trains and before the first: asked less often. */
    private static final LocalTime NIGHT_FROM = LocalTime.of(1, 30);
    private static final LocalTime NIGHT_TO = LocalTime.of(4, 30);
    private static final long TICK_SECONDS = 10;
    /** There are always dozens (80 rail on 2026-11-25). Suddenly few, or few we can read, is TfL failing politely. */
    private static final int IMPLAUSIBLE_FROM = 10;
    private static final String TOLD = "told";

    /** @param stale several polls: after this long without a good answer we know nothing */
    enum Group {
        RAIL(Duration.ofMinutes(1), Duration.ofMinutes(3), Duration.ofMinutes(10)),
        BUS(Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(25));

        final Duration day;
        final Duration night;
        final long staleMs;

        Group(Duration day, Duration night, Duration stale) {
            this.day = day;
            this.night = night;
            this.staleMs = stale.toMillis();
        }

        String key() {
            return name().toLowerCase();
        }

        /** By the rail ids (tube, DLR and tram; National Rail): TfL's bus stops outside London are 210, 240, 400... */
        static Group of(String stationId) {
            return stationId.startsWith("940G") || stationId.startsWith("910G") ? RAIL : BUS;
        }

        Duration interval(Instant now) {
            LocalTime t = now.atZone(LONDON).toLocalTime();
            return !t.isBefore(NIGHT_FROM) && t.isBefore(NIGHT_TO) ? night : day;
        }
    }

    /**
     * @param runs    good answers running. One is not enough to say a station is open
     * @param notices how many TfL gave
     * @param seen    in the last poll
     * @param held    in two running: what we hold to be true
     */
    record State(long lastOk, int runs, int notices, Map<String, StationStatus> seen, Map<String, StationStatus> held) {
        static final State NONE = new State(0, 0, 0, Map.of(), Map.of());
    }

    /** What phones were last told about a stop. */
    record Told(StationNotice notice, long at) {
    }

    private final TflApi tflApi;
    private final NotificationService fcm;
    private final LiveStreamPublisher backend;
    private final LocalDatabaseService db;
    /** Null means every station ("all" strategy). */
    private final Supplier<Set<String>> subscribed;
    private final ObjectMapper json;

    private final Map<Group, State> states = new HashMap<>();
    private final Map<Group, Instant> askedAt = new HashMap<>();
    private Map<String, Told> told = new HashMap<>();
    private boolean loaded;
    private ScheduledExecutorService executor;

    @Value("${station.notices.enabled:true}")
    boolean enabled = true;

    @Value("${station.notices.rail-modes:tube,dlr,overground,elizabeth-line,tram}")
    String railModes = "tube,dlr,overground,elizabeth-line,tram";

    @Autowired
    public StationNoticeService(TflApi tflApi, NotificationService fcm, LiveStreamPublisher backend,
                                LocalDatabaseService db, TflPollingService polling, ObjectMapper json) {
        this(tflApi, fcm, backend, db, polling::getActiveSubscribedStations, json);
    }

    StationNoticeService(TflApi tflApi, NotificationService fcm, LiveStreamPublisher backend,
                         LocalDatabaseService db, Supplier<Set<String>> subscribed, ObjectMapper json) {
        this.tflApi = tflApi;
        this.fcm = fcm;
        this.backend = backend;
        this.db = db;
        this.subscribed = subscribed;
        this.json = json;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("ℹ️ Station notices disabled (station.notices.enabled=false).");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "station-notices");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> tick(Instant.now()), TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) executor.shutdownNow();
    }

    /** Ask for whatever is due, then bring phones up to date. Never throws: that would end the schedule for good. */
    void tick(Instant now) {
        try {
            load();
            Set<String> stations = subscribed.get();
            for (Group g : Group.values()) {
                if (stations != null && stations.stream().noneMatch(id -> Group.of(id) == g)) continue;
                Instant last = askedAt.get(g);
                if (last != null && Duration.between(last, now).compareTo(g.interval(now)) < 0) continue;
                askedAt.put(g, now);
                poll(g, stations, now.toEpochMilli());
            }
            tell(stations, now.toEpochMilli());
        } catch (Exception | StackOverflowError e) {
            log.error("❌ [NOTICES] {}", e.getMessage(), e);
        }
    }

    private void poll(Group g, Set<String> stations, long now) {
        List<TflNotice> notices;
        try {
            List<Map<String, Object>> raw = tflApi.getStopNotices(g == Group.BUS ? "bus" : railModes);
            if (raw == null) throw new IllegalStateException("no answer");
            notices = new ArrayList<>(raw.size());
            for (Map<String, Object> n : raw) {
                TflNotice read = notice(n);
                if (StationStatusRules.usable(read)) notices.add(read);
            }
        } catch (Exception e) {
            log.warn("⚠️ [NOTICES] {} not read ({}), keeping what we had", g.key(), e.getMessage());
            return;
        }
        State before = states.getOrDefault(g, State.NONE);
        if (before.notices() >= IMPLAUSIBLE_FROM && notices.size() < before.notices() / 3) {
            log.warn("⚠️ [NOTICES] {} gave {} readable, after {}: keeping what we had", g.key(), notices.size(), before.notices());
            return;
        }
        State after = after(before, notices, now, g.staleMs);
        states.put(g, after);
        db.saveNoticeState(g.key(), write(after));
        announce(before, after);
        // Phones first: they must not wait for the backend.
        tell(stations, now);
        publish(g, after, now);
    }

    /** One notice that is not shaped like one must not lose the rest. */
    private TflNotice notice(Map<String, Object> n) {
        try {
            return json.convertValue(n, TflNotice.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** What we hold after one more good answer. */
    static State after(State before, List<TflNotice> notices, long now, long staleMs) {
        // Two sightings must be in a row: after a long silence the first one starts again.
        boolean broken = now - before.lastOk() > staleMs;
        Map<String, StationStatus> previous = broken ? Map.of() : before.seen();
        Map<String, StationStatus> seen = StationStatusRules.statuses(notices, now);
        Map<String, StationStatus> held = new LinkedHashMap<>();
        seen.forEach((id, status) -> {
            StationStatus was = before.held().get(id);
            if (status.sameState(previous.get(id))) held.put(id, status);
                // One state stays until the next is confirmed, or the board would be normal in between.
                // Not for doubt: that clears at once.
            else if (was != null && !previous.isEmpty() && !was.isUnclear() && !status.isUnclear()) held.put(id, was);
        });
        return new State(now, broken ? 1 : before.runs() + 1, notices.size(), seen, held);
    }

    private static void announce(State before, State after) {
        after.held().forEach((id, s) -> {
            if (!s.sameState(before.held().get(id))) {
                log.info("🚧 [NOTICES] {} {} {}{}", s.kind(), id, s.name(), s.why() == null ? "" : " (" + s.why() + ")");
            }
        });
        before.held().forEach((id, s) -> {
            if (!after.held().containsKey(id)) log.info("🚧 [NOTICES] cleared {} {}", id, s.name());
        });
    }

    /** Whether what we hold can be relied on: nothing said of a stop then means it is open. */
    private boolean knows(Group g, long now) {
        State s = states.getOrDefault(g, State.NONE);
        return s.runs() >= 2 && now - s.lastOk() <= g.staleMs;
    }

    private List<StationNotice> shown(Group g, long now) {
        State s = states.getOrDefault(g, State.NONE);
        if (now - s.lastOk() > g.staleMs) return List.of();
        List<StationNotice> out = new ArrayList<>();
        for (StationStatus status : s.held().values()) {
            if (status.isUnclear() || status.to() <= now) continue;
            StationNotice n = StationNotice.of(status, now, status.directions().isEmpty() ? null : db.countLinesAt(status.id()));
            if (n != null) out.add(n);
        }
        return out;
    }

    /**
     * Bring phones up to date: new and changed notices, hourly repeats, and "none" for
     * what has gone. Worked out from what they were last told, so a refused message is
     * simply sent again.
     */
    private void tell(Set<String> stations, long now) {
        Map<String, Told> next = new HashMap<>(told);
        for (String id : fcm.failedStationStatuses()) {
            Told t = next.get(id);
            // Told nothing after all. A refused "none" is put back so that it is sent again.
            next.put(id, new Told(null, t == null ? now : t.at()));
        }
        Map<String, StationNotice> want = new LinkedHashMap<>();
        for (Group g : Group.values()) {
            for (StationNotice n : shown(g, now)) {
                if (stations == null || stations.contains(n.id())) want.put(n.id(), n);
            }
        }
        want.forEach((id, n) -> {
            Told was = next.get(id);
            boolean same = was != null && was.notice() != null && !n.isNewsAfter(was.notice(), now);
            if (same && now - was.at() < StationNotice.RESEND_MS) return;
            if (send(id, n.kind(), n)) next.put(id, new Told(n, now));
        });
        for (String id : new TreeSet<>(next.keySet())) {
            if (want.containsKey(id)) continue;
            // Not knowing is not "reopened": a restart before the first good answers must not clear it.
            if (!knows(Group.of(id), now) && now - next.get(id).at() < StationNotice.EXPIRE_MS) continue;
            if (send(id, "none", Map.of("id", id, "kind", "none", "at", now))) next.remove(id);
        }
        if (!next.equals(told)) {
            told = next;
            db.saveNoticeState(TOLD, write(told));
        }
    }

    private boolean send(String id, String kind, Object message) {
        boolean queued = fcm.publishStationStatus(id, write(message));
        if (queued) log.info("📤 [NOTICES] {} {}", kind, id);
        return queued;
    }

    private void publish(Group g, State s, long now) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group", g.key());
        body.put("at", s.lastOk());
        body.put("known", knows(g, now));
        body.put("notices", shown(g, now));
        // Everything, unclear included: the backend's log is where we learn what we misread.
        body.put("statuses", s.held().values());
        backend.publishStationStatuses(body);
    }

    // ── Over a restart ───────────────────────────────────────────────────────

    private void load() {
        if (loaded) return;
        for (Group g : Group.values()) {
            State s = read(db.getNoticeState(g.key()), new TypeReference<State>() {
            });
            if (s != null && s.seen() != null && s.held() != null) states.put(g, s);
        }
        Map<String, Told> t = read(db.getNoticeState(TOLD), new TypeReference<Map<String, Told>>() {
        });
        if (t != null) told = new HashMap<>(t);
        loaded = true;
    }

    private <T> T read(String s, TypeReference<T> type) {
        try {
            return s == null ? null : json.readValue(s, type);
        } catch (Exception e) {
            log.warn("⚠️ [NOTICES] saved state not readable, starting afresh: {}", e.getMessage());
            return null;
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
