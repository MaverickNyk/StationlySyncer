package com.stationly.backend.notices;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A station's status as a board shows it: what phones are pushed and the backend
 * serves. The words are here, so they change without an app release; the app
 * decides only where they go. Client half: StationlyUI core `StationNotice.kt`.
 *
 * @param kind      closed, part_closed, exit_only or closing; none in a push clears
 * @param title     replaces the departures when this hides them (closing: from {@code from} on)
 * @param strip     the status strip, in place of the line's: "strip : reason"
 * @param certain   part_closed by direction: whether that is certainly every train that way. Not at a
 *                  station of several lines (Earl's Court), where TfL does not say whose platform it is:
 *                  it is then said and nothing is hidden
 * @param expiresAt a phone that hears no more drops it then: a missed "reopened" cannot stick
 * @param at        when this was said. Pushes can arrive out of order; a phone keeps the newest
 */
public record StationNotice(String id, String kind, String title, List<String> details, String strip, String reason,
                            List<String> directions, List<String> lines, boolean certain, long from, long to,
                            long expiresAt, long at) {

    /** Re-sent to phones this often while it lasts, and dropped by them after EXPIRE_MS without. */
    public static final long RESEND_MS = 60 * 60_000L;
    public static final long EXPIRE_MS = 2 * RESEND_MS + 15 * 60_000L;
    /** What fits one board row on a 4x2 widget. */
    private static final int ROW = 34;

    private record Reason(Pattern in, String row) {
    }

    private static Pattern re(String p) {
        return Pattern.compile(p, Pattern.CASE_INSENSITIVE);
    }

    private static Reason reason(String p, String row) {
        return new Reason(re(p), row);
    }

    /** Reasons we can say shortly. Anything else is left to the strip. */
    private static final List<Reason> REASONS = List.of(
            reason("power (failure|supply|cut)", "Due to a power failure"),
            reason("fire (alert|alarm)", "Due to a fire alert"),
            reason("security (alert|incident)", "Due to a security alert"),
            reason("(customer|passenger) (incident|ill)|casualty", "Due to a customer incident"),
            reason("unavailability of (station )?staff|staff shortage|shortage of staff", "Due to staff availability"),
            reason("industrial action|strike", "Due to strike action"),
            reason("flood", "Due to flooding"),
            reason("overcrowd|crowd", "To prevent overcrowding"),
            reason("signal", "Due to a signal failure"),
            reason("communication", "Due to a communications fault"),
            reason("(faulty|broken) (lift|escalator)s?|(lift|escalator) fault", "Due to a lift or escalator fault"),
            reason("escalators? (are |is )?(being )?replace|replace(ment of)? (the )?escalators?|escalator replacement", "Escalator replacement"),
            reason("lift replacement|replace (the )?lifts?", "Lift replacement"),
            reason("rebuild", "Rebuilding works"),
            reason("platform works", "Planned platform works"),
            reason("engineering|planned (maintenance|works?)|improvement works?|\\bworks\\b", "Planned works"));

    /** "until Monday 14 December", as TfL wrote it. */
    private static final Pattern UNTIL = re("\\buntil ((?:early |mid[- ]|late )?(?:(?:Mon|Tues|Wednes|Thurs|Fri|Satur|Sun)day )?(?:\\d{1,2} )?"
            + "(?:January|February|March|April|May|June|July|August|September|October|November|December)(?: \\d{4})?)\\b");
    private static final Pattern TEMPORARY_STOP = re("temporary (bus )?stop");
    private static final Pattern NEXT_OR_PREVIOUS = re("next.{0,30}previous|previous.{0,30}next");
    private static final Pattern NEXT_STOP = re("next (available )?stop");
    private static final Pattern USE_BUSES = re("use (local )?(london )?bus");
    private static final Pattern LAST_TRAINS = re("last trains?");
    private static final Pattern USE_LINE = Pattern.compile("please use ((?:[A-Z][\\w&]+ ){1,3})line trains");
    private static final Pattern STILL_STOP = re("\\b((?:north|south|east|west)bound) trains (?:are not affected|(?:will )?(?:stop|serve))");
    /** "Arsenal: Exit Only - ", which the board already says. Never a clock: "until 17:00". */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z&'’.,() -]{2,60}: ?");
    private static final Pattern STATE = re("^(part[ -]closed|closed|exit[ -]only) ?[-–] ?");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!]) ");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/London"));

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static List<String> reasonRow(String text) {
        return REASONS.stream().filter(r -> r.in().matcher(text).find()).findFirst().map(r -> List.of(r.row())).orElse(List.of());
    }

    private static List<String> untilRow(String text) {
        Matcher m = UNTIL.matcher(text);
        return m.find() && m.group(1).length() + 6 <= ROW ? List.of("Until " + m.group(1)) : List.of();
    }

    private static List<String> busRow(String text) {
        if (TEMPORARY_STOP.matcher(text).find()) return List.of("Use the temporary stop nearby");
        if (NEXT_OR_PREVIOUS.matcher(text).find()) return List.of("Use the next or previous stop");
        return NEXT_STOP.matcher(text).find() ? List.of("Use the next stop") : List.of();
    }

    @SafeVarargs
    private static List<String> rows(List<String>... parts) {
        List<String> out = new ArrayList<>();
        for (List<String> p : parts) p.stream().filter(r -> r.length() <= ROW && out.size() < 3).forEach(out::add);
        return out;
    }

    private static String stripped(StationStatus s) {
        String r = "bus".equals(s.mode()) ? s.text()
                : STATE.matcher(NAME.matcher(s.text()).replaceFirst("")).replaceFirst("");
        r = cap(r.strip());
        return r.isEmpty() ? s.text() : r;
    }

    /**
     * Null for what is not shown.
     *
     * @param linesHere how many lines call at the stop; null when not known
     */
    public static StationNotice of(StationStatus s, long at, Integer linesHere) {
        boolean bus = "bus".equals(s.mode());
        String title = bus ? "Bus stop closed" : "Station closed";
        String strip = bus ? "Stop closed" : "Station closed";
        List<String> buses = USE_BUSES.matcher(s.text()).find() ? List.of("Please use local buses") : List.of();
        switch (s.kind()) {
            case StationStatus.CLOSED: {
                List<String> why = rows(reasonRow(s.text()), bus ? busRow(s.text()) : untilRow(s.text()));
                List<String> details = rows(why, bus ? List.of() : buses);
                if (details.isEmpty() && !bus) details = List.of("We'll update when it reopens");
                return make(s, at, linesHere, title, details, strip, stripped(s));
            }
            case StationStatus.PART_CLOSED: {
                if (!s.lines().isEmpty()) {
                    String said = s.lines().get(0) + " closed here";
                    Matcher other = USE_LINE.matcher(s.text().replaceFirst("Please", "please"));
                    return make(s, at, linesHere, said, rows(other.find() ? List.of("Please use the " + other.group(1).strip()) : reasonRow(s.text())), said, stripped(s));
                }
                if (s.directions().isEmpty()) return null;
                String direction = cap(s.directions().get(0));
                Matcher other = STILL_STOP.matcher(s.text());
                String stops = other.find() ? cap(other.group(1).toLowerCase(Locale.ROOT)) : direction;
                return make(s, at, linesHere, direction + " platform closed",
                        rows(reasonRow(s.text()), stops.equals(direction) ? List.of() : List.of(stops + " trains still stop")),
                        direction + " closed", stripped(s));
            }
            case StationStatus.EXIT_ONLY:
                return make(s, at, linesHere, "", List.of(), "Exit only", stripped(s));
            case StationStatus.CLOSING: {
                String last = null;
                for (String x : SENTENCE.split(s.text())) {
                    if (last == null && LAST_TRAINS.matcher(x).find()) last = x;
                }
                return make(s, at, linesHere, title, rows(List.of("Closed early tonight"), buses),
                        "Closes " + CLOCK.format(Instant.ofEpochMilli(s.from())), last == null ? stripped(s) : last);
            }
            default:
                return null;
        }
    }

    private static StationNotice make(StationStatus s, long at, Integer linesHere, String title, List<String> details, String strip, String reason) {
        return new StationNotice(s.id(), s.kind(), title, details, strip, reason, s.directions(), s.lines(),
                s.directions().isEmpty() || (linesHere != null && linesHere == 1), s.from(), s.to(), Math.min(s.to(), at + EXPIRE_MS), at);
    }

    /**
     * Whether a phone told [was] needs telling this. Not for when it was said, nor when
     * something under way began, nor its end: TfL rolls a live notice's end forward at
     * every poll. Only when the end a phone has is near and no longer the end.
     */
    boolean isNewsAfter(StationNotice was, long now) {
        return !sameExceptTimes(was) || (from > now && (from != was.from || to != was.to)) || (was.to - now < 10 * 60_000L && to > was.to);
    }

    private boolean sameExceptTimes(StationNotice o) {
        return new StationNotice(id, kind, title, details, strip, reason, directions, lines, certain, 0, 0, 0, 0)
                .equals(new StationNotice(o.id, o.kind, o.title, o.details, o.strip, o.reason, o.directions, o.lines, o.certain, 0, 0, 0, 0));
    }
}
