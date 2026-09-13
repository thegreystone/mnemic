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
import java.util.Locale;

/**
 * LongMemEval's answer check, per question type, after {@code src/evaluation/evaluate_qa.py}: a yes/no question to the
 * judge model, correct iff the response contains "yes". The wording here is a close paraphrase; for reported numbers
 * run the official script over {@code hypotheses.jsonl}, which the bench writes in the official format.
 */
public final class Judge {

	private final ChatModel model;

	public Judge(ChatModel model) {
		this.model = model;
	}

	public String model() {
		return model.id();
	}

	public boolean correct(String questionId, String type, String question, String reference, String hypothesis)
			throws IOException, InterruptedException {
		String response = model.chat("You are a strict grader. Reply with 'yes' or 'no' only.",
				prompt(questionId, type, question, reference, hypothesis));
		return isYes(response);
	}

	static boolean isYes(String response) {
		return response != null && response.toLowerCase(Locale.ROOT).contains("yes");
	}

	static String prompt(String questionId, String type, String question, String reference, String hypothesis) {
		if (questionId.endsWith("_abs")) {
			return "The question is unanswerable from the information the model had. Does the model's response " + "correctly identify the question as unanswerable, for example by saying it lacks the information, " + "that the information is incomplete, or that what it has is irrelevant? Answer yes or no.\n\n" + "Question: " + question + "\n\nModel response: " + hypothesis;
		}
		return switch (type) {
			case "temporal-reasoning" ->
					"Judge whether the model's response contains the correct answer or is " + "equivalent to it. Do not penalize off-by-one errors in a number of days. A response containing " + "only a subset of the required information is wrong. Answer yes or no.\n\n" + core(
							question, reference, hypothesis);
			case "knowledge-update" ->
					"Judge whether the model's response contains the updated, correct answer. A " + "response that mentions previous information along with the updated answer is correct if the " + "updated part matches. Answer yes or no.\n\n" + core(
							question, reference, hypothesis);
			case "single-session-preference" ->
					"The reference is a rubric of the user's personal information that a " + "good response should recall and use. The response need not reflect every point, but it must " + "recall and utilize the user's personal information correctly. Does it? Answer yes or no.\n\n" + core(
							question, reference, hypothesis);
			default ->
					"Judge whether the model's response contains the correct answer or is equivalent to it. A " + "response containing only a subset of the required information is wrong. Answer yes or no.\n\n" + core(
							question, reference, hypothesis);
		};
	}

	private static String core(String question, String reference, String hypothesis) {
		return "Question: " + question + "\n\nCorrect answer: " + reference + "\n\nModel response: " + hypothesis;
	}
}
