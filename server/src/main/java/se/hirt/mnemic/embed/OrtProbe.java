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
package se.hirt.mnemic.embed;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * A diagnostic for a user-provided ONNX Runtime library, reported by {@code status}: loads it, calls
 * {@code OrtGetApiBase()}, and reads the version string and whether {@code GetApi(ORT_API_VERSION)} serves the API
 * table. Its downcall signatures are registered for the native image in {@code reachability-metadata.json}. No
 * session is created here; that is {@link OrtRuntime}'s job.
 */
public final class OrtProbe {

	static final int API_VERSION = OrtRuntime.API_VERSION;

	private OrtProbe() {
	}

	/** The runtime's version string, or the failure's message; never throws. */
	public static String describe(String library) {
		try {
			return "onnxruntime " + version(Path.of(library)) + ", api " + API_VERSION + " "
					+ (apiTable(Path.of(library)) ? "available" : "missing");
		} catch (Throwable t) {
			return "unavailable: " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
		}
	}

	static String version(Path library) throws Throwable {
		MemorySegment base = apiBase(library);
		// struct OrtApiBase { const OrtApi* (*GetApi)(uint32_t); const char* (*GetVersionString)(void); }
		MemorySegment getVersion = base.get(ValueLayout.ADDRESS, ValueLayout.ADDRESS.byteSize());
		MethodHandle fn = Linker.nativeLinker().downcallHandle(getVersion, FunctionDescriptor.of(ValueLayout.ADDRESS));
		MemorySegment cstr = ((MemorySegment) fn.invoke()).reinterpret(64);
		return cstr.getString(0);
	}

	static boolean apiTable(Path library) throws Throwable {
		MemorySegment base = apiBase(library);
		MemorySegment getApi = base.get(ValueLayout.ADDRESS, 0);
		MethodHandle fn = Linker.nativeLinker().downcallHandle(getApi,
				FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
		MemorySegment api = (MemorySegment) fn.invoke(API_VERSION);
		return api.address() != 0;
	}

	private static MemorySegment apiBase(Path library) throws Throwable {
		SymbolLookup lib = SymbolLookup.libraryLookup(library, Arena.global());
		MethodHandle getApiBase = Linker.nativeLinker().downcallHandle(lib.find("OrtGetApiBase").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.ADDRESS));
		return ((MemorySegment) getApiBase.invoke()).reinterpret(2 * ValueLayout.ADDRESS.byteSize());
	}
}
