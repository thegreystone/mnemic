/*
 * Copyright (C) 2026 Marcus Hirt
 *
 * This software is free:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVALUATION.md is the source of the scenario tests. This test parses its {@code ### X1.} headings, scans the test
 * sources for {@code @Scenario("X1")} tags, and writes {@code target/scenario-coverage.txt}: implemented, pending
 * (tagged but {@code @Disabled}), and missing. It fails only when a test names a scenario that no document knows,
 * because that is a stale reference; missing scenarios are expected while milestones are open.
 * <p>
 * Families L–P are the additions DECISIONS.md §5.3 asks for; until they are written into EVALUATION.md they count as
 * planned rather than unknown.
 */
class ScenarioCoverageTest {

	private static final Pattern HEADING = Pattern.compile("(?m)^### ([A-Z]\\d+)\\.");
	private static final Pattern TAG = Pattern.compile("@Scenario\\(\"([A-Z]\\d+)\"\\)");
	private static final Set<String> PLANNED_FAMILIES = Set.of("L", "M", "N", "O", "P");

	@Test
	void everyTaggedScenarioIsKnownAndCoverageIsReported() throws IOException {
		Path doc = Path.of("..", "docs", "EVALUATION.md");
		assertTrue(Files.exists(doc), "expected " + doc.toAbsolutePath());
		var documented = new TreeSet<String>();
		Matcher m = HEADING.matcher(Files.readString(doc, StandardCharsets.UTF_8));
		while (m.find()) {
			documented.add(m.group(1));
		}

		var tagged = new TreeMap<String, String>(); // id -> "implemented" | "pending"
		try (Stream<Path> files = Files.walk(Path.of("src", "test", "java"))) {
			for (Path f : files.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString()
							.equals("ScenarioCoverageTest.java") && !p.getFileName().toString().equals("Scenario.java"))
					.toList()) {
				String src = Files.readString(f, StandardCharsets.UTF_8);
				for (String chunk : src.split("@Test")) {
					Matcher t = TAG.matcher(chunk);
					while (t.find()) {
						String id = t.group(1);
						boolean pending = chunk.contains("@Disabled");
						tagged.merge(id, pending ? "pending" : "implemented",
								(a, b) -> a.equals("implemented") || b.equals("implemented") ? "implemented"
										: "pending");
					}
				}
			}
		}

		var unknown = new TreeSet<String>();
		for (String id : tagged.keySet()) {
			if (!documented.contains(id) && !PLANNED_FAMILIES.contains(id.substring(0, 1))) {
				unknown.add(id);
			}
		}
		var missing = new TreeSet<>(documented);
		missing.removeAll(tagged.keySet());

		var report = new StringBuilder();
		report.append("Scenario coverage against docs/EVALUATION.md\n");
		report.append("documented: ").append(documented.size()).append("  implemented: ")
				.append(tagged.values().stream().filter("implemented"::equals).count()).append("  pending: ")
				.append(tagged.values().stream().filter("pending"::equals).count()).append("  missing: ")
				.append(missing.size()).append('\n');
		tagged.forEach((id, state) -> report.append(state.equals("implemented") ? "  [x] " : "  [~] ").append(id)
				.append(documented.contains(id) ? "" : " (planned family)").append('\n'));
		missing.forEach(id -> report.append("  [ ] ").append(id).append('\n'));
		Files.createDirectories(Path.of("target"));
		Files.writeString(Path.of("target", "scenario-coverage.txt"), report, StandardCharsets.UTF_8);
		System.out.println(report);

		assertTrue(unknown.isEmpty(), "tests reference scenarios that no document defines: " + unknown);
		assertTrue(documented.size() >= 50, "EVALUATION.md parse looks wrong: only " + documented.size() + " ids");
	}

	static List<String> ids(String text) {
		var out = new java.util.ArrayList<String>();
		Matcher m = HEADING.matcher(text);
		while (m.find()) {
			out.add(m.group(1));
		}
		return out;
	}
}
