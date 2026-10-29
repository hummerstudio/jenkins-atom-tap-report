package com.jenkinschina.atom.tapreport;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * TAP and CTest as an atom: read the two report styles the smaller test frameworks write, show them, gate on failures.
 *
 * <p>JUnit XML is common enough to have its own atom; everything else (prove, bats, node-tap, CTest) writes TAP or the
 * CTest XML, and until now those results were invisible in a build.
 */
public class TapReportAtom implements AtomTask {

    private static final Pattern TAP = Pattern.compile("^(not ok|ok)\\b\\s*(\\d+)?\\s*(?:-\\s*)?(.*)$");
    private static final Pattern DIRECTIVE = Pattern.compile("#\\s*(SKIP|TODO)\\b\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String pattern = context.getParam("pattern", "**/*.tap, **/Testing/**/Test.xml, **/test-results/*.tap");
        String label = context.getParam("label", "Test results (TAP)");
        String outDir = context.getParam("outputDir", "out/tap");
        boolean failOnFailure = context.getBooleanParam("failOnFailure", false);

        List<Path> files = find(workspace, pattern);
        if (files.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "no TAP or CTest report matched " + pattern);
        }

        List<Case> cases = new ArrayList<>();
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.contains("<Testing") || text.contains("<Test ")) {
                cases.addAll(parseCtest(file, text));
            } else {
                cases.addAll(parseTap(file, text));
            }
        }
        if (cases.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "no test case found in " + files.size() + " file(s)");
        }

        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (Case test : cases) {
            switch (test.verdict()) {
                case "failed" -> failed++;
                case "skipped" -> skipped++;
                default -> passed++;
            }
        }

        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"), render(label, pattern, cases, passed, failed, skipped),
                StandardCharsets.UTF_8);

        context.log(String.format(Locale.ROOT, "[tap] %d case(s): %d passed, %d failed, %d skipped from %d file(s)",
                cases.size(), passed, failed, skipped, files.size()));

        AtomResult result = AtomResult.success()
                .data("total", String.valueOf(cases.size()))
                .data("passed", String.valueOf(passed))
                .data("failed", String.valueOf(failed))
                .data("skipped", String.valueOf(skipped))
                .report("report", label, dir.toString(), "index.html");

        if (failed > 0 && failOnFailure) {
            return AtomResult.failure(failed + " test(s) failed")
                    .data("total", String.valueOf(cases.size()))
                    .data("failed", String.valueOf(failed))
                    .report("report", label, dir.toString(), "index.html");
        }
        return result;
    }

    private static List<Case> parseTap(Path file, String text) {
        List<Case> cases = new ArrayList<>();
        String caseName = file.getFileName().toString();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("1..")) {
                continue;
            }
            Matcher matcher = TAP.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            boolean ok = "ok".equals(matcher.group(1));
            String rest = matcher.group(3) == null ? "" : matcher.group(3).strip();
            Matcher directive = DIRECTIVE.matcher(rest);
            String verdict;
            if (directive.find()) {
                verdict = "skipped";
                rest = rest.substring(0, directive.start()).strip();
            } else {
                verdict = ok ? "passed" : "failed";
            }
            cases.add(new Case(caseName, rest.isEmpty() ? "case " + cases.size() : rest, verdict, ""));
        }
        return cases;
    }

    /** CTest writes {@code <Test Status="passed"><Name>…</Name></Test>} inside {@code <Results>}. */
    private static List<Case> parseCtest(Path file, String text) {
        List<Case> cases = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
            NodeList tests = document.getElementsByTagName("Test");
            for (int i = 0; i < tests.getLength(); i++) {
                Element test = (Element) tests.item(i);
                String status = test.getAttribute("Status");
                String name = textOf(test, "Name");
                String verdict = switch (status) {
                    case "passed" -> "passed";
                    case "notrun", "disabled" -> "skipped";
                    default -> "failed"; // failed, timeout, exception, notrun-with-error
                };
                String measured = textOf(test, "Results");
                cases.add(new Case(file.getFileName().toString(), name.isEmpty() ? "test " + i : name, verdict,
                        abbreviate(measured)));
            }
        } catch (Exception ignored) {
            // an unreadable file contributes nothing rather than failing the build
        }
        return cases;
    }

    private static String textOf(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 120 ? oneLine.substring(0, 120) + "…" : oneLine;
    }

    private static List<Path> find(Path workspace, String pattern) throws java.io.IOException {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String part : pattern.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + trimmed));
            }
        }
        List<Path> found = new ArrayList<>();
        try (var walk = Files.walk(workspace, 12)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> matchers.stream().anyMatch(m -> m.matches(workspace.relativize(path)) || m.matches(java.nio.file.Path.of("_").resolve(workspace.relativize(path)))
                            || m.matches(path.getFileName())))
                    .sorted()
                    .limit(500)
                    .forEach(found::add);
        }
        return found;
    }

    private static String render(String label, String pattern, List<Case> cases, int passed, int failed, int skipped) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<title>").append(escape(label)).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 18px}")
                .append(".cards{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:20px}")
                .append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:12px 16px;min-width:100px}")
                .append(".card b{display:block;font-size:22px}.card span{font-size:12px;color:#6b7280}")
                .append(".bad b{color:#b91c1c}.ok b{color:#15803d}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;font-size:13px}")
                .append("th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #eef0f3}")
                .append("th{background:#fafbfc;color:#6b7280}")
                .append("</style></head><body>");
        html.append("<h1>").append(escape(label)).append("</h1><p class=\"meta\">").append(cases.size())
                .append(" case(s) · <code>").append(escape(pattern)).append("</code></p><div class=\"cards\">");
        card(html, "passed", passed, "ok");
        card(html, "failed", failed, failed > 0 ? "bad" : "");
        card(html, "skipped", skipped, "");
        html.append("</div><table><tr><th>File</th><th>Case</th><th>Verdict</th><th>Detail</th></tr>");
        Map<String, Integer> shown = new LinkedHashMap<>();
        for (Case test : cases) {
            if (shown.merge(test.verdict(), 1, Integer::sum) > 300) {
                continue;
            }
            html.append("<tr><td>").append(escape(test.file())).append("</td><td>").append(escape(test.name()))
                    .append("</td><td>").append(escape(test.verdict())).append("</td><td>")
                    .append(escape(test.detail())).append("</td></tr>");
        }
        return html.append("</table></body></html>").toString();
    }

    private static void card(StringBuilder html, String label, int value, String css) {
        html.append("<div class=\"card ").append(css).append("\"><b>").append(value).append("</b><span>")
                .append(label).append("</span></div>");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record Case(String file, String name, String verdict, String detail) {}
}
