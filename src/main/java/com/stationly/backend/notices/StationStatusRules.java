package com.stationly.backend.notices;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What TfL's stop notices say about a station. Pure: no clock, no network.
 *
 * <h2>Plain forms only</h2>
 * A board that wrongly says "Station closed" is worse than one that says nothing, and
 * TfL's type is no guide: "Closure" also labels exit-only stations, one platform, one
 * line and opening hours, and "Part Closure" open stations during a signal failure. So
 * something is claimed only when the WHOLE notice, its travel advice aside, is one of a
 * few plain forms ("This station is closed while ..."), in a window covering now. A date
 * or a time in it must be the notice's own.
 *
 * Looking for what could be wrong with a notice was tried first and cannot be finished:
 * there is always another wording. Everything that is not a plain form is
 * {@code unclear}: logged, never shown. Widen a form only for a real notice, in
 * src/test/resources/notices.
 */
public final class StationStatusRules {

    private StationStatusRules() {
    }

    private static final long MIN = 60_000;
    private static final long HOUR = 60 * MIN;
    /** A closure this close is worth saying on the board now. */
    private static final long CLOSING_SOON_MS = 12 * HOUR;
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final int LONGEST = 2000;

    private static Pattern re(String p) {
        return Pattern.compile(p, Pattern.CASE_INSENSITIVE);
    }

    private static final Pattern SPACE = Pattern.compile("[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!]) ");
    private static final String DAY = "(?:mon|tues|wednes|thurs|fri|satur|sun)day";
    private static final String MONTH = "(?:january|february|march|april|may|june|july|august|september|october|november|december)";
    private static final String TIME = "(?:[01]\\d|2[0-3])[:.]?[0-5]\\d";
    /** "Monday 26 June", "Monday 26 June 2023". */
    private static final String DATE = "(?:" + DAY + " )?\\d{1,2} " + MONTH + "(?: \\d{4})?";
    private static final String FROM = "from (?<from>" + DATE + ")";
    /** "until further notice", "until Monday 14 December", "until mid December", "until late 2024", "until 07:00 Monday morning". */
    private static final String UNTIL = "until (?<until>further notice|" + DATE + "|(?:early |mid[- ]?|late )?" + MONTH + "(?: \\d{4})?|(?:early |mid[- ]?|late )?\\d{4}|" + TIME + "(?: (?:on )?" + DAY + ")?(?: morning)?)";
    /** What closes stations. A reason not here makes a notice unclear: add one for a real notice. */
    private static final String THING = "(?:(?:a|an|the) )?(?:(?:temporary )?unavailability of (?:station )?staff|(?:station )?staff shortages?|shortage of (?:station )?staff|(?:fire|security) (?:alert|alarm|incident)"
            + "|power (?:failure|supply (?:failure|problem|fault)|cut)|signal failure|communications?(?: equipment)?(?: (?:fault|failure|problem))?|(?:customer|passenger|police) incident|police investigation"
            + "|casualty(?: on the track)?|flood(?:ing)?|burst (?:water )?(?:main|pipe)|gas leak|(?:faulty|broken) (?:lifts?|escalators?)(?: and (?:lifts?|escalators?))?|(?:lift|escalator) (?:fault|failure)"
            + "|overcrowding(?: due to faulty (?:lifts?|escalators?))?|industrial action|strike action"
            + "|(?:(?:major|planned|essential|urgent|emergency|vital) )?(?:(?:engineering|rebuilding|structural|maintenance|improvement|platform|escalator|lift|track) )?(?:works?|replacement))";
    private static final String REASON = "(?:(?:due to|because of|following|for|to prevent) " + THING + "|while we (?:respond to|fix|repair|investigate|deal with) " + THING
            + "|while (?:the )?(?:lifts|escalators) are (?:being )?(?:replaced|repaired)|to enable vital work to be carried out to replace the (?:lifts|escalators))";
    private static final String WHY = "(?:,? " + REASON + ")?";
    private static final String STATION = "(?:this|the) station";

    private static Pattern whole(String p) {
        return re("(?:" + p + ")\\.?");
    }

    /** The whole station, in one sentence. "Will be" only with a date: alone it may be any day. */
    private static final List<Pattern> CLOSED = List.of(
            whole("closed(?: " + UNTIL + ")?" + WHY),
            whole("(?:" + STATION + " (?:is|remains) )closed(?: " + UNTIL + ")?" + WHY),
            whole(STATION + " (?:will be|will remain) closed " + UNTIL + WHY),
            whole(FROM + "(?: " + UNTIL + ")?, " + STATION + " (?:is|will be) closed" + WHY),
            whole(STATION + " will close " + FROM + "(?: " + UNTIL + "| for up to (?:a|one|two|three) (?:year|month|week)s?)?" + WHY),
            whole("(?<bare>" + REASON + ")"));
    private static final String DIRECTION = "(?<direction>(?:north|south|east|west)bound)";
    private static final Pattern ONE_PLATFORM = whole("(?:" + FROM + "(?: " + UNTIL + ")?,? )?the " + DIRECTION + " platform(?: at (?<at>[a-z'’. ]+?))? (?:(?:is|remains) closed|will be closed)" + WHY);
    private static final Pattern ONE_PLATFORM_UNTIL = whole(UNTIL + ",? the " + DIRECTION + " platform(?: at (?<at>[a-z'’. ]+?))? (?:is|will be) closed" + WHY);
    /** Case matters: a line is a proper name. */
    private static final Pattern ONE_LINE = Pattern.compile("(?i:(?:(?:from )?(?<from>" + DATE + ") )?" + UNTIL + ", )?closed to (?<line>(?:[A-Z][a-z]+|&)(?: (?:[A-Z][a-z]+|&)){0,3}) line trains\\.?");
    private static final Pattern EXIT_ONLY = whole("(?:" + STATION + " is (?:exit only|open for exit(?: and interchange)? only)(?: and (?<stopping>(?:north|south|east|west)bound) trains are not stopping at this station)?(?:,? this is)?" + WHY + ")?");
    /** "open between 0700 and 1900": TfL's window is then the closed part. */
    private static final Pattern HOURS = whole(STATION + " (?:is|will be) (?:only )?open (?:only )?between (\\d{2})[:.]?(\\d{2}) and (\\d{2})[:.]?(\\d{2})");
    private static final Pattern CLOSES_AT = whole("(?:" + DAY + " \\d{1,2}(?: " + MONTH + ")?(?:(?:, | and )" + DAY + " \\d{1,2}(?: " + MONTH + ")?)*, )?" + STATION
            + " will close at (?:about |approximately )?(\\d{2})[:.]?(\\d{2})(?: approximately)?");

    /** A label before the words: "Closed - ". */
    private static final Pattern LABEL = re("^(?<label>closed|part[ -]closed|exit[ -]only)(?: ?[-–] ?|$)");
    private static final Pattern PREFIX = re("^([^:]{2,60}):(?!\\d) ?");
    /** Whole sentences that are travel advice, and say nothing of the station. */
    private static final Pattern ADVICE = whole("please use (?:local london bus(?:es)? services|(?:local )?(?:london )?buses(?: services)?|[a-z&' ]+ line (?:trains|connections)(?: instead)?)(?:, [^.]*)?"
            + "|call(?: us)? on 0343 222 1234(?: if you need (?:more information|(?:further )?help(?: planning your journey)?))?"
            + "|(?:london underground )?tickets are (?:being )?accepted on (?:london |local )?(?:buses|bus routes|underground)"
            + "|(?:a very limited|a limited|a|connecting)? ?replacement bus(?:es)? (?:service )?[a-z0-9 ]*operates?\\b[^.]*"
            + "|last trains? will be at [^.]*"
            + "|(?:(?:the )?national rail (?:\\(thameslink\\) )?|[a-z' ]+ thameslink )station (?:remains|will remain) open(?: for customers(?: to use[^.]*)?)?");
    /** Anywhere but in that last kind of advice, hours, or "open for exit". */
    private static final Pattern OPEN = re("\\bre-?open\\w*|\\bopen\\b|\\bopened\\b|\\bresum\\w+|\\bcleared\\b|\\bnormal\\w*");
    private static final Pattern OTHER_DIRECTION = re("(?<direction>(?:north|south|east|west)bound) trains (?:are not affected|will (?:stop|serve))(?: and will serve the station)?(?: as usual)?(?: during these works)?\\.?");

    private static final String BUS_DATE = "(?:\\d{1,2}(?:st|nd|rd|th)? (?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*(?: \\d{2,4})?|\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?)";
    private static final String BUS_DAY = "(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*";
    private static final String DATED = "(?:" + TIME + " (?:on )?(?:" + BUS_DAY + " )?" + BUS_DATE + "|(?:" + BUS_DAY + " )?" + BUS_DATE + "(?: " + TIME + ")?)";
    private static final String BUS_END = "(?:approx\\.? ?|\\(approx\\) ?)?(?:" + DATED + "|" + TIME + "(?: (?:on )?" + BUS_DAY + ")?|" + BUS_DAY + ")";
    private static final String BUS_WHY = "(?:[.,]? (?:(?:due to|to facilitate) )?(?:(?!nightly|daily|night|day|week)[a-z&]+ ){0,4}(?:works|scaffolding|a crane operation)(?: (?:on|at) (?:[a-z]+ ){1,2}(?:road|street|lane|avenue|hill|way))?)?";
    private static final String BUS_ADVICE = "(?:[., ]*(?:(?:please )?(?:use|go to) (?:the )?(?:(?:next|nest|previous|nearby|available|temporary|bus|stops?|or|the) ?)+(?: (?:located )?nearby)?(?: to (?:catch|get) your bus)?"
            + "|for further information visit:? ?tfl\\.gov\\.uk[a-z/]*|buses are on diversion tfl\\.gov\\.uk[a-z/]*|tfl\\.gov\\.uk[a-z/]*))*[., ]*";
    private static final Pattern BUS_CLOSED = re("(?:this )?(?:bus )?stop (?:is )?closed(?:(?: from (?<from>" + DATED + "))? (?:until|to) (?<until>" + BUS_END + "))?" + BUS_WHY
            + "(?: until (?<late>" + BUS_END + "))?" + BUS_ADVICE);
    private static final Pattern IN_IT = re("(?<time>\\b" + TIME + "\\b(?!/))|(?<num>\\b\\d{1,2})(?:st|nd|rd|th)?[ /](?<month>\\d{1,2}|[a-z]{3})[a-z]*(?:[ /](?<year>(?:20)?\\d{2})\\b(?![:.]?\\d))?|\\b(?<day>mon|tue|wed|thu|fri|sat|sun)[a-z]*");
    private static final Pattern WILL = re("\\bwill\\b");

    static int londonMinutes(long ms) {
        LocalTime t = Instant.ofEpochMilli(ms).atZone(LONDON).toLocalTime();
        return t.getHour() * 60 + t.getMinute();
    }

    static String tidy(String s) {
        return SPACE.matcher(s.replace("\\n", " ")).replaceAll(" ").strip();
    }

    /** Whether the Syncer can read it at all. */
    static boolean usable(TflNotice n) {
        return n != null && n.atcoCode() != null && !n.atcoCode().isEmpty() && n.description() != null && millis(n.fromDate()) != null && millis(n.toDate()) != null;
    }

    /** "ARSENAL UNDERGROUND STATION", "Arsenal Station" and "Arsenal" are one. */
    private static String plainName(String s) {
        return lower(s).replaceAll("\\([^)]*\\)|\\b(underground|rail|dlr|tube|station|stop|london)\\b|[^a-z0-9&]", "");
    }

    /**
     * The notice's sentences without its station's name and label in front, and without
     * travel advice. Empty when what is in front is not this station.
     */
    private static List<String> sentences(String text, String name) {
        String rest = text.replace("St. ", "St ");
        String label = "";
        for (int i = 0; i < 3; i++) {
            Matcher prefix = PREFIX.matcher(rest);
            if (prefix.find()) {
                if (!plainName(prefix.group(1)).equals(plainName(name))) return List.of();
                rest = rest.substring(prefix.end());
            }
            Matcher l = LABEL.matcher(rest);
            if (l.find() && label.isEmpty()) {
                label = lower(l.group("label")).replace(' ', '-');
                rest = rest.substring(l.end());
            }
        }
        List<String> out = new ArrayList<>(Arrays.stream(SENTENCE.split(rest)).filter(x -> !x.isBlank() && !ADVICE.matcher(x).matches()).toList());
        out.add(0, label);
        return out;
    }

    private static boolean near(int a, int b) {
        return Math.min(Math.floorMod(a - b, 1440), Math.floorMod(b - a, 1440)) <= 2;
    }

    private static int minutes(Matcher m, int group) {
        return Integer.parseInt(m.group(group)) * 60 + Integer.parseInt(m.group(group + 1));
    }

    /** Whether the notice's window is the closed part of the hours its sentence describes. Null: it describes none. */
    private static Boolean windowIsTheHours(String sentence, long from, long to) {
        Matcher hours = HOURS.matcher(sentence);
        Matcher closes = CLOSES_AT.matcher(sentence);
        boolean hasHours = hours.matches();
        if (!hasHours && !closes.matches()) return null;
        // One night's closing, not a week that happens to start at that hour.
        if (to - from > 14 * HOUR) return false;
        int start = londonMinutes(from);
        int end = londonMinutes(to);
        int shuts = hasHours ? minutes(hours, 3) : minutes(closes, 1);
        // TfL cuts a closure at midnight, so either half may come alone.
        boolean endsThen = end >= 23 * 60 + 58 || end <= 1 || (hasHours ? near(end, minutes(hours, 1)) : end < 5 * 60);
        return endsThen && (near(start, shuts) || (start <= 1 && shuts >= 20 * 60));
    }

    private static LocalDate day(long ms) {
        return Instant.ofEpochMilli(ms).atZone(LONDON).toLocalDate();
    }

    /** Whether a worded date is [day], or the evening before a night that ends early on it. */
    private static boolean isThatDay(String worded, LocalDate day, boolean orBefore) {
        for (LocalDate d : orBefore ? List.of(day, day.minusDays(1)) : List.of(day)) {
            String[] w = lower(worded).split(" ");
            int i = w[0].endsWith("day") ? 1 : 0;
            boolean same = Integer.parseInt(w[i]) == d.getDayOfMonth() && w[i + 1].equals(lower(d.getMonth().name()))
                    && (i == 0 || w[0].equals(lower(d.getDayOfWeek().name()))) && (w.length <= i + 2 || Integer.parseInt(w[i + 2]) == d.getYear());
            if (same) return true;
        }
        return false;
    }

    /** Whether the dates in a form are the notice's own. */
    private static boolean datesAreTheWindows(Matcher m, long from, long to) {
        String f = group(m, "from");
        if (f != null && !isThatDay(f, day(from), false)) return false;
        String until = group(m, "until");
        if (until == null || until.equalsIgnoreCase("further notice")) return true;
        String u = lower(until);
        LocalDate end = day(to);
        if (u.matches("\\d.*" + MONTH + ".*|" + DAY + " \\d.*")) return isThatDay(u, end, true);
        Matcher time = Pattern.compile("(" + TIME + ")(?: (?:on )?(" + DAY + "))?.*").matcher(u);
        if (time.matches() && !u.matches("20\\d{2}")) {
            // A time is tonight's, not every night's of a long notice.
            if (to - from > 30 * HOUR) return false;
            String t = time.group(1).replaceAll("[:.]", "");
            boolean atEnd = near(Integer.parseInt(t.substring(0, 2)) * 60 + Integer.parseInt(t.substring(2)), londonMinutes(to));
            return time.group(2) == null ? atEnd : time.group(2).equals(lower(end.getDayOfWeek().name()));
        }
        // A month or a year: the window ends in it, give or take what "early" and "late" do.
        Matcher year = Pattern.compile("\\b(20\\d{2})\\b").matcher(u);
        boolean hasYear = year.find();
        Matcher month = Pattern.compile("\\b(" + MONTH + ")\\b").matcher(u);
        LocalDate before = end.minusMonths(1);
        LocalDate after = end.plusMonths(1);
        for (LocalDate d : List.of(end, before, after)) {
            if ((!hasYear || d.getYear() == Integer.parseInt(year.group(1))) && (!month.reset().find() || month.group(1).equals(lower(d.getMonth().name())))) return true;
        }
        return false;
    }

    /** Not every form has every group. */
    private static String group(Matcher m, String name) {
        return m.pattern().pattern().contains("(?<" + name + ">") ? m.group(name) : null;
    }

    private static Matcher match(List<Pattern> forms, String text) {
        return forms.stream().map(p -> p.matcher(text)).filter(Matcher::matches).findFirst().orElse(null);
    }

    private record Read(String kind, List<String> directions, List<String> lines, String why) {
    }

    private static final String SAYS_OPEN = "says something is open";

    private static Read unclear(String why) {
        return new Read(StationStatus.UNCLEAR, List.of(), List.of(), why);
    }

    private static Read claim(String kind, String direction, String line) {
        return new Read(kind, direction == null ? List.of() : List.of(lower(direction)), line == null ? List.of() : List.of(line), null);
    }

    /** What one notice says, whenever it applies. Null: nothing to do with being open. */
    private static Read read(TflNotice n, long from, long to) {
        boolean realTime = "RealTime".equals(n.appearance());
        // Until about 2021 a closed station was "Part Closure" too; PlannedWork "Part Closure" is a line's works.
        boolean ours = "Closure".equals(n.type()) || "Exit Only".equals(n.type()) || ("Part Closure".equals(n.type()) && realTime);
        if (!ours) return null;
        String text = tidy(n.description());
        // The longest real one is under 1000. A pattern can overflow the stack on a huge one.
        if (text.length() > LONGEST) return unclear("too long");
        return "bus".equals(n.mode()) ? readBus(text, from, to) : readRail(text, n.commonName() == null ? "" : n.commonName(), from, to);
    }

    private static Read readRail(String text, String name, long from, long to) {
        List<String> said = sentences(text, name);
        if (said.isEmpty()) return unclear("in front of it is not this station");
        String label = said.get(0);
        List<String> rest = said.subList(1, said.size());
        String first = rest.isEmpty() ? "" : rest.get(0);

        if (label.equals("exit-only") || first.toLowerCase(Locale.ROOT).contains("exit only")) {
            Matcher exit = EXIT_ONLY.matcher(first);
            return rest.size() <= 1 && exit.matches() ? claim(StationStatus.EXIT_ONLY, exit.group("stopping"), null) : unclear("exit only, and more");
        }
        Boolean hours = rest.size() == 1 ? windowIsTheHours(first, from, to) : null;
        if (hours != null) return hours ? claim(StationStatus.CLOSED, null, null) : unclear("the hours are not the notice's");

        Matcher platform = match(List.of(ONE_PLATFORM, ONE_PLATFORM_UNTIL), first);
        if (platform != null) {
            Matcher other = rest.size() == 2 ? OTHER_DIRECTION.matcher(rest.get(1)) : null;
            boolean onlyThat = rest.size() == 1 || (other != null && other.matches() && !other.group("direction").equalsIgnoreCase(platform.group("direction")));
            boolean here = platform.group("at") == null || plainName(platform.group("at")).equals(plainName(name));
            // "Will be" only with a date, as for the station.
            boolean dated = group(platform, "from") != null || group(platform, "until") != null || !WILL.matcher(first).find();
            return onlyThat && here && dated && datesAreTheWindows(platform, from, to) ? claim(StationStatus.PART_CLOSED, platform.group("direction"), null) : unclear("one platform, and more");
        }
        if (OPEN.matcher(String.join(" ", rest)).find()) return unclear(SAYS_OPEN);
        if (rest.size() > 1) return unclear("not a plain form");
        Matcher line = ONE_LINE.matcher(first);
        if (line.matches()) return datesAreTheWindows(line, from, to) ? claim(StationStatus.PART_CLOSED, null, line.group("line")) : unclear("dates that are not the notice's");
        // "Arsenal: Closed", and nothing more.
        if (rest.isEmpty()) return label.equals("closed") ? claim(StationStatus.CLOSED, null, null) : unclear("not a plain form");
        Matcher closed = match(CLOSED, first);
        // A reason alone ("while we respond to a power failure") needs the label to say what it is the reason of.
        if (closed == null || (group(closed, "bare") != null && !label.equals("closed"))) return unclear("not a plain form");
        return datesAreTheWindows(closed, from, to) ? claim(StationStatus.CLOSED, null, null) : unclear("dates that are not the notice's");
    }

    private static Read readBus(String text, long from, long to) {
        Matcher m = BUS_CLOSED.matcher(text);
        if (!m.matches()) return unclear("not a plain form");
        String until = m.group("until") != null ? m.group("until") : m.group("late");
        boolean plain = (m.group("from") == null || isThen(m.group("from"), from, false)) && (until == null || isThen(until, to, true));
        // "From 08:00 to 18:00" on a long notice is every day.
        if (m.group("from") != null && until != null && !IN_IT.matcher(until).results().anyMatch(r -> r.group(2) != null)) plain = false;
        return plain ? claim(StationStatus.CLOSED, null, null) : unclear("a date or time that is not the notice's");
    }

    /** Whether every time, date and weekday in [worded] is of [ms]. */
    private static boolean isThen(String worded, long ms, boolean end) {
        LocalDate d = day(ms);
        Matcher m = IN_IT.matcher(worded);
        while (m.find()) {
            if (m.group("time") != null) {
                String t = m.group("time").replaceAll("[:.]", "");
                // A bare year is not a time.
                if (t.equals(m.group("time")) && t.startsWith("20") && worded.contains(" " + t) && Integer.parseInt(t) == d.getYear()) continue;
                if (!near(Integer.parseInt(t.substring(0, 2)) * 60 + Integer.parseInt(t.substring(2)), londonMinutes(ms))) return false;
            } else if (m.group("day") != null) {
                if (!lower(d.getDayOfWeek().name()).startsWith(lower(m.group("day")))) return false;
            } else {
                String month = lower(m.group("month"));
                boolean sameMonth = month.matches("\\d+") ? Integer.parseInt(month) == d.getMonthValue() : lower(d.getMonth().name()).startsWith(month);
                boolean sameYear = m.group("year") == null || d.getYear() % 100 == Integer.parseInt(m.group("year")) % 100;
                if (Integer.parseInt(m.group("num")) != d.getDayOfMonth() || !sameMonth || !sameYear) return false;
            }
        }
        return true;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    private static final Map<String, Integer> RANK = Map.of(StationStatus.CLOSED, 5, StationStatus.PART_CLOSED, 4,
            StationStatus.EXIT_ONLY, 3, StationStatus.CLOSING, 2, StationStatus.UNCLEAR, 1);

    private record Window(TflNotice n, long from, long to) {
    }

    private static Long millis(String iso) {
        try {
            return iso == null ? null : Instant.parse(iso).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** One status per stop, for those with something to say at [now]. */
    public static Map<String, StationStatus> statuses(List<TflNotice> notices, long now) {
        // Join a closure TfL has cut at midnight: the same words, back to back.
        Map<String, List<Window>> byWords = new LinkedHashMap<>();
        for (TflNotice n : notices) {
            if (!usable(n)) continue;
            Long from = millis(n.fromDate());
            Long to = millis(n.toDate());
            if (from >= to) continue;
            String key = n.atcoCode() + "|" + n.type() + "|" + n.appearance() + "|" + tidy(n.description());
            byWords.computeIfAbsent(key, k -> new ArrayList<>()).add(new Window(n, from, to));
        }
        Map<String, List<StationStatus>> found = new LinkedHashMap<>();
        for (List<Window> windows : byWords.values()) {
            windows.sort(Comparator.comparingLong(Window::from));
            List<Window> joined = new ArrayList<>(List.of(windows.get(0)));
            for (Window w : windows.subList(1, windows.size())) {
                Window last = joined.get(joined.size() - 1);
                if (w.from() - last.to() <= 2 * MIN) joined.set(joined.size() - 1, new Window(last.n(), last.from(), Math.max(last.to(), w.to())));
                else joined.add(w);
            }
            for (Window w : joined) {
                // Ended ones were kept until now so that a piece before midnight could be joined.
                if (w.to() <= now || w.from() > now + CLOSING_SOON_MS) continue;
                Read read = read(w.n(), w.from(), w.to());
                if (read == null) continue;
                String text = tidy(w.n().description());
                List<String> said = sentences(text, w.n().commonName() == null ? "" : w.n().commonName());
                Boolean hours = said.size() != 2 ? null : windowIsTheHours(said.get(1), w.from(), w.to());
                String kind = read.kind();
                // A live notice that says closed, not that it will be, means now. TfL has stamped these
                // in London time as if it were UTC, which in summer puts them an hour ahead.
                boolean live = "RealTime".equals(w.n().appearance()) && w.from() <= now + HOUR + 5 * MIN && hours == null
                        && LONDON.getRules().isDaylightSavings(Instant.ofEpochMilli(now)) && !WILL.matcher(text).find();
                // The second piece of a cut closure, seen in the minute before it starts.
                boolean aboutTo = w.from() <= now + 2 * MIN && londonMinutes(w.from()) <= 1;
                if (w.from() > now && !live && !aboutTo) {
                    // Only a whole station closing at the time its words give is worth announcing early.
                    if (!StationStatus.CLOSED.equals(kind) || !Boolean.TRUE.equals(hours)) continue;
                    kind = StationStatus.CLOSING;
                }
                String id = w.n().atcoCode();
                found.computeIfAbsent(id, k -> new ArrayList<>()).add(new StationStatus(id,
                        w.n().commonName() == null ? id : w.n().commonName(), w.n().mode() == null ? "" : w.n().mode(),
                        kind, text, live ? Math.min(w.from(), now) : w.from(), w.to(), read.directions(), read.lines(), read.why()));
            }
        }
        Map<String, StationStatus> out = new LinkedHashMap<>();
        found.forEach((id, all) -> {
            all.sort(Comparator.<StationStatus>comparingInt(s -> -RANK.get(s.kind())).thenComparing(s -> -s.from()));
            StationStatus best = all.get(0);
            StationStatus doubt = !StationStatus.CLOSED.equals(best.kind()) ? null : all.stream()
                    .filter(s -> StationStatus.PART_CLOSED.equals(s.kind()) || StationStatus.EXIT_ONLY.equals(s.kind()) || SAYS_OPEN.equals(s.why())).findFirst().orElse(null);
            out.put(id, doubt == null ? best : best.as(StationStatus.UNCLEAR, "another notice says " + (doubt.why() == null ? doubt.kind() : "otherwise")));
        });
        return out;
    }
}
