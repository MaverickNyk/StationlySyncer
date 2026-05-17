package com.stationly.backend.scheduler;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.auth.ExportedUserRecord;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.ListUsersPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutionException;

/**
 * Nightly cleanup of orphaned Firebase Auth users.
 *
 * A user is considered orphaned when ALL of:
 *   1. They exist in Firebase Auth.
 *   2. They have NO matching document in the Firestore `users` collection
 *      (i.e. /user/sync/profile never completed during signup).
 *   3. Their email is NOT verified.
 *   4. Account was created more than ORPHAN_GRACE_HOURS ago.
 *
 * Without this, signup failures (e.g. network drop between Firebase Auth user
 * creation and our backend's profile sync) leave the email permanently "taken"
 * in Firebase Auth — the user can't re-register with the same address until
 * someone removes them via the Console.
 *
 * Runs at 03:15 every day in the host timezone. Stationly mainly serves London
 * commuters so 03:15 is quiet.
 */
@Component
@Slf4j
public class OrphanAuthCleanupScheduler {

    /** Minimum age a Firebase Auth account must reach before it's a cleanup candidate. */
    private static final int ORPHAN_GRACE_HOURS = 24;

    /** Hard cap on deletions per run so a bug can't accidentally wipe the user base. */
    private static final int MAX_DELETES_PER_RUN = 500;

    @Autowired(required = false)
    private Firestore firestore;

    @Value("${orphan.auth.cleanup.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${orphan.auth.cleanup.cron:0 15 3 * * *}")
    public void cleanupOrphanedAuthUsers() {
        if (!enabled) {
            log.info("🧹 Orphan Auth cleanup is disabled (orphan.auth.cleanup.enabled=false). Skipping.");
            return;
        }
        if (firestore == null) {
            log.warn("🧹 Orphan Auth cleanup skipped — Firestore not initialised.");
            return;
        }

        log.info("🧹 Starting orphan Firebase Auth cleanup pass");
        long startedAt = System.currentTimeMillis();

        int scanned = 0;
        int deleted = 0;
        int skippedFreshAccount = 0;
        int skippedVerified = 0;
        int skippedHasFirestoreDoc = 0;
        int failures = 0;

        Instant cutoff = Instant.now().minus(ORPHAN_GRACE_HOURS, ChronoUnit.HOURS);

        try {
            ListUsersPage page = FirebaseAuth.getInstance().listUsers(null);
            while (page != null) {
                for (ExportedUserRecord user : page.getValues()) {
                    if (deleted >= MAX_DELETES_PER_RUN) {
                        log.warn("🧹 Hit deletion cap ({}). Stopping early — re-run will pick up the rest.",
                                MAX_DELETES_PER_RUN);
                        logSummary(startedAt, scanned, deleted, skippedFreshAccount,
                                skippedVerified, skippedHasFirestoreDoc, failures);
                        return;
                    }
                    scanned++;

                    // Filter 1: email verified → user is real, leave alone
                    if (user.isEmailVerified()) {
                        skippedVerified++;
                        continue;
                    }

                    // Filter 2: too fresh (within the grace window) — signup might still
                    // be in flight, or the user is about to verify
                    Instant createdAt = Instant.ofEpochMilli(user.getUserMetadata().getCreationTimestamp());
                    if (createdAt.isAfter(cutoff)) {
                        skippedFreshAccount++;
                        continue;
                    }

                    // Filter 3: Firestore doc exists → user IS in our records, even if
                    // unverified (they may verify later — leave them alone)
                    if (firestoreDocExists(user.getUid())) {
                        skippedHasFirestoreDoc++;
                        continue;
                    }

                    // True orphan — delete from Firebase Auth so the email frees up
                    try {
                        FirebaseAuth.getInstance().deleteUser(user.getUid());
                        deleted++;
                        log.info("🧹 Deleted orphan Auth user uid={} email={} created={}",
                                redact(user.getUid()), redact(user.getEmail()), createdAt);
                    } catch (FirebaseAuthException e) {
                        failures++;
                        log.warn("🧹 Failed to delete orphan Auth user uid={}: {}",
                                redact(user.getUid()), e.getMessage());
                    }
                }
                page = page.getNextPage();
            }
        } catch (FirebaseAuthException e) {
            log.error("🧹 Orphan cleanup pass aborted while listing Auth users", e);
        }

        logSummary(startedAt, scanned, deleted, skippedFreshAccount,
                skippedVerified, skippedHasFirestoreDoc, failures);
    }

    private boolean firestoreDocExists(String uid) {
        try {
            DocumentSnapshot snap = firestore.collection("users").document(uid).get().get();
            return snap.exists();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            // Treat as "doc exists" so we don't delete on transient errors.
            return true;
        } catch (ExecutionException e) {
            log.warn("🧹 Firestore lookup failed for uid={} — leaving Auth user alone", redact(uid), e);
            return true;
        }
    }

    private void logSummary(long startedAt, int scanned, int deleted,
                            int skippedFreshAccount, int skippedVerified,
                            int skippedHasFirestoreDoc, int failures) {
        long elapsedMs = System.currentTimeMillis() - startedAt;
        log.info("🧹 Orphan Auth cleanup complete in {} ms. " +
                        "scanned={} deleted={} skipped[fresh={}, verified={}, has_doc={}] failures={}",
                elapsedMs, scanned, deleted, skippedFreshAccount,
                skippedVerified, skippedHasFirestoreDoc, failures);
    }

    /** Mask a string to a short stable hash for log lines — never write PII in plaintext. */
    private static String redact(String value) {
        if (value == null || value.isEmpty()) return "none";
        return String.format("%06x", value.hashCode() & 0xFFFFFF);
    }
}
