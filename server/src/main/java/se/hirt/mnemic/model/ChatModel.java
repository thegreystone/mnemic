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

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * What Mnemic needs from a language model: a system prompt and a user message in, text out; and, for the usage bench, a
 * conversation with tools in, a turn of text and tool calls out. Every vendor lives behind a {@link ModelProvider};
 * nothing outside a provider names a vendor. The server uses this only for the optional server-side proposer; the
 * benchmark harness uses the same interface for its reader, judge, proposer, and the assistant under test.
 */
public interface ChatModel {

	/** Stable identifier recorded in run configs and logs, e.g. {@code lmstudio:qwen3-4b}. */
	String id();

	String chat(String system, String user) throws IOException, InterruptedException;

	/** A tool the model may call: an MCP tool as the server lists it (name, description, JSON schema of its input). */
	record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
	}

	/** A call the model made; {@code id} is the vendor's handle, echoed back with the result. */
	record ToolCall(String id, String name, Map<String, Object> arguments) {
	}

	/** One turn of the model: what it said and the calls it made; either may be empty. */
	record Turn(String text, List<ToolCall> calls) {
		public boolean hasCalls() {
			return calls != null && !calls.isEmpty();
		}
	}

	/** A message in a conversation with tools. */
	sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {
	}

	record UserMessage(String text) implements Message {
	}

	record AssistantMessage(Turn turn) implements Message {
	}

	/** What a tool answered, addressed to the call it answers. */
	record ToolResultMessage(String callId, String name, String content) implements Message {
	}

	/** Whether {@link #chat(String, List, List)} is implemented: the model receives tools as tools, not as text. */
	default boolean supportsTools() {
		return false;
	}

	/**
	 * A turn of a conversation in which the model holds tools: the vendor's native tool calling, so the calls arrive
	 * structured and the model never writes JSON into text. The messages alternate user, assistant, and tool results as
	 * the harness recorded them.
	 */
	default Turn chat(String system, List<Message> messages, List<ToolSpec> tools)
			throws IOException, InterruptedException {
		throw new UnsupportedOperationException(id() + " does not support tool calls");
	}
}
