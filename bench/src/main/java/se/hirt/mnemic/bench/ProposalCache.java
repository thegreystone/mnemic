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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Raw model replies keyed by what produced them: model id, extraction spec, and the exact user message. A proposal is
 * the model's reading of a session, not the engine's, so one run of a paid proposer serves every later engine version
 * and every subset of the same questions (LongMemEval_s reuses 19,195 sessions across its 500 haystacks). Files are
 * written atomically; a missing directory means an empty cache.
 */
public final class ProposalCache {

	private final Path dir;

	public ProposalCache(Path dir) {
		this.dir = dir;
	}

	public Path dir() {
		return dir;
	}

	public static String key(String modelId, String spec, String user) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(modelId.getBytes(StandardCharsets.UTF_8));
			md.update((byte) 0);
			md.update(spec.getBytes(StandardCharsets.UTF_8));
			md.update((byte) 0);
			md.update(user.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(md.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public Optional<String> get(String key) throws IOException {
		Path f = file(key);
		return Files.isRegularFile(f) ? Optional.of(Files.readString(f, StandardCharsets.UTF_8)) : Optional.empty();
	}

	public void put(String key, String reply) throws IOException {
		Path f = file(key);
		Files.createDirectories(f.getParent());
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp-" + Thread.currentThread().threadId());
		Files.writeString(tmp, reply, StandardCharsets.UTF_8);
		try {
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			// Two workers finished the same key at once (Windows refuses to replace a file another thread just
			// moved in): the first writer's reply is as good as ours.
			Files.deleteIfExists(tmp);
			if (!Files.isRegularFile(f)) {
				throw e;
			}
		}
	}

	private Path file(String key) {
		return dir.resolve(key.substring(0, 2)).resolve(key + ".txt");
	}
}
