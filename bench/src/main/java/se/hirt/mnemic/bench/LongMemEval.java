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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The LongMemEval dataset (arXiv 2410.10813; {@code xiaowu0162/longmemeval-cleaned}, MIT). Field names follow the
 * official JSON:
 * {@code question_id, question_type, question, answer, question_date, haystack_session_ids, haystack_dates,
 * haystack_sessions[[turn]], answer_session_ids}; a turn is {@code role, content, has_answer?}. Dates look like
 * {@code 2023/05/20 (Sat) 02:21}.
 */
public final class LongMemEval {

	public record Turn(String role, String content, boolean hasAnswer) {
	}

	public record Session(String id, String dateText, Instant date, List<Turn> turns) {
	}

	public record Question(String id, String type, String question, String answer, String dateText, Instant date,
			List<Session> haystack, List<String> answerSessionIds) {

		public boolean isAbstention() {
			return id.endsWith("_abs");
		}
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd (EEE) HH:mm", Locale.ENGLISH);

	private LongMemEval() {
	}

	public static List<Question> load(Path file) throws IOException {
		JsonNode root = MAPPER.readTree(file.toFile());
		var out = new ArrayList<Question>();
		for (JsonNode q : root) {
			JsonNode ids = q.get("haystack_session_ids");
			JsonNode dates = q.get("haystack_dates");
			JsonNode sessions = q.get("haystack_sessions");
			var haystack = new ArrayList<Session>();
			for (int i = 0; i < sessions.size(); i++) {
				var turns = new ArrayList<Turn>();
				for (JsonNode t : sessions.get(i)) {
					turns.add(new Turn(t.path("role").asText(), t.path("content").asText(),
							t.path("has_answer").asBoolean(false)));
				}
				String dateText = dates.get(i).asText();
				haystack.add(new Session(ids.get(i).asText(), dateText, parseDate(dateText), List.copyOf(turns)));
			}
			var answerIds = new ArrayList<String>();
			for (JsonNode a : q.path("answer_session_ids")) {
				answerIds.add(a.asText());
			}
			String qDate = q.path("question_date").asText();
			out.add(new Question(q.get("question_id").asText(), q.get("question_type").asText(),
					q.get("question").asText(), q.path("answer").asText(), qDate, parseDate(qDate),
					List.copyOf(haystack), List.copyOf(answerIds)));
		}
		return out;
	}

	/** Parses the dataset's date format; null when unparseable so ingestion can fall back to haystack order. */
	static Instant parseDate(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			return LocalDateTime.parse(text.trim(), DATE).toInstant(ZoneOffset.UTC);
		} catch (DateTimeParseException e) {
			return null;
		}
	}
}
