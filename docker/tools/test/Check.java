import java.util.Objects;

/** Minimal assertions for the container tool's tests, which run without JUnit or Maven. */
final class Check {

    private static int failures;

    private Check() {
    }

    static void eq(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual))
            fail(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    static void isTrue(boolean condition, String what) {
        if (!condition)
            fail(what);
    }

    static void throwsWith(String fragment, Runnable body, String what) {
        try {
            body.run();
            fail(what + ": expected an exception mentioning \"" + fragment + "\"");
        } catch (RuntimeException e) {
            if (e.getMessage() == null || !e.getMessage().contains(fragment))
                fail(what + ": exception message <" + e.getMessage() + "> lacks \"" + fragment + "\"");
        }
    }

    static void fail(String message) {
        failures++;
        System.err.println("FAIL " + message);
    }

    /** Ends a test class: prints a summary and exits non-zero if anything failed. */
    static void done(String suite) {
        if (failures > 0) {
            System.err.println(suite + ": " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println(suite + ": OK");
    }
}
