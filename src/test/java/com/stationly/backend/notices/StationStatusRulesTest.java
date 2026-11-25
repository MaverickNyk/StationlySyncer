package com.stationly.backend.notices;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A wrong "closed" is worse than a missed one. Wednesday 25 November 2026, noon, unless said. */
class StationStatusRulesTest {

    private static final String NOON = "2026-11-25T12:00:00Z";

    private static StationStatus read(String mode, String words, String from, String to, String now) {
        // The station a notice is about is Arsenal's unless it starts with another's name.
        java.util.regex.Matcher name = java.util.regex.Pattern.compile("^(Clapham Common|St. John's Wood|Colindale|COLINDALE|KENTISH TOWN|South Ealing|SOUTH KENSINGTON|Russell Square|Kennington|Bank)\\b").matcher(words);
        TflNotice n = new TflNotice("id", (name.find() ? name.group(1) : "Arsenal") + " Underground Station", mode, "Closure", "RealTime", from, to, words);
        return StationStatusRules.statuses(List.of(n), Instant.parse(now).toEpochMilli()).get("id");
    }

    /** A window of a month around now, so that only the words can tell. */
    private static String kind(String mode, String words) {
        StationStatus s = read(mode, words, "2026-11-10T04:30:00Z", "2026-12-10T01:29:00Z", NOON);
        return s == null ? null : s.kind();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Open now, at some hours, or on another date.
            "HAMPSTEAD STATION: This station is open between 0700 and 1900.",
            "ARSENAL STATION: the station will close at 2200 until Thursday.",
            "JUBILEE LINE: Northern ticket office shop will close at 2300.",
            "Arsenal: Closed - This station is closed on Sundays.",
            "Arsenal: Closed - This station will be closed from 2200.",
            "ARSENAL STATION: This station will be closed from Saturday 28 November.",
            "ARSENAL STATION: This station is closed Monday to Friday.",
            "ARSENAL STATION: This station was closed. It has reopened.",
            // Trains stop, or people can get in or out.
            "Arsenal: Closed - Interchange only. This station is closed for entry and exit.",
            "ARSENAL STATION: The station is closed except for interchange.",
            "Arsenal: Closed - This station is closed to customers entering.",
            // Part of it.
            "ARSENAL STATION: This station is closed to eastbound trains.",
            "Arsenal: Closed - platform 2.",
            "Arsenal: Closed - the entrance in Gillespie Road is closed.",
            "Arsenal: Closed lifts - use the stairs.",
            // Another date, or some hours, however it is put (the second review's).
            "ARSENAL STATION: Saturday 28 and Sunday 29 November, the station will be closed.",
            "ARSENAL STATION: the station will be closed on 25 December.", "ARSENAL STATION: This station will be closed on Christmas Day.",
            "ARSENAL STATION: This station will be closed this Sunday.", "ARSENAL STATION: This station will be closed from tomorrow.",
            "ARSENAL STATION: This station will be closed from 28 Nov.", "ARSENAL STATION: This station will be closed from 10pm.",
            "ARSENAL STATION: the station will close at midnight.", "ARSENAL STATION: the station will close early.",
            "Arsenal: Closed - This station is closed at night.", "Arsenal: Closed - This station is closed in the evenings.",
            "Arsenal: Closed - Closed from 22:00. This station is closed until 05:00.", "ARSENAL STATION: This station will be closed while we carry out repairs.",
            // Open after all.
            "Arsenal: Closed - fire alert. This station is now\r\nopen.", "Arsenal: Closed - This station has\u00a0reopened.", "Arsenal: Closed - This station has re-opened.",
            "Arsenal: Closed - This station is open again.", "Arsenal: Closed - This station was closed due to a fire alert. It is open.",
            "Arsenal: Closed - Earlier fire alert now cleared. Trains stopping normally.", "Arsenal: Closed - This station is closed while we respond to a fire alert. It has now reopened.",
            // Something in it.
            "Arsenal: Closed - lift out of service", "Arsenal: Closed - Ticket office", "Arsenal: Closed - the Gillespie Road entrance", "Bank: Closed - Waterloo & City line only",
            "Arsenal: Closed - Northern line. Victoria line trains are still running.", "Arsenal: Closed due to faulty lifts - no step-free access", "Arsenal: closed to wheelchair users",
            "ARSENAL STATION: The ticket office is open between 0700 and 1900.", "Arsenal: Closed - This station is closed for customers without tickets.",
            // Which direction, or not now.
            "Arsenal: Part Closed - The westbound platform is open, eastbound platform is closed.", "Arsenal: Part Closed - the westbound platform was closed earlier.",
            "Arsenal: Part Closed - the westbound platform is not closed.", "Arsenal: Part Closed - the westbound platform was closed. It has re-opened.",
            "Arsenal: Part Closed - the westbound platform toilets are closed.", "Arsenal: Part Closed - westbound platform 3 is closed. Use platform 4.",
            "Arsenal: Part Closed - Use the westbound platform while the eastbound is closed.", "Arsenal: Part Closed - the westbound platform is closed on Saturday 28 November.",
            "Arsenal: Part Closed - the westbound platform is closed from 10pm.", "Arsenal: Part Closed - the westbound platform is closed. The eastbound platform is closed.",
            "Arsenal: Part Closed - the westbound platform is closed. Westbound trains stop as usual.",
            // The third review's: something at the station, or another's name.
            "Arsenal: The lift at this station is closed.", "Arsenal: The ticket office at the station is closed.", "Arsenal: The Gillespie Road entrance to the station is closed.",
            "Arsenal: The car park at this station is closed due to flooding.", "Arsenal: The Thameslink station is closed.", "Ticket office: Closed", "Lifts: Closed", "Northern line: Closed",
            "Step free access: closed while we repair a lift", "Holloway Road: Closed - This station is closed while we respond to a fire alert.",
            // Open after all, in what looks like advice.
            "Arsenal: Closed - This station is closed while we respond to a fire alert. Please note the station has now reopened.", "Arsenal: Closed - This station is closed due to a fire alert. This station remains open.",
            "Arsenal: Closed - This station is closed due to a fire alert. Arsenal station remains open.", "Arsenal: Closed - This station is closed due to a fire alert. Please note only the eastbound platform is affected.",
            "Arsenal: Closed - This station is closed due to a fire alert. Please note this is on Sundays only.", "Arsenal: Closed - This station is closed due to a fire alert. Please use the Gillespie Road entrance.",
            "Arsenal: Closed - This station is closed due to a fire alert. Last trains call at 2330.", "Arsenal: Part Closed - the westbound platform is closed. Please note it has reopened.",
            // A time or date that is not the notice's.
            "Arsenal: Closed - This station is closed until 07:00.", "Arsenal: Closed - Closed until 05:30 Monday", "Arsenal: Closed - This station is closed until 20 November.", "Arsenal: Closed until late morning",
            "Arsenal: Closed until 2024", "ARSENAL STATION: From Tuesday 10 November 2027 until December 2027, the station is closed.", "ARSENAL STATION: From Friday 10 November until December 2026, the station is closed.",
            "Arsenal: Part Closed - the westbound platform is closed until 07:00.", "ARSENAL STATION: closed to Piccadilly line trains until 07:00.",
            // A reason that is not one.
            "Arsenal: Closed - This station is closed for engineering work overnight.", "Arsenal: Closed - This station is closed for works next month.", "Arsenal: Closed - This station is closed for works this coming Sat.",
            "Arsenal: Closed - This station is closed for cyclists.", "Arsenal: Closed - This station is closed for journeys starting here.", "Arsenal: Closed for maintenance of the toilets", "Arsenal: Closed for new year's day",
            "Arsenal: Closed - This station is closed due to an earlier fire alert, now cleared.", "Arsenal: Part Closed - the westbound platform is closed for engineering works on Sundays.",
            "Arsenal: Part Closed - the westbound platform is closed for works every night.", "Arsenal: Part Closed - the westbound platform is closed for boarding.", "Arsenal: Part Closed - the westbound platform is closed due to an incident, now reopened.",
            "Arsenal: Exit Only - This station is exit only to prevent overcrowding on Sundays.", "Arsenal: Exit Only - This station is exit only due to overcrowding in the evening peak.",
            "Arsenal: Closed - fire alert. This station is closed.", "Arsenal: the fire alert", "Arsenal: while we respond to a fire alert",
            // The way to a platform, or a platform at another time.
            "Arsenal: Part Closed - The northbound platform was closed earlier but has reopened.",
            "Arsenal: Part Closed - The westbound platform will be closed from 2200 tonight.",
            "Arsenal: Part Closed - The escalator to the northbound platform is closed.",
            "Arsenal: Part Closed - The westbound platform is closed on Sundays.",
            "Arsenal: Part Closed - From Saturday 28 November the westbound platform will be closed.",
            "Arsenal: Exit Only - This station is exit only on Sundays.",
            "Arsenal: Exit Only - This station is exit only between 0700 and 1000.",
            "ARSENAL STATION: on Sundays, closed to Piccadilly line trains.",
            "ARSENAL STATION: from 2200, closed to Piccadilly line trains."})
    void claimsNothing(String words) {
        String kind = kind("tube", words);
        assertTrue(kind == null || kind.equals("unclear"), words + " -> " + kind);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Bus stop closed from 28 November until 10 December", "Bus stop closed from 01:29 28/11/2026 until 01:29 10/12/26", "Bus stop closed until 20 November", "Bus stop closed due to a street market on Sundays until 10 December",
            "Bus stop closed due to works from Monday", "Bus stop closed due to works starting next week", "Bus stop closed due to works in the mornings", "Bus stop closed due to carnival on Saturday",
            "Bus stop closed. Please note buses still serve this stop", "Bus stop closed please use from 10am", "Bus stop closed. Buses are on diversion after 10pm", "Bus stop closed from 04:30 to 01:29",
            "Bus stop closed due to night works", "Bus Stop Closed from 08:00 24/11/26 until 17:00 18/11/26 please use the next stop", "Bus stop closed until 01:29 on Monday 10 December",
            "Next bus stop closed. This stop is open.", "Opposite bus stop closed. Use this stop.", "Bus stop closed for route 29 only", "Routes 29 and 253: bus stop closed. Route 4 stops as normal.",
            "Stop closed N29 only", "Temporary bus stop closed. Use permanent stop.", "Bus Stop Closed from 25-27 Nov 2026 from 08:00 to 18:00 please use the next or previous stop",
            "Bus Stop Closed daily between 09:30-16:00 25/26/11/26 please use the next stop", "Bus Stop Closed Nightly 25-27 Nov 26 21:00-23:59 Please use the next stop",
            "Bus stop closed - Bridge works nightly 2100-0500 until 7/12/26.", "Bus Stop Closed Daily works 09:30 - 15:00 25 and 26 November 2026", "Bus stop closed. This stop will open on Monday.",
            "Bus Stop Closed 09:30 - 11:30 Mon-Fri. Use next stop",
            "This bus stop is closed from 22:00 to 05:00 until 10 December.",
            "Bus stop closed overnight.",
            "Bus stop closed from 28 November.",
            "Route 255 is on diversion until 17:00."})
    void claimsNothingOfABusStop(String words) {
        assertEquals("unclear", kind("bus", words), words);
        // Nor with a window that has the same hours.
        StationStatus s = read("bus", words, "2026-11-25T08:00:00Z", "2026-11-27T18:00:00Z", "2026-11-25T21:00:00Z");
        assertEquals("unclear", s.kind(), words);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "tube|Arsenal: Closed - This station is closed while we respond to a fire alert.|closed",
            "tube|Clapham Common: Closed - while we respond to a power failure.|closed",
            "tube|Arsenal: Closed|closed",
            "tube|St. John's Wood: Closed - This station is closed due to the temporary unavailability of station staff.|closed",
            "tube|Colindale: Closed - Closed until December 2026 for major rebuilding works. Call us on 0343 222 1234 if you need help.|closed",
            "tube|COLINDALE STATION: From Tuesday 10 November until December 2026, the station is closed for major rebuilding works. Connecting replacement bus service NL6 operates.|closed",
            "tube|KENTISH TOWN UNDERGROUND STATION: The station will close from Tuesday 10 November for up to a year to enable vital work to be carried out to replace the escalators. Kentish Town Thameslink station will remain open for customers.|closed",
            "tube|South Ealing: From Tuesday 10 November until mid December, the westbound platform at South Ealing will be closed for planned platform works. Eastbound trains are not affected and will serve the station as usual.|part_closed",
            "tube|SOUTH KENSINGTON STATION: Tuesday 10 November until December 2026, closed to Piccadilly line trains. Please use District line trains instead.|part_closed",
            "tube|Russell Square: Exit Only - This station is exit only and westbound trains are not stopping at this station, this is to prevent overcrowding due to faulty lifts. Call us on 0343 222 1234.|exit_only",
            "tube|Kennington: Exit Only - This station is open for exit and interchange only due to faulty lifts.|exit_only",
            "bus|Bus Stop Closed Please use the next stop or the previous stop to catch your bus|closed",
            "bus|This bus stop is closed due to Cadent Gas works until 10 December 01:29. Please use next or previous stop|closed",
            "bus|Bus Stop Closed from 04:30 10/11/2026 until 01:29 10/12/26 please use the nest or previous stop|closed",
            "bus|This bus stop is closed. Please use the temporary bus stop located nearby.|closed",
            "bus|Bus stop closed to facilitate UKPN works until approx. 01:29 Thu 10 December|closed"})
    void stillReadsThePlainOnes(String mode, String words, String kind) {
        assertEquals(kind, kind(mode, words), words);
    }

    @Test
    void anEveningClosureIsAnnouncedHappensAndEnds() {
        String words = "ARSENAL STATION: the station will close at 2335 approximately. Last trains will be at 2248 to Cheshunt. Please use local London Buses services. A very limited replacement bus service operates, southbound only, from Cheshunt.";
        for (String[] c : new String[][]{{"2026-11-25T11:00:00Z", null}, {"2026-11-25T18:00:00Z", "closing"}, {"2026-11-25T23:34:00Z", "closing"},
                {"2026-11-25T23:35:00Z", "closed"}, {"2026-11-26T01:00:00Z", "closed"}, {"2026-11-26T01:29:00Z", null}}) {
            StationStatus s = read("overground", words, "2026-11-25T23:35:00Z", "2026-11-26T01:29:00Z", c[0]);
            assertEquals(c[1], s == null ? null : s.kind(), c[0]);
        }
    }

    @Test
    void hoursCountOnlyOnANightThatIsTheirs() {
        String words = "ARSENAL STATION: The station is open between 0700 and 1900.";
        assertEquals("closed", read("tube", words, "2026-11-25T19:00:00Z", "2026-11-26T06:59:00Z", "2026-11-25T20:00:00Z").kind());
        assertEquals("unclear", read("tube", words, "2026-11-23T19:00:00Z", "2026-11-27T07:00:00Z", NOON).kind(), "a week that starts at seven");
        // Summer: 19:00 in London is 18:00 UTC. 19:00 UTC would be an hour out.
        assertEquals("closed", read("tube", words, "2026-07-25T18:00:00Z", "2026-07-25T22:59:00Z", "2026-07-25T20:00:00Z").kind());
        assertEquals("unclear", read("tube", words, "2026-07-25T19:00:00Z", "2026-07-25T23:30:00Z", "2026-07-25T20:00:00Z").kind());
    }

    @Test
    void onlyInSummerAndOnlyIfItIsClosedMayALiveNoticeStartAheadOfNow() {
        String is = "Arsenal: Closed - This station is closed while we repair a lift.";
        String will = "ARSENAL STATION: This station will be closed while we carry out repairs.";
        assertNotNull(read("tube", is, "2026-07-25T12:50:00Z", "2026-07-26T01:29:00Z", "2026-07-25T12:00:00Z"));
        assertNull(read("tube", will, "2026-07-25T12:50:00Z", "2026-07-26T01:29:00Z", "2026-07-25T12:00:00Z"));
        assertNull(read("tube", is, "2026-11-25T12:50:00Z", "2026-11-26T01:29:00Z", NOON));
    }

    @Test
    void untilAClockTimeIsTonights() {
        String words = "Arsenal: Closed - This station is closed until 07:00 Thursday morning.";
        assertEquals("closed", read("tube", words, "2026-11-25T08:00:00Z", "2026-11-26T01:29:00Z", NOON).kind());
        assertEquals("unclear", read("tube", words, "2026-11-25T08:00:00Z", "2026-11-28T01:29:00Z", NOON).kind());
    }

    @Test
    void tflsOwnExitOnlyType() {
        TflNotice n = new TflNotice("id", "Kennington Underground Station", "tube", "Exit Only", "RealTime", "2026-11-25T08:00:00Z", "2026-11-26T01:29:00Z",
                "Kennington: Exit Only - This station is open for exit and interchange only due to faulty lifts.");
        assertEquals("exit_only", StationStatusRules.statuses(List.of(n), Instant.parse(NOON).toEpochMilli()).get("id").kind());
    }

    @Test
    void aNoticeThatContradictsAnotherLeavesItUnclear() {
        for (String other : List.of("Arsenal: Part Closed - the westbound platform is closed.", "Arsenal: This station has reopened.", "Arsenal: Exit Only")) {
            TflNotice a = new TflNotice("id", "Arsenal Underground Station", "tube", "Closure", "RealTime", "2026-11-25T08:00:00Z", "2026-11-26T01:29:00Z", "Arsenal: Closed - This station is closed.");
            TflNotice b = new TflNotice("id", "Arsenal Underground Station", "tube", "Closure", "RealTime", "2026-11-25T08:00:00Z", "2026-11-26T01:29:00Z", other);
            assertEquals("unclear", StationStatusRules.statuses(List.of(a, b), Instant.parse(NOON).toEpochMilli()).get("id").kind(), other);
        }
    }

    @Test
    void aHugeNoticeIsUnclearAndDoesNotOverflow() {
        assertEquals("unclear", kind("bus", "Bus Stop Closed " + "please use next ".repeat(5000)));
        assertEquals("unclear", kind("tube", "Arsenal: Closed - " + "This station is closed. ".repeat(5000)));
    }
}
