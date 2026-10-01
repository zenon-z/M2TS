package de.tum.tssimulator.experiments.support;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TAKES the host measurement lock for a Java run, and releases it on exit.
 *
 * <p>WHY ACQUIRE RATHER THAN CHECK. Checking alone catches a JVM launched <em>during</em> a held
 * sweep, which is the rarer direction. The likelier one is a simulator already running when a
 * sweep starts, launched through the IDE or Maven and therefore holding nothing:
 * {@code run_lock} inspects the lock directory, not running JVMs, and {@code preflight.py} catches
 * only intruders already connected to PostgreSQL, which a simulator never is. So the sweep would
 * start beside it and both measurements would be corrupt. Writing the lock closes that direction.
 *
 * <p>WHY OVERLAP MATTERS. A simulator run is CPU-saturating; a PostgreSQL campaign measures
 * latency on the same machine, and its agreement gate's threshold was calibrated on a quiet host.
 * An overlap does not add noise that averages out -- it shifts the measured quantity, and
 * asymmetrically between the arms. The reverse holds too: the identity check reads simulator
 * throughput to four decimals, meaningless while a multi-worker sweep saturates the same cores.
 *
 * <p>ONE LOCK, ONE OWNER FORMAT, TWO LANGUAGES. This writes the same lock directory and the same
 * {@code owner.json} shape as {@code run_lock.acquire}, so either side sees the other's lock.
 *
 * <p><b>START TOKEN IS DELIBERATELY OMITTED, and that is not laziness.</b> Python records
 * {@code start_token} from {@code $p.StartTime.Ticks} -- .NET ticks in local time. Any token this
 * class could naturally produce ({@code ProcessHandle.info().startInstant()}) is a different
 * format for the same instant, and {@code _owner_is_live} compares tokens as strings: a mismatch
 * is read as "PID was recycled", so Python would declare a <em>live</em> Java lock STALE and break
 * it. Omitting the field makes Python skip that comparison ({@code recorded is None}) and fall
 * back to pid liveness alone. The cost is exposure to PID recycling for Java-held locks, which
 * errs toward refusing to start -- the direction {@code run_lock}'s own docstring argues for,
 * since it costs one {@code --force-unlock} rather than a corrupted run. Normal exits release
 * through the shutdown hook, so a surviving Java lock means the JVM was killed outright.
 *
 * <p>SCOPE, STATED HONESTLY. Wired into the entry points that participate in V1b measurement. The
 * repository has 50-odd other experiment mains that do not call it, and for those the lock remains
 * a convention. An IDE language server compiling into {@code target/classes} on save is also
 * outside what this can police.
 */
public final class RunLockGuard {

    private static final String LOCK_REL = "scripts/postgres_anchor/.sweep.lock";
    private static final String OWNER = "owner.json";
    private static final String ENV_OWNER = "TSSIM_RUN_LOCK_PID";
    private static final String IGNORE_PROPERTY = "ts.runlock.ignore";
    private static final Pattern PID = Pattern.compile("\"pid\"\\s*:\\s*(\\d+)");

    private static Path acquired;          // non-null only when THIS JVM wrote the lock

    private RunLockGuard() {
    }

    /**
     * Acquires the host lock, or aborts the JVM if another live run holds it.
     *
     * @param what short description of this run, recorded in the owner file and used in messages
     */
    public static synchronized void require(String what) {
        if (Boolean.getBoolean(IGNORE_PROPERTY)) {
            System.out.println("RunLockGuard: -D" + IGNORE_PROPERTY + " set -- proceeding WITHOUT "
                    + "the host lock. Measurements from this run are uncontrolled for host "
                    + "contention and must not be reported as a campaign.");
            return;
        }
        Optional<Path> root = findRepoRoot();
        if (root.isEmpty()) {
            System.out.println("RunLockGuard: repository root not found from the working "
                    + "directory; proceeding WITHOUT the lock.");
            return;
        }
        Path lockDir = root.get().resolve(LOCK_REL);
        try {
            Files.createDirectory(lockDir);                      // atomic; the whole guarantee
        } catch (FileAlreadyExistsException existing) {
            if (!handleExistingLock(lockDir, what)) {
                return;                                          // our own wrapper already holds it
            }
            try {
                Files.createDirectory(lockDir);
            } catch (IOException e) {
                abort(what, "could not take the lock after breaking a stale one: " + e);
                return;
            }
        } catch (IOException e) {
            abort(what, "could not create the lock directory: " + e);
            return;
        }
        writeOwner(lockDir, what);
        acquired = lockDir;
        Runtime.getRuntime().addShutdownHook(new Thread(RunLockGuard::release, "runlock-release"));
        System.out.println("RunLockGuard: host lock acquired (pid " + ProcessHandle.current().pid()
                + ").");
    }

    /**
     * @return true if the caller should now create the lock (it was stale and has been removed);
     *         false if the lock is legitimately ours already and nothing further is needed
     */
    private static boolean handleExistingLock(Path lockDir, String what) {
        Path ownerFile = lockDir.resolve(OWNER);
        Long ownerPid = readOwnerPid(ownerFile);
        if (ownerPid == null) {
            // A lock directory with no readable owner is exactly what the Python side refuses to
            // break silently: an unidentifiable lock treated as absent is the same hole as no lock.
            abort(what, "the lock exists but its owner record is missing or unreadable. "
                    + "If no run is active: python scripts/run_java.py --force-unlock");
            return false;
        }
        String mine = System.getenv(ENV_OWNER);
        if (mine != null && mine.trim().equals(Long.toString(ownerPid))) {
            System.out.println("RunLockGuard: lock already held by our own wrapper (pid "
                    + ownerPid + "); proceeding without re-acquiring.");
            return false;
        }
        if (ProcessHandle.of(ownerPid).isPresent()) {
            abort(what, "the host measurement lock is held by live pid " + ownerPid);
            return false;
        }
        System.out.println("RunLockGuard: breaking STALE lock previously held by pid " + ownerPid
                + " (process is gone).");
        try {
            Files.deleteIfExists(ownerFile);
            Files.deleteIfExists(lockDir);
        } catch (IOException e) {
            abort(what, "stale lock could not be removed: " + e);
            return false;
        }
        return true;
    }

    private static Long readOwnerPid(Path ownerFile) {
        try {
            Matcher m = PID.matcher(Files.readString(ownerFile));
            return m.find() ? Long.parseLong(m.group(1)) : null;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private static void writeOwner(Path lockDir, String what) {
        long pid = ProcessHandle.current().pid();
        String when = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        // start_token omitted on purpose -- see the class javadoc. Written as JSON by hand rather
        // than pulling in a dependency for four fields.
        String json = "{\n  \"pid\": " + pid + ",\n  \"started_at\": \"" + when + "\",\n"
                + "  \"argv\": \"java " + what.replace("\\", "").replace("\"", "") + "\"\n}\n";
        try {
            Files.writeString(lockDir.resolve(OWNER), json);
        } catch (IOException e) {
            System.out.println("RunLockGuard: lock taken but owner record could not be written ("
                    + e + "). The next run will refuse rather than break it.");
        }
    }

    /** Releases only a lock this JVM wrote, and only if it still owns it. */
    private static synchronized void release() {
        if (acquired == null) {
            return;
        }
        Path ownerFile = acquired.resolve(OWNER);
        Long ownerPid = readOwnerPid(ownerFile);
        if (ownerPid != null && ownerPid != ProcessHandle.current().pid()) {
            System.out.println("RunLockGuard: not releasing -- lock is now owned by pid "
                    + ownerPid + ".");
            return;
        }
        try {
            Files.deleteIfExists(ownerFile);
            Files.deleteIfExists(acquired);
        } catch (IOException e) {
            // Loud: a lock that cannot be removed refuses every later run, and that needs to be
            // known now rather than at the next launch.
            System.out.println("RunLockGuard: could not remove " + acquired + " (" + e + ").");
        }
        acquired = null;
    }

    private static void abort(String what, String reason) {
        System.err.println();
        System.err.println("ABORT: " + what + " will not start -- " + reason + ".");
        System.err.println("  Two measurement runs on one host corrupt BOTH, not just the later");
        System.err.println("  one: the PostgreSQL arm's agreement gate is calibrated on a quiet");
        System.err.println("  host, and simulator throughput is read to four decimals.");
        System.err.println("  Check with : python scripts/run_java.py --status");
        System.err.println("  Run via    : python scripts/run_java.py <your java command>");
        System.err.println("  Override   : -D" + IGNORE_PROPERTY + "=true (NOT for campaign data)");
        System.exit(1);
    }

    /** Walks up from the working directory, so an IDE launching from a module dir still finds it. */
    private static Optional<Path> findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("scripts/postgres_anchor"))) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }
}
