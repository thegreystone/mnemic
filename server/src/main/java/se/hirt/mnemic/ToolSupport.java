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

import io.quarkiverse.mcp.server.ToolResponse;
import org.jboss.logging.Logger;
import se.hirt.mnemic.protocol.Json;
import se.hirt.mnemic.protocol.MnemicException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Renders service results as the response envelope and failures as structured tool errors. A failure is an
 * {@code isError} result with a JSON {@code {"error": {code, message, retryable, details}}} body, never a thrown
 * exception, so the model can read the message and retry.
 */
final class ToolSupport {

	private static final Logger LOG = Logger.getLogger(ToolSupport.class);

	private ToolSupport() {
	}

	/** JSON envelope {@code {"result": ...}} for tools whose result the model must parse (ids, counts). */
	static ToolResponse json(String tool, Supplier<Map<String, Object>> body) {
		return run(tool, () -> ToolResponse.success(envelope(body.get())));
	}

	/** Plain text for tools whose result the model reads (recall). */
	static ToolResponse text(String tool, Supplier<String> body) {
		return run(tool, () -> ToolResponse.success(body.get()));
	}

	private static ToolResponse run(String tool, Supplier<ToolResponse> body) {
		try {
			return body.get();
		} catch (MnemicException e) {
			LOG.debugf("%s failed: %s %s", tool, e.code(), e.getMessage());
			return ToolResponse.error(Json.write(e.toErrorMap()));
		} catch (RuntimeException e) {
			LOG.errorf(e, "%s failed unexpectedly", tool);
			return ToolResponse.error(Json.write(MnemicException
					.internal("Internal error in " + tool + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(),
							e)
					.toErrorMap()));
		}
	}

	static String envelope(Map<String, Object> serviceResult) {
		var out = new LinkedHashMap<String, Object>();
		out.put("result", serviceResult);
		return Json.write(out);
	}
}
