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
package se.hirt.mnemic.model;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The vendor plug-in point, loaded through {@link ServiceLoader} from
 * {@code META-INF/services/se.hirt.mnemic.model.ModelProvider}. A provider claims one or more
 * {@link ModelSpec#provider()} names and builds a {@link ChatModel} for a spec. Adding a vendor is one class with a
 * public no-arg constructor and one line in a services file; nothing else changes. The server ships only the key-free
 * OpenAI-compatible provider (LM Studio, Ollama); vendor SDKs live in the benchmark harness.
 * <p>
 * Native image: GraalVM's {@code ServiceLoaderFeature} registers every provider whose service interface is reachable,
 * but Quarkus turns that feature off unless {@code quarkus.native.auto-service-loader-registration=true} is set (see
 * {@code application.properties}); the native sanity test asserts the providers are found in the image.
 */
public interface ModelProvider {

	/** The provider names this implementation answers to, lowercase, e.g. {@code ["openai", "lmstudio"]}. */
	List<String> names();

	ChatModel create(ModelSpec spec);

	/** Every provider name available in this process, in discovery order; empty when nothing is registered. */
	static List<String> available() {
		var names = new ArrayList<String>();
		for (ModelProvider p : ServiceLoader.load(ModelProvider.class)) {
			names.addAll(p.names());
		}
		return names;
	}

	static ChatModel resolve(ModelSpec spec) {
		for (ModelProvider p : ServiceLoader.load(ModelProvider.class)) {
			if (p.names().contains(spec.provider())) {
				return p.create(spec);
			}
		}
		throw new IllegalArgumentException("No model provider named '" + spec.provider() + "'. Known: " + available());
	}

	static ChatModel resolve(String specText, String apiKeyEnvOverride) {
		return resolve(ModelSpec.parse(specText, apiKeyEnvOverride));
	}
}
