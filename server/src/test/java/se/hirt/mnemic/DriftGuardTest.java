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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import se.hirt.mnemic.protocol.Protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against silent drift across duplicated protocol text, hand-pinned plugin release versions, and version/schema
 * numbers recorded in {@code docs/DESIGN.md}.
 */
class DriftGuardTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static Path repoRoot() {
		Path dir = Path.of("").toAbsolutePath();
		for (int i = 0; i < 6 && dir != null; i++) {
			if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("plugin"))
					&& Files.isDirectory(dir.resolve("plugin-codex"))) {
				return dir;
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("Could not locate repository root from " + Path.of("").toAbsolutePath());
	}

	private static String readNormalized(Path file) throws IOException {
		return Files.readString(file).replace("\r\n", "\n");
	}

	private static String xmlTag(String xml, String tag) {
		Matcher m = Pattern.compile("<" + Pattern.quote(tag) + ">([^<]+)</" + Pattern.quote(tag) + ">").matcher(xml);
		assertTrue(m.find(), "Expected <" + tag + "> in POM");
		return m.group(1).trim();
	}

	@Test
	void pluginSkillsEmbedProtocolInstructionsVerbatim() throws IOException {
		Path root = repoRoot();
		String canonical = Protocol.instructions();
		assertEquals(readNormalized(root.resolve("server/src/main/resources/protocol/instructions.md")).strip(),
				canonical, "Protocol.instructions() should match instructions.md");

		for (String rel : List.of("plugin/skills/mnemic/SKILL.md", "plugin-codex/skills/mnemic/SKILL.md")) {
			String skill = readNormalized(root.resolve(rel));
			assertTrue(skill.contains(canonical),
					rel + " has drifted from server/src/main/resources/protocol/instructions.md; keep the "
							+ "'# Mnemic memory protocol' block verbatim (put client-specific notes outside it).");
		}
	}

	@Test
	void pluginReleaseVersionsAreConsistentWithEachOtherAndPom() throws IOException {
		Path root = repoRoot();

		JsonNode claudePlugin = MAPPER.readTree(root.resolve("plugin/.claude-plugin/plugin.json").toFile());
		String mcpServersUrl = claudePlugin.path("mcpServers").asText("");
		Matcher urlMatch = Pattern
				.compile("/releases/download/v(\\d+\\.\\d+\\.\\d+)/mnemic-(\\d+\\.\\d+\\.\\d+)-universal\\.mcpb$")
				.matcher(mcpServersUrl);
		assertTrue(urlMatch.find(), "Unexpected mcpServers URL in plugin/.claude-plugin/plugin.json: " + mcpServersUrl);
		assertEquals(urlMatch.group(1), urlMatch.group(2),
				"Tag and artifact version disagree in plugin/.claude-plugin/plugin.json: " + mcpServersUrl);
		String claudeVersion = urlMatch.group(1);

		JsonNode codexPlugin = MAPPER.readTree(root.resolve("plugin-codex/plugin.json").toFile());
		String codexPluginVersion = codexPlugin.path("version").asText("");
		assertEquals(claudeVersion, codexPluginVersion,
				"plugin-codex/plugin.json version disagrees with plugin/.claude-plugin/plugin.json");

		String launcher = readNormalized(root.resolve("plugin-codex/scripts/mnemic.mjs"));
		Matcher launcherMatch = Pattern.compile("RELEASE_VERSION\\s*=\\s*'(\\d+\\.\\d+\\.\\d+)'").matcher(launcher);
		assertTrue(launcherMatch.find(), "Expected RELEASE_VERSION constant in plugin-codex/scripts/mnemic.mjs");
		String launcherVersion = launcherMatch.group(1);
		assertEquals(claudeVersion, launcherVersion,
				"plugin-codex/scripts/mnemic.mjs RELEASE_VERSION disagrees with plugin manifests");

		String pom = readNormalized(root.resolve("pom.xml"));
		String revision = xmlTag(pom, "revision");
		if (revision.endsWith("-SNAPSHOT")) {
			String base = revision.substring(0, revision.length() - "-SNAPSHOT".length());
			assertTrue(compareSemver(claudeVersion, base) <= 0,
					"Plugin release version (" + claudeVersion + ") is ahead of pom.xml revision (" + revision + ")");
		} else {
			assertEquals(revision, claudeVersion, "Release pom.xml revision (" + revision
					+ ") must match pinned plugin versions (" + claudeVersion + ")");
		}
	}

	@Test
	void designDocMatchesActualSchemaAndDependencyVersions() throws IOException {
		Path root = repoRoot();
		String design = readNormalized(root.resolve("docs/DESIGN.md"));
		String rootPom = readNormalized(root.resolve("pom.xml"));
		String serverPom = readNormalized(root.resolve("server/pom.xml"));

		long migrations;
		try (Stream<Path> s = Files.list(root.resolve("server/src/main/resources/db/migration"))) {
			migrations = s.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql")).count();
		}
		assertTrue(migrations > 0, "No migrations found");
		assertTrue(design.contains("(schema " + migrations + ")"),
				"docs/DESIGN.md should cite (schema " + migrations + ")");
		assertTrue(design.contains("(" + migrations + " migrations)"),
				"docs/DESIGN.md should cite (" + migrations + " migrations)");

		String quarkusVersion = xmlTag(rootPom, "quarkus.platform.version");
		String mcpVersion = xmlTag(rootPom, "quarkus-mcp-server.version");
		String sqliteVersion = xmlTag(rootPom, "sqlite-jdbc.version");
		String ortVersion = xmlTag(serverPom, "mnemic.ort.version");

		assertNotNull(quarkusVersion);
		assertTrue(design.contains("Quarkus " + quarkusVersion),
				"docs/DESIGN.md should cite Quarkus " + quarkusVersion);
		assertTrue(design.contains("`quarkus-mcp-server-stdio` " + mcpVersion),
				"docs/DESIGN.md should cite quarkus-mcp-server-stdio " + mcpVersion);
		assertTrue(design.contains("`sqlite-jdbc` " + sqliteVersion),
				"docs/DESIGN.md should cite sqlite-jdbc " + sqliteVersion);
		assertTrue(design.contains("ONNX Runtime " + ortVersion),
				"docs/DESIGN.md should cite ONNX Runtime " + ortVersion);
	}

	private static int compareSemver(String a, String b) {
		String[] pa = a.split("\\.");
		String[] pb = b.split("\\.");
		for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
			int na = i < pa.length ? Integer.parseInt(pa[i]) : 0;
			int nb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
			if (na != nb) {
				return Integer.compare(na, nb);
			}
		}
		return 0;
	}
}
