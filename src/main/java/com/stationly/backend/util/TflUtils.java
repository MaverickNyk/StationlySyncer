package com.stationly.backend.util;

import java.util.Map;
import java.util.List;
import java.util.Arrays;

public class TflUtils {

    // Mapping of transport mode to its corresponding stopType for station filtering
    public static final Map<String, String> MODE_STOPTYPE_MAP = Map.of(
            "bus", "NaptanPublicBusCoachTram",
            "tube", "NaptanMetroStation",
            "underground", "NaptanMetroStation",
            "overground", "NaptanRailStation",
            "elizabeth-line", "NaptanRailStation",
            "dlr", "NaptanMetroStation",
            "national-rail", "NaptanRailStation",
            "tram", "NaptanPublicBusCoachTram",
            "river-bus", "NaptanFerryPort",
            "cable-car", "NaptanCableCarStation");

    public static final List<String> GOOD_SERVICE_MESSAGES = Arrays.asList(
            "Please mind the gap between the train and the platform. Mind the gap.",
            "Please stand behind the yellow line and stay back from the platform edge.",
            "See it, say it, sorted. Text the British Transport Police on 61016.",
            "Please hold the handrail on the escalators and always stand on the right.",
            "Please move down inside the carriages and use all available space.",
            "Please offer your seat to those who may need it more than you. Thank you.",
            "Please keep all personal belongings with you at all times. Thank you.",
            "Check before you travel. Plan your journey at tfl.gov.uk or on the TfL Go app.",
            "Please have your tickets or contactless cards ready before the barriers.",
            "Follow the signs for a way out and keep to the left when on the stairs.",
            "For a more comfortable journey, please carry a bottle of water with you.",
            "Please keep the doorways clear to allow other customers to board the train.");

    public static String getExpectedStopType(String mode) {
        return MODE_STOPTYPE_MAP.get(mode.toLowerCase());
    }
}
