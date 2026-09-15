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
package se.hirt.mnemic.proposal;

import se.hirt.mnemic.model.ChatModel;
import se.hirt.mnemic.model.ModelProvider;
import se.hirt.mnemic.model.ModelSpec;
import se.hirt.mnemic.protocol.MnemicException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

/**
 * The hybrid mode (README, "Hybrid mode"): a model the server is configured with reads an observation that
 * arrived without a proposal and proposes in the assistant's place, under the same extraction spec and the same
 * validation. Nothing here is trusted more than an assistant's proposal; it is provenance ({@code proposer} on
 * the observation) and a different origin, that is all. A failure is a warning on the observation, never an
 * error: the observation is stored regardless, as EXTRACTION.md requires.
 */
public final class ModelProposer {

	/** {@code sync}: propose inside {@code remember}. {@code deferred}: leave it to {@code consolidate}. */
	public enum Mode {
		SYNC, DEFERRED;

		public static Mode parse(String s) {
			if (s == null || s.isBlank()) {
				return SYNC;
			}
			return switch (s.trim().toLowerCase(Locale.ROOT)) {
				case "sync" -> SYNC;
				case "deferred", "async", "consolidate" -> DEFERRED;
				default -> throw MnemicException.invalidArgument("mnemic.proposer.mode must be sync or deferred, not '"
						+ s + "'.");
			};
		}
	}

	/** Either a proposal or the reason there is none; never both null. */
	public record Result(Proposal proposal, String warning) {
		public boolean ok() {
			return proposal != null;
		}
	}

	private static final String SPEC = loadSpec();

	private final ChatModel model;
	private final Mode mode;

	public ModelProposer(ChatModel model, Mode mode) {
		this.model = model;
		this.mode = mode;
	}

	/** Resolves {@code provider:model[@endpoint]} through the SPI; the key comes from the environment. */
	public static ModelProposer configure(String spec, String apiKeyEnv, Mode mode) {
		return new ModelProposer(ModelProvider.resolve(ModelSpec.parse(spec, apiKeyEnv)), mode);
	}

	public String id() {
		return model.id();
	}

	public Mode mode() {
		return mode;
	}

	public Result propose(String text, Instant observedAt) {
		String user = userMessage(text, observedAt.toString().substring(0, 10));
		String reply;
		try {
			reply = model.chat(SPEC, user);
		} catch (IOException e) {
			return new Result(null, "The configured proposer " + model.id() + " could not be reached: "
					+ firstLine(e.getMessage()) + ". The observation is stored without a proposal; consolidate will "
					+ "try again.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Result(null, "Interrupted while waiting for the configured proposer " + model.id() + ".");
		} catch (RuntimeException e) {
			return new Result(null, "The configured proposer " + model.id() + " failed: " + firstLine(e.getMessage())
					+ ". The observation is stored without a proposal.");
		}
		try {
			return new Result(Proposal.parse(extractJson(reply)), null);
		} catch (MnemicException e) {
			return new Result(null, "The configured proposer " + model.id() + " returned something that is not a "
					+ "proposal: " + firstLine(e.getMessage()) + ". The observation is stored without one.");
		}
	}

	/**
	 * The user turn sent with the spec. The benchmark's proposal cache keys on it, so a change here invalidates
	 * every cached reply.
	 */
	public static String userMessage(String observation, String observedAt) {
		return "Observation date: " + observedAt + "\n\nObservation:\n\"\"\"\n" + observation + "\n\"\"\"\n\n"
				+ "Reply with the JSON object only.";
	}

	/** Strips code fences and takes the outermost object. */
	public static String extractJson(String reply) {
		String s = reply == null ? "" : reply.trim();
		if (s.startsWith("```")) {
			int nl = s.indexOf('\n');
			s = nl >= 0 ? s.substring(nl + 1) : s;
			int fence = s.lastIndexOf("```");
			if (fence >= 0) {
				s = s.substring(0, fence);
			}
		}
		int start = s.indexOf('{');
		int end = s.lastIndexOf('}');
		return start >= 0 && end > start ? s.substring(start, end + 1) : s;
	}

	private static String firstLine(String s) {
		if (s == null) {
			return "no message";
		}
		int nl = s.indexOf('\n');
		String line = nl >= 0 ? s.substring(0, nl) : s;
		return line.length() > 200 ? line.substring(0, 200) + "…" : line;
	}

	public static String spec() {
		return SPEC;
	}

	private static String loadSpec() {
		try (InputStream in = ModelProposer.class.getClassLoader().getResourceAsStream("protocol/extraction-spec.md")) {
			if (in == null) {
				throw new IllegalStateException("protocol/extraction-spec.md missing from the server resources");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
