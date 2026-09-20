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
package se.hirt.mnemic.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A Mnemic server as a client sees it: the process, started over stdio the way Claude Code or Claude Desktop starts it,
 * spoken to in JSON-RPC. The usage bench hands the model exactly the tools this publishes, so what is measured is the
 * surface the assistant meets, not the engine behind it.
 */
final class McpClient implements AutoCloseable {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final Process process;
	private final OutputStream stdin;
	private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
	private final StringBuilder stderr = new StringBuilder();
	private final List<String> unmatched = new ArrayList<>();
	private int nextId = 1;

	private McpClient(Process process) {
		this.process = process;
		this.stdin = process.getOutputStream();
		Thread out = new Thread(() -> pump(process), "mcp-stdout");
		out.setDaemon(true);
		out.start();
		Thread err = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(
					new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					synchronized (stderr) {
						stderr.append(line).append('\n');
					}
				}
			} catch (IOException ignored) {
				// the process went away
			}
		}, "mcp-stderr");
		err.setDaemon(true);
		err.start();
	}

	private void pump(Process p) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (!line.isBlank()) {
					lines.add(line);
				}
			}
		} catch (IOException ignored) {
			// the process went away
		}
	}

	/**
	 * Starts a server on a fresh data home. {@code server} is the runner jar or the native binary; the owner and the
	 * home go in through the environment, as a client's configuration would pass them.
	 */
	static McpClient start(Path server, Path home, String owner, boolean embed) throws Exception {
		var cmd = new ArrayList<String>();
		if (server.toString().endsWith(".jar")) {
			cmd.add("java");
			cmd.add("-jar");
		}
		cmd.add(server.toAbsolutePath().toString());
		cmd.add("-Dquarkus.mcp.server.stdio.enabled=true");
		cmd.add("-Dquarkus.log.file.path=" + home.resolve("mnemic.log").toAbsolutePath());
		if (!embed) {
			cmd.add("-Dmnemic.embed=off");
		}
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.environment().keySet().removeIf(k -> k.startsWith("MNEMIC_"));
		pb.environment().put("MNEMIC_HOME", home.toAbsolutePath().toString());
		pb.environment().put("MNEMIC_OWNER", owner);
		McpClient client = new McpClient(pb.start());
		JsonNode init = client.request("initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
				"clientInfo", Map.of("name", "mnemic-usage-bench", "version", "1")), 90_000);
		if (init == null) {
			throw new IllegalStateException("the server did not answer initialize:\n" + client.stderr());
		}
		client.notify("notifications/initialized");
		return client;
	}

	/** The tools the server publishes: name, description, and input schema, as the assistant would see them. */
	List<Map<String, Object>> listTools() throws Exception {
		JsonNode r = request("tools/list", Map.of(), 30_000);
		var out = new ArrayList<Map<String, Object>>();
		if (r == null || r.get("result") == null) {
			return out;
		}
		for (JsonNode t : r.get("result").get("tools")) {
			var m = new LinkedHashMap<String, Object>();
			m.put("name", t.get("name").asText());
			m.put("description", t.path("description").asText(""));
			m.put("input_schema", JSON.convertValue(t.get("inputSchema"), Map.class));
			out.add(m);
		}
		return out;
	}

	/** Calls a tool and returns what it said, its text parts joined; an error reply is returned prefixed. */
	String call(String name, Map<String, Object> arguments) throws Exception {
		JsonNode r = request("tools/call", Map.of("name", name, "arguments", arguments == null ? Map.of() : arguments),
				120_000);
		if (r == null) {
			return "ERROR: no reply from the server within two minutes";
		}
		if (r.get("error") != null) {
			return "ERROR: " + r.get("error").path("message").asText(r.get("error").toString());
		}
		JsonNode result = r.get("result");
		var sb = new StringBuilder();
		if (result != null && result.get("content") != null) {
			for (JsonNode c : result.get("content")) {
				if (c.has("text")) {
					sb.append(c.get("text").asText());
				}
			}
		}
		boolean error = result != null && result.path("isError").asBoolean(false);
		return (error ? "ERROR: " : "") + sb;
	}

	private synchronized JsonNode request(String method, Map<String, Object> params, long timeoutMs) throws Exception {
		int id = nextId++;
		var msg = new LinkedHashMap<String, Object>();
		msg.put("jsonrpc", "2.0");
		msg.put("id", id);
		msg.put("method", method);
		msg.put("params", params);
		stdin.write((JSON.writeValueAsString(msg) + "\n").getBytes(StandardCharsets.UTF_8));
		stdin.flush();
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (true) {
			long left = deadline - System.currentTimeMillis();
			if (left <= 0) {
				return null;
			}
			String line = lines.poll(left, TimeUnit.MILLISECONDS);
			if (line == null) {
				return null;
			}
			JsonNode node;
			try {
				node = JSON.readTree(line);
			} catch (IOException e) {
				continue; // not JSON: a stray line on stdout
			}
			if (node.has("id") && node.get("id").asInt() == id) {
				return node;
			}
			unmatched.add(line);
		}
	}

	private void notify(String method) throws IOException {
		stdin.write((JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "method", method)) + "\n")
				.getBytes(StandardCharsets.UTF_8));
		stdin.flush();
	}

	String stderr() {
		synchronized (stderr) {
			return stderr.toString();
		}
	}

	@Override
	public void close() {
		process.destroy();
		try {
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}
}
