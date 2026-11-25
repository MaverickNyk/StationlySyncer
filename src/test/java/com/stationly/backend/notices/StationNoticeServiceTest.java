package com.stationly.backend.notices;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stationly.backend.client.TflApi;
import com.stationly.backend.service.LiveStreamPublisher;
import com.stationly.backend.service.LocalDatabaseService;
import com.stationly.backend.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The whole service, with TfL, FCM, the backend and the database played by hand. */
class StationNoticeServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String ARSENAL = "940GZZLUASL";

    /** What TfL answers: modes to notices. Throwing is TfL failing. */
    private Function<String, List<Map<String, Object>>> tfl = m -> List.of();
    private final List<String> asked = new ArrayList<>();
    private final List<String> sent = new ArrayList<>();
    private final List<JsonNode> messages = new ArrayList<>();
    private final Set<String> refused = new HashSet<>();
    private boolean fcmOn = true;
    private final List<Map<String, Object>> posts = new ArrayList<>();
    private final Map<String, String> database = new HashMap<>();
    private Integer linesHere = 1;
    private Set<String> stations = new HashSet<>(Set.of(ARSENAL));
    private StationNoticeService service;
    private Instant now = Instant.parse("2026-11-25T12:00:00Z");

    /** Only what the service uses is played; anything else is a surprise. */
    @SuppressWarnings("unchecked")
    private static <T> T played(Class<T> type, Map<String, Function<Object[], Object>> methods) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (!methods.containsKey(m.getName())) throw new UnsupportedOperationException(m.getName());
            return methods.get(m.getName()).apply(a);
        });
    }

    @BeforeEach
    void start() {
        TflApi api = played(TflApi.class, Map.of(
                "getStopNotices", a -> { asked.add((String) a[0]); return tfl.apply((String) a[0]); }));
        NotificationService fcm = played(NotificationService.class, Map.of(
                "publishStationStatus", a -> {
                    if (!fcmOn) return false;
                    try {
                        JsonNode n = JSON.readTree((String) a[1]);
                        messages.add(n);
                        sent.add(a[0] + " " + n.get("kind").asText());
                    } catch (Exception e) { throw new IllegalStateException(e); }
                    return true;
                },
                "failedStationStatuses", a -> { Set<String> out = new HashSet<>(refused); refused.clear(); return out; }));
        LiveStreamPublisher backend = new LiveStreamPublisher(null) {
            @Override
            public void publishStationStatuses(Map<String, Object> body) {
                posts.add(body);
            }
        };
        LocalDatabaseService db = played(LocalDatabaseService.class, Map.of(
                "getNoticeState", a -> database.get((String) a[0]),
                "saveNoticeState", a -> database.put((String) a[0], (String) a[1]) == null ? null : null,
                "countLinesAt", a -> linesHere));
        service = new StationNoticeService(api, fcm, backend, db, () -> stations, JSON);
    }

    private static Map<String, Object> notice(String description) {
        Map<String, Object> n = new HashMap<>(Map.of("atcoCode", ARSENAL, "mode", "tube", "type", "Closure", "appearance", "RealTime",
                "fromDate", "2026-11-25T08:00:00Z", "toDate", "2026-11-27T01:29:00Z", "description", description));
        n.put("commonName", "Arsenal Underground Station");
        n.put("$type", "Tfl.Api");
        return n;
    }

    private static List<Map<String, Object>> many(Object... extra) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < 20; i++) out.add(new HashMap<>(Map.of("atcoCode", "x" + i, "type", "Information", "description", "lift", "fromDate", "2026-01-01T00:00:00Z", "toDate", "2027-01-01T00:00:00Z")));
        for (Object e : extra) out.add(notice((String) e));
        return out;
    }

    private static final String CLOSED = "Arsenal: Closed - This station is closed while we respond to a fire alert.";
    private static final String EXIT = "Arsenal: Exit Only - This station is exit only due to faulty lifts.";

    /** Polls a minute apart, one per argument. */
    @SafeVarargs
    private void polls(List<Map<String, Object>>... answers) {
        for (List<Map<String, Object>> a : answers) {
            tfl = m -> a;
            service.tick(now);
            now = now.plusSeconds(60);
        }
    }

    private void fails() {
        tfl = m -> { throw new RuntimeException("503"); };
        service.tick(now);
        now = now.plusSeconds(60);
    }

    private Map<String, Object> lastPost() {
        return posts.get(posts.size() - 1);
    }

    private static List<String> list(String... s) {
        return List.of(s);
    }

    private static String k(String kind) {
        return ARSENAL + " " + kind;
    }

    @Test
    void twoPollsToCloseOneToReopen() {
        polls(many(CLOSED));
        assertEquals(list(), sent);
        polls(many(CLOSED), many(CLOSED), many());
        assertEquals(list(k("closed"), k("none")), sent);
    }

    @Test
    void thePushIsWhatPhonesAlreadyRead() {
        polls(many(CLOSED), many(CLOSED));
        JsonNode n = messages.get(0);
        assertEquals("Station closed", n.get("title").asText());
        assertEquals("Due to a fire alert", n.get("details").get(0).asText());
        assertEquals("This station is closed while we respond to a fire alert.", n.get("reason").asText());
        assertEquals(n.get("at").asLong() + StationNotice.EXPIRE_MS, n.get("expiresAt").asLong());
        assertEquals(Set.of("id", "kind", "title", "details", "strip", "reason", "directions", "lines", "certain", "from", "to", "expiresAt", "at"),
                new HashSet<>((Collection<String>) JSON.convertValue(n, Map.class).keySet()));
    }

    @Test
    void aFailedEmptyOrOddAnswerIsNotAllReopened() {
        polls(many(CLOSED), many(CLOSED));
        fails();
        polls((List<Map<String, Object>>) null, List.of());
        fails();
        assertEquals(list(k("closed")), sent);
        Map<String, Object> odd = new HashMap<>();
        odd.put("atcoCode", List.of("not", "a", "string"));
        List<Map<String, Object>> withJunk = many(CLOSED);
        withJunk.add(odd);
        withJunk.add(new HashMap<>());
        polls(withJunk);
        assertEquals(list(k("closed")), sent, "junk among good ones is skipped");
    }

    @Test
    void afterALongSilenceItStartsAgain() {
        polls(many(CLOSED), many(CLOSED));
        now = now.plusSeconds(11 * 60);
        polls(many());
        assertEquals(list(k("closed")), sent, "one answer after a silence is not enough to say open");
        polls(many());
        assertEquals(list(k("closed"), k("none")), sent);
    }

    @Test
    void oneStateFollowsAnotherWithNoNoneBetweenAndEachIsNewer() {
        polls(many(CLOSED), many(CLOSED), many(EXIT), many(EXIT), many(CLOSED), many(CLOSED), many());
        assertEquals(list(k("closed"), k("exit_only"), k("closed"), k("none")), sent);
        long[] at = messages.stream().mapToLong(m -> m.get("at").asLong()).toArray();
        for (int i = 1; i < at.length; i++) assertTrue(at[i] > at[i - 1]);
    }

    @Test
    void doubtClearsAtOnceAndIsNeverShown() {
        String doubt = "Arsenal: Closed - the eastbound lift.";
        polls(many(CLOSED), many(CLOSED), many(doubt), many(doubt));
        assertEquals(list(k("closed"), k("none")), sent);
        assertEquals(List.of(), lastPost().get("notices"));
        assertEquals("unclear", ((Collection<StationStatus>) lastPost().get("statuses")).iterator().next().kind());
    }

    @Test
    void repeatedHourlyWhileItLasts() {
        for (int i = 0; i < 64; i++) polls(many(CLOSED));
        assertEquals(list(k("closed"), k("closed")), sent);
    }

    @Test
    void aRefusedPushIsSentAgainBothWays() {
        polls(many(CLOSED), many(CLOSED));
        refused.add(ARSENAL);
        polls(many(CLOSED));
        assertEquals(list(k("closed"), k("closed")), sent);
        polls(many());
        refused.add(ARSENAL);
        polls(many(), many());
        assertEquals(list(k("closed"), k("closed"), k("none"), k("none")), sent);
    }

    @Test
    void withFcmOffNothingCountsAsTold() {
        fcmOn = false;
        polls(many(CLOSED), many(CLOSED));
        fcmOn = true;
        polls(many(CLOSED));
        assertEquals(list(k("closed")), sent);
    }

    @Test
    void aRestartRepeatsNothingReopensNothingAndStillClearsWhatEnded() {
        polls(many(CLOSED), many(CLOSED));
        start();
        polls(many(CLOSED));
        assertEquals(list(k("closed")), sent, "remembered");
        start();
        fails();
        assertEquals(list(k("closed")), sent);
        now = now.plusSeconds(15 * 60);
        start();
        polls(many());
        assertEquals(list(k("closed")), sent, "one answer is not enough");
        polls(many());
        assertEquals(list(k("closed"), k("none")), sent);
    }

    @Test
    void aBrokenSavedStateStartsAfresh() {
        database.putAll(Map.of("rail", "{", "bus", "null", "told", "[]"));
        polls(many(CLOSED), many(CLOSED));
        assertEquals(list(k("closed")), sent);
    }

    @Test
    void onlyStopsSomeoneHas() {
        stations = new HashSet<>(Set.of("940GZZLUOXC"));
        polls(many(CLOSED), many(CLOSED));
        assertEquals(list(), sent);
        assertEquals(1, ((List<?>) lastPost().get("notices")).size(), "but the backend can answer for it");
        stations.add(ARSENAL);
        polls(many(CLOSED));
        assertEquals(list(k("closed")), sent, "added while closed");
    }

    @Test
    void aClosedDirectionIsCertainOnlyWhereOneLineCalls() {
        String part = "Arsenal: Part Closed - the westbound platform is closed for planned platform works.";
        for (Object[] c : new Object[][]{{1, true}, {2, false}, {null, false}}) {
            start();
            messages.clear();
            database.clear();
            linesHere = (Integer) c[0];
            polls(many(part), many(part));
            assertEquals(c[1], messages.get(0).get("certain").asBoolean(), String.valueOf(c[0]));
            now = now.plusSeconds(3600);
        }
    }

    @Test
    void theBackendGetsEverythingEveryPoll() {
        polls(many(CLOSED));
        assertEquals(false, lastPost().get("known"), "one answer is not enough to say the rest are open");
        polls(many(CLOSED));
        assertEquals("rail", lastPost().get("group"));
        assertEquals(true, lastPost().get("known"));
        assertEquals(now.minusSeconds(60).toEpochMilli(), lastPost().get("at"));
        polls(many(CLOSED));
        assertEquals(3, posts.size());
        fails();
        assertEquals(3, posts.size(), "nothing new, nothing posted");
    }

    @Test
    void railEveryMinuteAndBusesEveryFive() {
        stations.add("490008296M");
        for (int s = 0; s <= 300; s += 10) service.tick(now.plusSeconds(s));
        assertEquals(6, asked.stream().filter(m -> m.startsWith("tube,")).count());
        assertEquals(2, asked.stream().filter("bus"::equals).count());
    }

    @Test
    void slowerOvernightInLondonTimeWhateverTheSeason() {
        Instant winter = Instant.parse("2026-11-25T02:00:00Z");
        for (int s = 0; s <= 180; s += 10) service.tick(winter.plusSeconds(s));
        assertEquals(2, asked.size());
        asked.clear();
        // 04:00 UTC in July is 05:00 in London: day.
        Instant summer = Instant.parse("2027-07-01T04:00:00Z");
        for (int s = 0; s <= 180; s += 10) service.tick(summer.plusSeconds(s));
        assertEquals(4, asked.size());
    }

    @Test
    void asksNothingWhileNobodyHasSuchAStopAndEverythingWhenThePollingStrategyIsAll() {
        stations.clear();
        service.tick(now);
        assertEquals(list(), asked);
        stations = null;
        service.tick(now);
        assertEquals(2, asked.size());
        now = now.plusSeconds(60);
        polls(many(CLOSED), many(CLOSED));
        assertTrue(sent.contains(k("closed")));
    }

    @Test
    void nothingThrownEverEndsTheSchedule() {
        service = new StationNoticeService(null, null, null, null, () -> { throw new IllegalStateException("firestore"); }, JSON);
        assertDoesNotThrow(() -> service.tick(now));
    }

    @Test
    void aLiveNoticeStampedAnHourAheadInSummerIsPushedOnce() {
        now = Instant.parse("2026-07-25T12:00:00Z");
        List<Map<String, Object>> ahead = many(CLOSED);
        ahead.get(20).put("fromDate", "2026-07-25T13:00:00Z");
        for (int i = 0; i < 30; i++) polls(ahead);
        assertEquals(list(k("closed")), sent);
    }

    @Test
    void aLiveNoticeWhoseEndRollsForwardIsNotNews() {
        for (int i = 0; i < 59; i++) {
            List<Map<String, Object>> rolling = many(CLOSED);
            rolling.get(20).put("toDate", now.plusSeconds(3 * 3600).toString());
            polls(rolling);
        }
        assertEquals(list(k("closed")), sent);
    }

    @Test
    void aPhoneIsToldBeforeTheEndItHasComes() {
        for (int i = 0; i < 25; i++) {
            List<Map<String, Object>> rolling = many(CLOSED);
            rolling.get(20).put("toDate", now.plusSeconds(20 * 60).toString());
            polls(rolling);
        }
        assertEquals(list(k("closed"), k("closed"), k("closed")), sent, "every ten minutes or so, not every one");
    }

    @Test
    void noticesThatSuddenlyCannotBeReadAreNotAllReopened() {
        polls(many(CLOSED), many(CLOSED));
        List<Map<String, Object>> odd = many(CLOSED);
        odd.forEach(n -> n.put("fromDate", "25/11/2026 08:00"));
        polls(odd, odd, many(CLOSED).subList(15, 21));
        assertEquals(list(k("closed")), sent);
    }

    @Test
    void aBusStopOutsideLondonIsABusStop() {
        stations = new HashSet<>(Set.of("210021000010"));
        tfl = m -> List.of();
        service.tick(now);
        assertEquals(list("bus"), asked);
    }

    @Test
    void aNoticeThatNamesAPlatformIsNotTheStation() {
        polls(many("Arsenal: Closed - platform 2 access."), many("Arsenal: Closed - platform 2 access."));
        assertEquals(list(), sent);
    }
}
