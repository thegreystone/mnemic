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

import se.hirt.mnemic.model.ChatModel;

import java.io.IOException;

/**
 * The reader: answers a question from recalled context, in the spirit of LongMemEval's reading prompt. The question
 * date is supplied because temporal-reasoning questions need "today". Vendor-neutral: takes any {@link ChatModel}.
 */
public final class Reader {

	private final ChatModel model;

	public Reader(ChatModel model) {
		this.model = model;
	}

	public String model() {
		return model.id();
	}

	public String answer(String question, String questionDate, String context)
			throws IOException, InterruptedException {
		String system = "You are a helpful assistant with access to retrieved memory of earlier conversations "
				+ "with the user. Answer the user's question using only the retrieved memory. If the memory does not "
				+ "contain the information needed, say that you do not have that information. Be concise. "
				+ "The current date is " + questionDate + ".";
		String user = "Retrieved memory:\n\n" + context + "\n\nQuestion: " + question;
		return model.chat(system, user);
	}
}
