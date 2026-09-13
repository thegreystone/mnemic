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
package se.hirt.mnemic.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A failure that is reported to the calling model as a structured tool error, never as a protocol error (DECISIONS.md
 * §3.1: validation failures are {@code isError: true} results with fix-it text so the model can retry). The message
 * must say what was wrong and what a valid call looks like.
 */
public final class MnemicException extends RuntimeException {

	public enum Code {
		INVALID_ARGUMENT, NOT_FOUND, CONFLICT, INTERNAL
	}

	private final Code code;
	private final Map<String, Object> details;

	private MnemicException(Code code, String message, Map<String, Object> details, Throwable cause) {
		super(message, cause);
		this.code = code;
		this.details = details == null ? Map.of() : details;
	}

	public static MnemicException invalidArgument(String message) {
		return new MnemicException(Code.INVALID_ARGUMENT, message, null, null);
	}

	public static MnemicException invalidArgument(String message, Map<String, Object> details) {
		return new MnemicException(Code.INVALID_ARGUMENT, message, details, null);
	}

	public static MnemicException notFound(String message) {
		return new MnemicException(Code.NOT_FOUND, message, null, null);
	}

	public static MnemicException conflict(String message, Map<String, Object> details) {
		return new MnemicException(Code.CONFLICT, message, details, null);
	}

	public static MnemicException internal(String message, Throwable cause) {
		return new MnemicException(Code.INTERNAL, message, null, cause);
	}

	public Code code() {
		return code;
	}

	public Map<String, Object> details() {
		return details;
	}

	/** {@code {"error": {"code", "message", "retryable", "details"}}}, the shape every tool error carries. */
	public Map<String, Object> toErrorMap() {
		var error = new LinkedHashMap<String, Object>();
		error.put("code", code.name());
		error.put("message", getMessage());
		error.put("retryable", code == Code.INTERNAL);
		if (!details.isEmpty()) {
			error.put("details", details);
		}
		return Map.of("error", error);
	}
}
