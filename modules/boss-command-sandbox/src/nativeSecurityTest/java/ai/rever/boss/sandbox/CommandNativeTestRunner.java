package ai.rever.boss.sandbox;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/** Test-only progress reporting; native calls can block without producing JUnit dots. */
public final class CommandNativeTestRunner {
    private static final AtomicReference<String> STAGE = new AtomicReference<>("starting suite");
    private static final long STARTED = System.nanoTime();

    private CommandNativeTestRunner() {}

    public static void stage(String value) {
        STAGE.set(value);
        log(value);
    }

    private static void log(String message) {
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - STARTED);
        System.out.println(Instant.now() + " [native +" + seconds + "s] " + message);
        System.out.flush();
    }

    public static void main(String[] args) {
        ScheduledExecutorService progress = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "native-test-progress");
            thread.setDaemon(true);
            return thread;
        });
        progress.scheduleAtFixedRate(() -> log("still waiting: " + STAGE.get()), 15, 15, TimeUnit.SECONDS);
        JUnitCore junit = new JUnitCore();
        junit.addListener(new RunListener() {
            @Override
            public void testStarted(Description test) {
                stage("START " + test.getMethodName());
            }

            @Override
            public void testFailure(Failure failure) {
                log("FAIL " + failure.getDescription().getMethodName());
                System.out.print(failure.getTrace());
                System.out.flush();
            }

            @Override
            public void testFinished(Description test) {
                stage("END " + test.getMethodName());
            }
        });
        Result result;
        try {
            result = junit.run(CommandNativeSecurityTest.class);
            log("RESULT tests=" + result.getRunCount() + " failures=" + result.getFailureCount()
                    + " elapsed_ms=" + result.getRunTime());
        } finally {
            progress.shutdownNow();
        }
        System.exit(result.wasSuccessful() ? 0 : 1);
    }
}
