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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity test for the native binary (PLAN.md M0 gate, DECISIONS.md P1): starts the executable against a temporary data
 * home, performs the legacy MCP handshake over STDIO, lists tools, and round-trips remember/recall, which exercises
 * SQLite, migrations and FTS5 inside the image. A second test probes the 2026-07-28 stateless {@code server/discover}
 * so the dual-era requirement (DECISIONS.md §3.1) is checked on every release build.
 * <p>
 * Skipped unless {@code native.image.path} is set, e.g.
 * {@code mvn test-compile failsafe:integration-test -Dnative.image.path=target/mnemic-server-0.1.0-runner.exe}.
 */
class NativeImageSanityIT {

	@Test
	@EnabledIfSystemProperty(named = "native.image.path", matches = ".+")
	void nativeBinaryRespondsToLegacyMcp() throws Exception {
		Process process = start();
		try {
			OutputStream stdin = process.getOutputStream();
			InputStream stdout = process.getInputStream();

			send(stdin,
					"{\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}," + "\"clientInfo\":{\"name\":\"sanity-test\",\"version\":\"1.0\"}},\"jsonrpc\":\"2.0\",\"id\":0}");
			String init = readResponse(stdout, 15_000);
			assertNotNull(init, "No initialize response");
			assertTrue(init.contains("\"serverInfo\""), init);

			send(stdin, "{\"method\":\"notifications/initialized\",\"jsonrpc\":\"2.0\"}");
			send(stdin, "{\"method\":\"tools/list\",\"params\":{},\"jsonrpc\":\"2.0\",\"id\":1}");
			String tools = readResponse(stdout, 15_000);
			assertNotNull(tools, "No tools/list response");
			assertTrue(tools.contains("\"remember\""), tools);
			assertTrue(tools.contains("\"recall\""), tools);

			send(stdin,
					"{\"method\":\"tools/call\",\"params\":{\"name\":\"remember\",\"arguments\":{\"text\":" + "\"I joined Hooli in 2018 as a director of engineering.\"}},\"jsonrpc\":\"2.0\",\"id\":2}");
			String remembered = readResponse(stdout, 20_000);
			assertNotNull(remembered, "No remember response");
			assertTrue(remembered.contains("obs-1"), remembered);

			// Lexical recall in M0: the query must share a term with the observation (A1 uses "work"; this one "Hooli").
			send(stdin,
					"{\"method\":\"tools/call\",\"params\":{\"name\":\"recall\",\"arguments\":{\"query\":" + "\"when did Mattias join Hooli\"}},\"jsonrpc\":\"2.0\",\"id\":3}");
			String recalled = readResponse(stdout, 20_000);
			assertNotNull(recalled, "No recall response");
			assertTrue(recalled.contains("Hooli"), recalled);

			// A proposal must convert at the MCP boundary (plain object schema, no $ref) and construct inside the
			// image (reflection metadata on the records). Both failed once against a real client (2026-09-09).
			assertFalse(tools.contains("$ref"), "tool schemas must not use $ref: " + tools);
			send(stdin, "{\"method\":\"tools/call\",\"params\":{\"name\":\"remember\",\"arguments\":{\"text\":"
					+ "\"I moved to Zürich in 2014.\",\"proposal\":{\"entities\":[{\"ref\":\"e1\",\"name\":\"Zürich\","
					+ "\"type\":\"place\"}],\"facts\":[{\"subject\":\"self\",\"predicate\":\"lives_in\","
					+ "\"object\":\"e1\",\"valid_time\":{\"start\":\"2014\"}}]}}},\"jsonrpc\":\"2.0\",\"id\":5}");
			String proposed = readResponse(stdout, 20_000);
			assertNotNull(proposed, "No response to remember with a proposal");
			assertTrue(proposed.contains("\"predicate\":\"lives_in\"") || proposed.contains("lives_in"),
					"the proposal did not become a fact: " + proposed);
			assertFalse(proposed.contains("\"isError\":true"), proposed);
			send(stdin, "{\"method\":\"tools/call\",\"params\":{\"name\":\"remember\",\"arguments\":{\"text\":"
					+ "\"Yes, that one.\",\"resolve\":[{\"question_id\":\"q-99\",\"choice\":\"new\"}]}},"
					+ "\"jsonrpc\":\"2.0\",\"id\":6}");
			String resolved = readResponse(stdout, 20_000);
			assertNotNull(resolved, "No response to remember with resolve");
			assertTrue(resolved.contains("NOT_FOUND"), "resolve must reach the engine (no such question): " + resolved);

			// ServiceLoader inside the image (application.properties, auto-service-loader-registration).
			send(stdin,
					"{\"method\":\"tools/call\",\"params\":{\"name\":\"status\",\"arguments\":{}}," + "\"jsonrpc\":\"2.0\",\"id\":4}");
			String status = readResponse(stdout, 20_000);
			assertNotNull(status, "No status response");
			assertTrue(status.contains("openai-compatible"), "ModelProvider not found by ServiceLoader: " + status);
		} finally {
			process.destroyForcibly();
			process.waitFor();
		}
	}

	@Test
	@EnabledIfSystemProperty(named = "native.image.path", matches = ".+")
	void nativeBinaryAnswersServerDiscover() throws Exception {
		Process process = start();
		try {
			OutputStream stdin = process.getOutputStream();
			InputStream stdout = process.getInputStream();
			send(stdin,
					"{\"method\":\"server/discover\",\"params\":{\"_meta\":{" + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"," + "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"sanity-test\",\"version\":\"1.0\"}," + "\"io.modelcontextprotocol/clientCapabilities\":{}}},\"jsonrpc\":\"2.0\",\"id\":0}");
			String discover = readResponse(stdout, 15_000);
			assertNotNull(discover, "No server/discover response");
			assertTrue(discover.contains("supportedVersions") || discover.contains("2026-07-28"), discover);
		} finally {
			process.destroyForcibly();
			process.waitFor();
		}
	}

	/** P2: sqlite-vec loads inside the native image. Runs when MNEMIC_VEC_LIBRARY names the loadable library. */
	@Test
	void nativeBinaryLoadsSqliteVec() throws Exception {
		String lib = System.getenv("MNEMIC_VEC_LIBRARY");
		org.junit.jupiter.api.Assumptions.assumeTrue(lib != null && Files.exists(Path.of(lib)),
				"MNEMIC_VEC_LIBRARY not set: sqlite-vec prototype not exercised");
		Process process = start("-Dmnemic.vec-library=" + lib);
		try {
			OutputStream stdin = process.getOutputStream();
			InputStream stdout = process.getInputStream();
			send(stdin,
					"{\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}," + "\"clientInfo\":{\"name\":\"sanity-test\",\"version\":\"1.0\"}},\"jsonrpc\":\"2.0\",\"id\":0}");
			assertNotNull(readResponse(stdout, 15_000), "No initialize response");
			send(stdin, "{\"method\":\"notifications/initialized\",\"jsonrpc\":\"2.0\"}");
			send(stdin, "{\"method\":\"tools/call\",\"params\":{\"name\":\"status\",\"arguments\":{}},\"jsonrpc\":\"2.0\",\"id\":1}");
			String status = readResponse(stdout, 20_000);
			assertNotNull(status, "No status response");
			// The tool result is JSON inside JSON, so the quotes arrive escaped.
			assertTrue(status.contains("vec") && status.contains("v0.1."), "sqlite-vec did not load in the native image: " + status);
		} finally {
			process.destroyForcibly();
			process.waitFor();
		}
	}

	/** P3: foreign downcalls into ONNX Runtime inside the native image. Runs when MNEMIC_ORT_LIBRARY names the library. */
	@Test
	void nativeBinaryCallsOnnxRuntime() throws Exception {
		String lib = System.getenv("MNEMIC_ORT_LIBRARY");
		org.junit.jupiter.api.Assumptions.assumeTrue(lib != null && Files.exists(Path.of(lib)),
				"MNEMIC_ORT_LIBRARY not set: ONNX Runtime prototype not exercised");
		Process process = start("-Dmnemic.ort-library=" + lib);
		try {
			OutputStream stdin = process.getOutputStream();
			InputStream stdout = process.getInputStream();
			send(stdin,
					"{\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}," + "\"clientInfo\":{\"name\":\"sanity-test\",\"version\":\"1.0\"}},\"jsonrpc\":\"2.0\",\"id\":0}");
			assertNotNull(readResponse(stdout, 15_000), "No initialize response");
			send(stdin, "{\"method\":\"notifications/initialized\",\"jsonrpc\":\"2.0\"}");
			send(stdin, "{\"method\":\"tools/call\",\"params\":{\"name\":\"status\",\"arguments\":{}},\"jsonrpc\":\"2.0\",\"id\":1}");
			String status = readResponse(stdout, 20_000);
			assertNotNull(status, "No status response");
			assertTrue(status.contains("onnxruntime 1.") && status.contains("available"),
					"the downcalls did not work in the native image: " + status);
		} finally {
			process.destroyForcibly();
			process.waitFor();
		}
	}

	/** M4: the embedder runs inside the native image. Needs MNEMIC_ORT_LIBRARY and MNEMIC_EMBED_MODEL. */
	@Test
	void nativeBinaryEmbedsText() throws Exception {
		String lib = System.getenv("MNEMIC_ORT_LIBRARY");
		String model = System.getenv("MNEMIC_EMBED_MODEL");
		org.junit.jupiter.api.Assumptions.assumeTrue(lib != null && Files.exists(Path.of(lib)) && model != null
				&& Files.exists(Path.of(model, "model.onnx")), "MNEMIC_ORT_LIBRARY / MNEMIC_EMBED_MODEL not set");
		Process process = start("-Dmnemic.ort-library=" + lib, "-Dmnemic.embed-model=" + model);
		try {
			OutputStream stdin = process.getOutputStream();
			InputStream stdout = process.getInputStream();
			send(stdin,
					"{\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}," + "\"clientInfo\":{\"name\":\"sanity-test\",\"version\":\"1.0\"}},\"jsonrpc\":\"2.0\",\"id\":0}");
			assertNotNull(readResponse(stdout, 15_000), "No initialize response");
			send(stdin, "{\"method\":\"notifications/initialized\",\"jsonrpc\":\"2.0\"}");
			// The model loads in the background; the server answers at once and status says where the loader is.
			String status = null;
			long deadline = System.currentTimeMillis() + 60_000;
			for (int id = 1; System.currentTimeMillis() < deadline; id++) {
				send(stdin, "{\"method\":\"tools/call\",\"params\":{\"name\":\"status\",\"arguments\":{}},\"jsonrpc\":\"2.0\",\"id\":" + id + "}");
				status = readResponse(stdout, 30_000);
				assertNotNull(status, "No status response");
				if (status.contains("dims") && status.contains("384")) {
					break;
				}
				assertTrue(!status.contains("\\\"state\\\":\\\"failed\\\""), "the embedder failed in the native image: " + status);
				Thread.sleep(500);
			}
			assertTrue(status != null && status.contains("dims") && status.contains("384"), "the embedder did not run in the native image: " + status);
			System.out.println(status);
		} finally {
			process.destroyForcibly();
			process.waitFor();
		}
	}

	/**
	 * First use inside the image: nothing on disk, a local mirror of the runtime jar and the model, the server
	 * answering at once, and the channel alive once the fetch lands; the runtime library comes out of the image
	 * itself, nothing executable is fetched. Needs MNEMIC_EMBED_MIRROR (a copy of the model's repository files).
	 */
	@Test
	void nativeBinaryFetchesTheModelOnFirstUse() throws Exception {
		String mirror = System.getenv("MNEMIC_EMBED_MIRROR");
		org.junit.jupiter.api.Assumptions.assumeTrue(mirror != null && Files.exists(Path.of(mirror, "tokenizer.json")),
				"MNEMIC_EMBED_MIRROR not set");
		Path models = Files.createTempDirectory("mnemic-native-models");
		Process process = start("-Dmnemic.embed=auto", "-Dmnemic.models-dir=" + models.toAbsolutePath(),
				"-Dmnemic.embed-model-url=" + Path.of(mirror).toUri());
		try {
			OutputStream stdin = process.getOutputStream();
			InputStream stdout = process.getInputStream();
			send(stdin,
					"{\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}," + "\"clientInfo\":{\"name\":\"sanity-test\",\"version\":\"1.0\"}},\"jsonrpc\":\"2.0\",\"id\":0}");
			assertNotNull(readResponse(stdout, 15_000), "No initialize response");
			send(stdin, "{\"method\":\"notifications/initialized\",\"jsonrpc\":\"2.0\"}");
			// Remember works while the fetch runs.
			send(stdin, "{\"method\":\"tools/call\",\"params\":{\"name\":\"remember\",\"arguments\":{\"text\":"
					+ "\"My accounts are at Nordbank, the savings and the salary account.\"}},\"jsonrpc\":\"2.0\",\"id\":1}");
			String remembered = readResponse(stdout, 20_000);
			assertNotNull(remembered, "No remember response");
			assertTrue(remembered.contains("obs-1"), remembered);
			// Poll status until the embedder is ready (the copy from the mirror and the load take a few seconds).
			String status = null;
			long deadline = System.currentTimeMillis() + 180_000;
			int id = 2;
			while (System.currentTimeMillis() < deadline) {
				send(stdin, "{\"method\":\"tools/call\",\"params\":{\"name\":\"status\",\"arguments\":{}},\"jsonrpc\":\"2.0\",\"id\":" + id++ + "}");
				status = readResponse(stdout, 30_000);
				assertNotNull(status, "No status response");
				if (status.contains("\\\"state\\\":\\\"ready\\\"")) {
					break;
				}
				assertTrue(!status.contains("\\\"state\\\":\\\"failed\\\""), "the fetch failed: " + status);
				Thread.sleep(2_000);
			}
			assertTrue(status != null && status.contains("ready"), "embedder never became ready: " + status);
			assertTrue(Files.exists(models.resolve(se.hirt.mnemic.embed.ModelFetcher.MODEL_ID).resolve("model.onnx")), "fetched into the models directory");
			assertTrue(Files.exists(models.resolve("onnxruntime-" + se.hirt.mnemic.embed.OrtLibrary.VERSION)), "the runtime was written out of the image");
			assertTrue(status.contains("\\\"semantic\\\":\\\"on\\\""), "channels report the semantic one on: " + status);
			// The channel is alive: a paraphrase finds the observation.
			send(stdin, "{\"method\":\"tools/call\",\"params\":{\"name\":\"recall\",\"arguments\":{\"query\":\"where do I bank\"}},\"jsonrpc\":\"2.0\",\"id\":" + id + "}");
			String recalled = readResponse(stdout, 30_000);
			assertNotNull(recalled, "No recall response");
			assertTrue(recalled.contains("Nordbank") && recalled.contains("semantic"), recalled);
		} finally {
			process.destroyForcibly();
			process.waitFor();
		}
	}

	private static Process start(String... extra) throws Exception {
		Path binary = Path.of(System.getProperty("native.image.path"));
		assertTrue(Files.exists(binary), "Native binary not found at: " + binary);
		Path home = Files.createTempDirectory("mnemic-native-it");
		var cmd = new java.util.ArrayList<String>(List.of(binary.toAbsolutePath().toString(),
				"-Dmnemic.home=" + home.toAbsolutePath(), "-Dquarkus.mcp.server.stdio.enabled=true",
				"-Dquarkus.log.file.path=" + home.resolve("mnemic.log").toAbsolutePath()));
		// No test fetches 480 MB from the internet by accident: the semantic channel is off unless a test says so,
		// and the server under test never sees this JVM's MNEMIC_* environment (the fetch test must start from nothing).
		if (java.util.Arrays.stream(extra).noneMatch(a -> a.startsWith("-Dmnemic.embed") || a.startsWith("-Dmnemic.ort-library"))) {
			cmd.add("-Dmnemic.embed=off");
		}
		cmd.addAll(List.of(extra));
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.environment().keySet().removeIf(k -> k.startsWith("MNEMIC_"));
		pb.redirectErrorStream(false);
		return pb.start();
	}

	private static void send(OutputStream stdin, String json) throws Exception {
		stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
		stdin.flush();
	}

	private static String readResponse(InputStream stdout, long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		var buf = new java.io.ByteArrayOutputStream();
		while (System.currentTimeMillis() < deadline) {
			if (stdout.available() > 0) {
				int b = stdout.read();
				if (b == -1) {
					break;
				}
				if (b == '\n') {
					String line = buf.toString(StandardCharsets.UTF_8).trim();
					if (!line.isEmpty()) {
						return line;
					}
					buf.reset();
				} else {
					buf.write(b);
				}
			} else {
				Thread.sleep(50);
			}
		}
		String remaining = buf.toString(StandardCharsets.UTF_8).trim();
		return remaining.isEmpty() ? null : remaining;
	}
}
