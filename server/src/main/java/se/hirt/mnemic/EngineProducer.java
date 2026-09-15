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

import se.hirt.mnemic.embed.Embedder;
import se.hirt.mnemic.embed.EmbedderHolder;
import se.hirt.mnemic.embed.ModelFetcher;
import se.hirt.mnemic.embed.OrtLibrary;
import se.hirt.mnemic.knowledge.Lang;
import se.hirt.mnemic.proposal.ModelProposer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.List;

/** CDI wiring: one {@link Engine} per process, opened on the configured data home. */
@ApplicationScoped
public class EngineProducer {

	private static final Logger LOG = Logger.getLogger(EngineProducer.class);

	private volatile EmbedderHolder holder;
	private volatile Engine engine;

	@Produces
	@Singleton
	Engine engine(MnemicConfig config, @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
	String version) {
		Path home = Path.of(config.home());
		LOG.infof("Opening Mnemic data home at %s", home.toAbsolutePath());
		ModelProposer proposer = null;
		if (config.proposer().model().isPresent()) {
			String spec = config.proposer().model().get();
			try {
				proposer = ModelProposer.configure(spec, config.proposer().apiKeyEnv().orElse(null),
						ModelProposer.Mode.parse(config.proposer().mode()));
				LOG.infof("Hybrid mode: %s proposes (%s) when the assistant does not", proposer.id(), proposer.mode());
			} catch (RuntimeException e) {
				// A misconfigured proposer must not take the memory down with it; status reports the absence.
				LOG.errorf("mnemic.proposer.model=%s could not be set up: %s. Running without a server proposer.", spec,
						e.getMessage());
			}
		}
		holder = embedderHolder(config);
		Engine.Options options = Engine.Options.of(home, version).withSoftLimit(config.observation().softLimitChars())
				.withOwner(config.owner().orElse(null), config.ownerIdentity())
				.withProposer(proposer, config.proposer().batch()).withVecLibrary(config.vecLibrary().orElse(null))
				.withEmbedder(holder).withLang(Lang.of(config.language().orElse("en")));
		engine = new Engine(options);
		return engine;
	}

	/**
	 * The semantic channel's embedder, never in the way of the start: an explicit model and runtime are loaded in the
	 * background; with none configured and {@code mnemic.embed=auto}, the runtime library comes out of the build
	 * ({@link OrtLibrary}) and the model is fetched on first use into the models directory (pinned by hash) and loaded
	 * when it is there; {@code off} keeps the channel out. Whatever happens, the engine answers meanwhile without it.
	 */
	private EmbedderHolder embedderHolder(MnemicConfig config) {
		if ("off".equalsIgnoreCase(config.embed())) {
			LOG.info("Semantic recall is off (mnemic.embed=off)");
			return EmbedderHolder.off("mnemic.embed=off");
		}
		Path modelsDir = config.modelsDir().map(Path::of).orElse(ModelFetcher.defaultModelsDir());
		try {
			if (config.ortLibrary().isPresent() && config.embedModel().isPresent()) {
				Path dir = Path.of(config.embedModel().get());
				Path library = Path.of(config.ortLibrary().get());
				return new EmbedderHolder(modelsDir, List.of(), () -> library, dir, dir.getFileName().toString(),
						this::backfill);
			}
			List<ModelFetcher.Item> plan = ModelFetcher.plan(modelsDir, config.embedModelUrl().orElse(null));
			Path modelDir = plan.get(0).target().getParent();
			if (!ModelFetcher.complete(plan)) {
				LOG.infof(
						"Semantic recall: fetching the embedding model (%d MB) into %s in the background; "
								+ "status reports progress, MNEMIC_EMBED=off disables it",
						ModelFetcher.totalBytes(plan) >> 20, modelsDir);
			}
			EmbedderHolder.LibrarySource library = config.ortLibrary().isPresent()
					? () -> Path.of(config.ortLibrary().get()) : () -> OrtLibrary.install(modelsDir);
			return new EmbedderHolder(modelsDir, plan, library, modelDir, ModelFetcher.MODEL_ID, this::backfill);
		} catch (RuntimeException e) {
			LOG.errorf("Semantic recall unavailable: %s", e.getMessage());
			return EmbedderHolder.off(e.getMessage());
		}
	}

	/** Once the embedder is there, what was stored before it gets its vectors, a batch at a time. */
	private void backfill(Embedder embedder) {
		Engine e = engine;
		if (e == null) {
			return;
		}
		try {
			int stale = e.vectors().dropOtherModels(embedder.id());
			if (stale > 0) {
				LOG.infof("Semantic recall: dropped %d vectors of another model; re-embedding with %s", stale,
						embedder.id());
			}
			int total = 0;
			int n;
			while ((n = e.embedMissing(200)) > 0) {
				total += n;
			}
			if (total > 0) {
				LOG.infof("Semantic recall: embedded %d stored rows with %s", total, embedder.id());
			}
		} catch (RuntimeException ex) {
			LOG.warnf("Backfill of vectors stopped: %s", ex.getMessage());
		}
	}

	void close(@Disposes
	Engine engine) {
		engine.close();
		if (holder != null) {
			holder.close();
		}
	}
}
