/*
 * Copyright (C) 2026 Marcus Hirt
 * All rights reserved.
 *
 * This software is free:
 * you can redistribute it and/or modify it under the terms of the
 * BSD 3-Clause License.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mnemic.embed;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The ONNX Runtime C API over foreign-function downcalls, the twenty entries of {@code OrtApi} the embedder uses
 * and nothing else (PLAN.md M4, prototype P3). The API is a table of function pointers whose order is fixed by
 * {@code onnxruntime_c_api.h}; the indices below were read from the 1.30 header and hold for every later version,
 * since the table is append-only. Every distinct signature here is registered for the native image in
 * {@code reachability-metadata.json} ("foreign"); a new signature needs a new entry there, or the image throws at
 * the first call.
 *
 * <p>Status handling: nearly every call returns an {@code OrtStatus*}, null on success. {@link #check} turns a
 * non-null status into an exception carrying the runtime's message and releases it.
 */
public final class OrtRuntime {

	/** ORT_API_VERSION the table is requested for; 1.30 serves 23 and every later runtime serves it too. */
	public static final int API_VERSION = 23;

	private static final Linker LINKER = Linker.nativeLinker();
	private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
	private static final AddressLayout PTR = ValueLayout.ADDRESS;

	// OrtApi indices (onnxruntime_c_api.h, 1.30)
	private static final int GET_ERROR_MESSAGE = 2, CREATE_ENV = 3, CREATE_SESSION = 7, RUN = 9,
			CREATE_SESSION_OPTIONS = 10, SET_GRAPH_OPT = 23, SET_INTRA_THREADS = 24, INPUT_COUNT = 30, OUTPUT_COUNT = 31,
			INPUT_NAME = 36, OUTPUT_NAME = 37, CREATE_TENSOR = 49, TENSOR_DATA = 51, DIMS_COUNT = 61, DIMS = 62,
			TENSOR_INFO = 65, CPU_MEMINFO = 69, ALLOCATOR_FREE = 76, DEFAULT_ALLOCATOR = 78, RELEASE_ENV = 92,
			RELEASE_STATUS = 93, RELEASE_MEMINFO = 94, RELEASE_SESSION = 95, RELEASE_VALUE = 96, RELEASE_TENSOR_INFO = 99,
			RELEASE_SESSION_OPTIONS = 100;

	/** ONNXTensorElementDataType */
	static final int TYPE_FLOAT = 1, TYPE_INT64 = 7;
	/** OrtLoggingLevel, OrtAllocatorType, OrtMemType */
	static final int LOG_WARNING = 2, ALLOC_ARENA = 0, MEM_DEFAULT = 0;
	/** GraphOptimizationLevel ORT_ENABLE_ALL */
	static final int OPT_ALL = 99;

	private final Arena arena = Arena.ofShared();
	private final MemorySegment api;
	private final MethodHandle getErrorMessage, createEnv, createSession, run, createSessionOptions, setGraphOpt,
			setIntraThreads, inputCount, outputCount, inputName, outputName, createTensor, tensorData, dimsCount, dims,
			tensorInfo, cpuMemInfo, allocatorFree, defaultAllocator, releaseEnv, releaseStatus, releaseMemInfo,
			releaseSession, releaseValue, releaseTensorInfo, releaseSessionOptions;
	private final String version;

	public OrtRuntime(Path library) {
		try {
			SymbolLookup lib = SymbolLookup.libraryLookup(library, arena);
			MethodHandle getApiBase = LINKER.downcallHandle(lib.find("OrtGetApiBase").orElseThrow(),
					FunctionDescriptor.of(PTR));
			MemorySegment base = ((MemorySegment) getApiBase.invoke()).reinterpret(2 * PTR.byteSize());
			MethodHandle versionFn = LINKER.downcallHandle(base.get(PTR, PTR.byteSize()), FunctionDescriptor.of(PTR));
			version = ((MemorySegment) versionFn.invoke()).reinterpret(64).getString(0);
			MethodHandle getApi = LINKER.downcallHandle(base.get(PTR, 0), FunctionDescriptor.of(PTR, I32));
			MemorySegment table = (MemorySegment) getApi.invoke(API_VERSION);
			if (table.address() == 0) {
				throw new IllegalStateException("ONNX Runtime " + version + " does not serve API version " + API_VERSION);
			}
			api = table.reinterpret(512 * PTR.byteSize());
		} catch (Throwable t) {
			throw new IllegalStateException("Cannot load ONNX Runtime from " + library + ": " + t.getMessage(), t);
		}
		getErrorMessage = fn(GET_ERROR_MESSAGE, FunctionDescriptor.of(PTR, PTR));
		createEnv = fn(CREATE_ENV, FunctionDescriptor.of(PTR, I32, PTR, PTR));
		createSession = fn(CREATE_SESSION, FunctionDescriptor.of(PTR, PTR, PTR, PTR, PTR));
		run = fn(RUN, FunctionDescriptor.of(PTR, PTR, PTR, PTR, PTR, I64, PTR, I64, PTR));
		createSessionOptions = fn(CREATE_SESSION_OPTIONS, FunctionDescriptor.of(PTR, PTR));
		setGraphOpt = fn(SET_GRAPH_OPT, FunctionDescriptor.of(PTR, PTR, I32));
		setIntraThreads = fn(SET_INTRA_THREADS, FunctionDescriptor.of(PTR, PTR, I32));
		inputCount = fn(INPUT_COUNT, FunctionDescriptor.of(PTR, PTR, PTR));
		outputCount = fn(OUTPUT_COUNT, FunctionDescriptor.of(PTR, PTR, PTR));
		inputName = fn(INPUT_NAME, FunctionDescriptor.of(PTR, PTR, I64, PTR, PTR));
		outputName = fn(OUTPUT_NAME, FunctionDescriptor.of(PTR, PTR, I64, PTR, PTR));
		createTensor = fn(CREATE_TENSOR, FunctionDescriptor.of(PTR, PTR, PTR, I64, PTR, I64, I32, PTR));
		tensorData = fn(TENSOR_DATA, FunctionDescriptor.of(PTR, PTR, PTR));
		dimsCount = fn(DIMS_COUNT, FunctionDescriptor.of(PTR, PTR, PTR));
		dims = fn(DIMS, FunctionDescriptor.of(PTR, PTR, PTR, I64));
		tensorInfo = fn(TENSOR_INFO, FunctionDescriptor.of(PTR, PTR, PTR));
		cpuMemInfo = fn(CPU_MEMINFO, FunctionDescriptor.of(PTR, I32, I32, PTR));
		allocatorFree = fn(ALLOCATOR_FREE, FunctionDescriptor.of(PTR, PTR, PTR));
		defaultAllocator = fn(DEFAULT_ALLOCATOR, FunctionDescriptor.of(PTR, PTR));
		releaseEnv = fn(RELEASE_ENV, FunctionDescriptor.ofVoid(PTR));
		releaseStatus = fn(RELEASE_STATUS, FunctionDescriptor.ofVoid(PTR));
		releaseMemInfo = fn(RELEASE_MEMINFO, FunctionDescriptor.ofVoid(PTR));
		releaseSession = fn(RELEASE_SESSION, FunctionDescriptor.ofVoid(PTR));
		releaseValue = fn(RELEASE_VALUE, FunctionDescriptor.ofVoid(PTR));
		releaseTensorInfo = fn(RELEASE_TENSOR_INFO, FunctionDescriptor.ofVoid(PTR));
		releaseSessionOptions = fn(RELEASE_SESSION_OPTIONS, FunctionDescriptor.ofVoid(PTR));
	}

	public String version() {
		return version;
	}

	private MethodHandle fn(int index, FunctionDescriptor descriptor) {
		return LINKER.downcallHandle(api.get(PTR, (long) index * PTR.byteSize()), descriptor);
	}

	private void check(Object status) {
		MemorySegment s = (MemorySegment) status;
		if (s.address() == 0) {
			return;
		}
		String message;
		try {
			message = ((MemorySegment) getErrorMessage.invoke(s)).reinterpret(4096).getString(0);
			releaseStatus.invoke(s);
		} catch (Throwable t) {
			message = "(unreadable status)";
		}
		throw new IllegalStateException("ONNX Runtime: " + message);
	}

	/** A C string in native memory, UTF-8 on every platform. */
	private static MemorySegment cstr(Arena a, String s) {
		return a.allocateFrom(s, StandardCharsets.UTF_8);
	}

	/** {@code ORTCHAR_T*}: wide (UTF-16) on Windows, UTF-8 elsewhere. */
	private static MemorySegment pathStr(Arena a, Path p) {
		String s = p.toAbsolutePath().toString();
		if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
			byte[] utf16 = (s + "\0").getBytes(StandardCharsets.UTF_16LE);
			MemorySegment seg = a.allocate(utf16.length);
			MemorySegment.copy(MemorySegment.ofArray(utf16), 0, seg, 0, utf16.length);
			return seg;
		}
		return cstr(a, s);
	}

	// ── handles ──

	public MemorySegment createEnv(String logId) {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment out = a.allocate(PTR);
			check(createEnv.invoke(LOG_WARNING, cstr(a, logId), out));
			return out.get(PTR, 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public MemorySegment createSession(MemorySegment env, Path model, int threads) {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment optsOut = a.allocate(PTR);
			check(createSessionOptions.invoke(optsOut));
			MemorySegment opts = optsOut.get(PTR, 0);
			try {
				check(setGraphOpt.invoke(opts, OPT_ALL));
				check(setIntraThreads.invoke(opts, threads));
				MemorySegment out = a.allocate(PTR);
				check(createSession.invoke(env, pathStr(a, model), opts, out));
				return out.get(PTR, 0);
			} finally {
				releaseSessionOptions.invoke(opts);
			}
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public String[] inputNames(MemorySegment session) {
		return names(session, inputCount, inputName);
	}

	public String[] outputNames(MemorySegment session) {
		return names(session, outputCount, outputName);
	}

	private String[] names(MemorySegment session, MethodHandle count, MethodHandle name) {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment allocOut = a.allocate(PTR);
			check(defaultAllocator.invoke(allocOut));
			MemorySegment allocator = allocOut.get(PTR, 0);
			MemorySegment n = a.allocate(I64);
			check(count.invoke(session, n));
			int total = (int) n.get(I64, 0);
			var out = new String[total];
			for (int i = 0; i < total; i++) {
				MemorySegment p = a.allocate(PTR);
				check(name.invoke(session, (long) i, allocator, p));
				MemorySegment str = p.get(PTR, 0);
				out[i] = str.reinterpret(256).getString(0);
				check(allocatorFree.invoke(allocator, str));
			}
			return out;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/**
	 * Runs the session on int64 inputs of shape [1][n] and returns the first output as floats, with its
	 * dimensions. Inputs and outputs live in the confined arena of the call; the returned array is Java's.
	 */
	public Result run(MemorySegment session, String[] inputNames, long[][] inputs, String outputName) {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment memOut = a.allocate(PTR);
			check(cpuMemInfo.invoke(ALLOC_ARENA, MEM_DEFAULT, memOut));
			MemorySegment memInfo = memOut.get(PTR, 0);
			MemorySegment namesArr = a.allocate(PTR, inputNames.length);
			MemorySegment valuesArr = a.allocate(PTR, inputNames.length);
			var created = new MemorySegment[inputNames.length];
			try {
				for (int i = 0; i < inputNames.length; i++) {
					long[] data = inputs[i];
					MemorySegment buf = a.allocateFrom(I64, data);
					MemorySegment shape = a.allocateFrom(I64, 1L, (long) data.length);
					MemorySegment valOut = a.allocate(PTR);
					check(createTensor.invoke(memInfo, buf, (long) data.length * 8, shape, 2L, TYPE_INT64, valOut));
					created[i] = valOut.get(PTR, 0);
					namesArr.setAtIndex(PTR, i, cstr(a, inputNames[i]));
					valuesArr.setAtIndex(PTR, i, created[i]);
				}
				MemorySegment outNames = a.allocate(PTR, 1);
				outNames.setAtIndex(PTR, 0, cstr(a, outputName));
				MemorySegment outValues = a.allocate(PTR, 1);
				outValues.setAtIndex(PTR, 0, MemorySegment.NULL);
				check(run.invoke(session, MemorySegment.NULL, namesArr, valuesArr, (long) inputNames.length, outNames, 1L,
						outValues));
				MemorySegment value = outValues.getAtIndex(PTR, 0);
				try {
					MemorySegment infoOut = a.allocate(PTR);
					check(tensorInfo.invoke(value, infoOut));
					MemorySegment info = infoOut.get(PTR, 0);
					long[] shape;
					try {
						MemorySegment nOut = a.allocate(I64);
						check(dimsCount.invoke(info, nOut));
						int nd = (int) nOut.get(I64, 0);
						MemorySegment dimsBuf = a.allocate(I64, nd);
						check(dims.invoke(info, dimsBuf, (long) nd));
						shape = dimsBuf.toArray(I64);
					} finally {
						releaseTensorInfo.invoke(info);
					}
					long count = 1;
					for (long d : shape) {
						count *= d;
					}
					MemorySegment dataOut = a.allocate(PTR);
					check(tensorData.invoke(value, dataOut));
					float[] floats = dataOut.get(PTR, 0).reinterpret(count * 4).toArray(ValueLayout.JAVA_FLOAT);
					return new Result(shape, floats);
				} finally {
					releaseValue.invoke(value);
				}
			} finally {
				for (MemorySegment v : created) {
					if (v != null) {
						releaseValue.invoke(v);
					}
				}
				releaseMemInfo.invoke(memInfo);
			}
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public void releaseSession(MemorySegment session) {
		try {
			releaseSession.invoke(session);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public void releaseEnv(MemorySegment env) {
		try {
			releaseEnv.invoke(env);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static RuntimeException rethrow(Throwable t) {
		return t instanceof RuntimeException r ? r : new IllegalStateException(t);
	}

	/** An output tensor: its shape and its values in row-major order. */
	public record Result(long[] shape, float[] data) {
	}
}
