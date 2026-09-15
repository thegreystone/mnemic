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

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@code mnemic.*} configuration; see {@code application.properties} for defaults and the data-home rule. */
@ConfigMapping(prefix = "mnemic")
public interface MnemicConfig {

	/** The data home: one SQLite file plus the log. */
	String home();

	/** The owner entity name, set at init; first-person references resolve to it (EXTRACTION.md, The owner). */
	Optional<String> owner();

	/**
	 * The owner's other names, seeded as aliases of the owner entity at init so that a nickname, a surname, an
	 * e-mail address, or a code-hosting handle in a query or a proposal resolves to the owner. All optional; a
	 * store used for code work wants the handle and the addresses commits and issues carry.
	 */
	Optional<List<String>> ownerAliases();

	Optional<List<String>> ownerEmails();

	Optional<String> ownerGithub();

	/** Other handles, with or without a leading @ ("@alice_example", "slack:alice"). */
	Optional<List<String>> ownerHandles();

	/** Every configured identity of the owner, in one list: aliases, e-mail addresses, the GitHub handle, handles. */
	default List<String> ownerIdentity() {
		var out = new ArrayList<String>();
		ownerAliases().ifPresent(out::addAll);
		ownerEmails().ifPresent(out::addAll);
		ownerGithub().ifPresent(out::add);
		ownerHandles().ifPresent(out::addAll);
		return out;
	}

	/**
	 * The sqlite-vec loadable library (vec0.dll / vec0.so / vec0.dylib). When set, the store loads it at open and
	 * {@code status} reports {@code vec}; when unset, vectors are scanned. Env: MNEMIC_VEC_LIBRARY.
	 */
	Optional<String> vecLibrary();

	/** The ONNX Runtime shared library; when set, {@code status} reports what the probe found. Env: MNEMIC_ORT_LIBRARY. */
	Optional<String> ortLibrary();

	/** A directory holding the embedding model (model.onnx, tokenizer.json). Env: MNEMIC_EMBED_MODEL. */
	Optional<String> embedModel();

	/**
	 * Semantic recall: {@code auto} (default) fetches the runtime and the model on first use into the models
	 * directory and loads them in the background; {@code off} disables the channel; Env: MNEMIC_EMBED.
	 */
	@WithDefault("auto")
	String embed();

	/** Where fetched models live, shared by every data home: {@code ~/.mnemic/models}. Env: MNEMIC_MODELS_DIR. */
	Optional<String> modelsDir();

	/** A mirror laid out like the model's Hugging Face repository, in place of the published one. Env: MNEMIC_EMBED_MODEL_URL. */
	Optional<String> embedModelUrl();

	/** The language of the fact layer: en (default) or de. Observations stay verbatim. Env: MNEMIC_LANGUAGE. */
	Optional<String> language();

	Observation observation();

	Proposer proposer();

	interface Observation {
		@WithDefault("4000")
		int softLimitChars();
	}

	/**
	 * Hybrid mode (README): a model the server asks for a proposal when the assistant sent none. {@code model} is
	 * {@code provider:model[@endpoint]} as in the bench; unset means the assistant proposes, as before.
	 */
	interface Proposer {
		Optional<String> model();

		@WithDefault("sync")
		String mode();

		Optional<String> apiKeyEnv();

		@WithDefault("20")
		int batch();
	}
}
