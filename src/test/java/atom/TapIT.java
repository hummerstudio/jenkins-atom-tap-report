package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TAP and CTest in one reader. A skip directive must not count as a failure, and a failing suite must ask the runner
 * for UNSTABLE rather than failing the step — unless {@code failOnFailure} says otherwise.
 */
class TapIT {

    private static final String MIXED_TAP = """
            1..3
            ok 1 - adds numbers
            not ok 2 - divides by zero
            ok 3 - skipped on windows # SKIP not supported
            """;

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("a TAP file with a pass, a failure and a skip is counted three ways")
    void mixedTap() throws Exception {
        AtomResult run = atom.file("unit.tap", MIXED_TAP)
                .input("failOnFailure", "false")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("3", run.field("total"));
        assertEquals("1", run.field("passed"));
        assertEquals("1", run.field("failed"));
        assertEquals("1", run.field("skipped"));
        assertTrue(run.file("out/tap/index.html").contains("divides by zero"),
                "the failing case is not listed:\n" + run.file("out/tap/index.html"));
    }

    @Test
    @DisplayName("a failing TAP asks the runner for UNSTABLE without failing the step")
    void failingTapIsUnstable() throws Exception {
        AtomResult run = atom.file("unit.tap", MIXED_TAP)
                .input("failOnFailure", "false")
                .run();

        run.assertOk();
        assertEquals("UNSTABLE", buildResult(run),
                "a failing TAP must ask for UNSTABLE: " + run.document());
    }

    /** The {@code build.result} the atom asks the runner for; the key is a literal name, not a nesting path. */
    private static String buildResult(AtomResult run) {
        Object data = run.document().get("data");
        if (!(data instanceof java.util.Map<?, ?> fields)) {
            return "";
        }
        Object field = fields.get("build.result");
        if (field instanceof java.util.Map<?, ?> map && map.get("value") != null) {
            return String.valueOf(map.get("value"));
        }
        return field == null ? "" : String.valueOf(field);
    }

    @Test
    @DisplayName("failOnFailure turns the same report into a failed step")
    void failOnFailure() throws Exception {
        AtomResult run = atom.file("x.tap", "not ok 1 - boom\n")
                .input("failOnFailure", "true")
                .run();

        run.assertRefused();
        assertTrue(String.valueOf(run.document().getOrDefault("message", "")).contains("1 test(s) failed"),
                "message: " + run.document());
    }

    @Test
    @DisplayName("CTest's Test.xml is read with the same rules")
    void ctest() throws Exception {
        AtomResult run = atom.file("Testing/Temporary/Test.xml", """
                        <Testing><Test Status="failed"><Name>expected</Name><Results/></Test>\
                        <Test Status="passed"><Name>expected</Name><Results/></Test></Testing>
                        """)
                .input("{}")
                .run();

        run.assertOk();
        assertEquals("2", run.field("total"), "ctest total");
        assertEquals("1", run.field("failed"), "ctest failed");
    }

    @Test
    @DisplayName("no report at all is a clear error")
    void noReport() throws Exception {
        AtomResult run = atom.input("{}").run();

        run.assertRefused();
        assertTrue(String.valueOf(run.document().getOrDefault("message", "")).contains("no TAP or CTest report"),
                "message not readable: " + run.document());
    }

    @Test
    @DisplayName("a mid-pattern **/ matches zero directories")
    void midPatternMatchesZeroDirectories() throws Exception {
        AtomResult run = atom.file("Testing/Test.xml",
                        "<Testing><Test Status=\"passed\"><Name>calc</Name><Results/></Test></Testing>")
                .input("testResults", "**/Testing/**/Test.xml")
                .run();

        run.assertOk();
        assertEquals("1", run.field("total"), "expected 1 test: " + run.document());
        assertFalse(run.field("failed").equals("1"), "nothing failed in this report");
    }
}
