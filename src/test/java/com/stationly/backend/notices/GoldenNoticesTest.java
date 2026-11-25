package com.stationly.backend.notices;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real TfL notices, 2019 to 2026 (web.archive.org, then live), and worded edge
 * cases, at several times of day, with what we must say about each.
 *
 * A changed answer here is a changed claim to passengers: look at each one before
 * regenerating ({@code -Dgolden.write=path}). {@code -Dgolden=path} reads another file.
 */
class GoldenNoticesTest {

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** As the service reads them: whatever is not a notice is null. */
    private static TflNotice notice(JsonNode n) {
        try {
            return n.isObject() ? JSON.treeToValue(n, TflNotice.class) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void saysTheSameAboutEveryKnownNotice() throws Exception {
        String other = System.getProperty("golden");
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/notices/golden.json")) {
            cases = other != null ? JSON.readTree(new File(other)) : JSON.readTree(in);
        }
        int checked = 0;
        List<String> wrong = new ArrayList<>();
        for (JsonNode c : cases) {
            List<TflNotice> notices = new ArrayList<>();
            c.get("notices").forEach(n -> notices.add(notice(n)));
            long now = c.get("now").asLong();
            Map<String, StationStatus> got = StationStatusRules.statuses(notices, now);
            List<JsonNode> actual = new ArrayList<>();
            for (StationStatus s : got.values()) {
                var node = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(s);
                node.set("one", JSON.valueToTree(StationNotice.of(s, now, 1)));
                node.set("two", JSON.valueToTree(StationNotice.of(s, now, 2)));
                actual.add(node);
            }
            JsonNode a = JSON.readTree(JSON.writeValueAsString(actual));
            checked += a.size();
            if (System.getProperty("golden.write") != null) ((com.fasterxml.jackson.databind.node.ObjectNode) c).set("expect", a);
            else if (!a.equals(c.get("expect"))) wrong.add("now " + now + "\n  want " + c.get("expect") + "\n  got  " + a);
        }
        if (System.getProperty("golden.write") != null) JSON.writeValue(new File(System.getProperty("golden.write")), cases);
        assertTrue(checked > 100, "only " + checked);
        assertEquals(0, wrong.size(), () -> wrong.size() + " differ, first:\n" + wrong.get(0));
        System.out.println("golden: " + cases.size() + " cases, " + checked + " statuses");
    }
}
