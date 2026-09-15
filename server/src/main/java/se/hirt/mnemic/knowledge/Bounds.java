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
package se.hirt.mnemic.knowledge;

import se.hirt.mnemic.proposal.Proposal.ValidTime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A valid-time interval as stored on a fact or an event: ISO bounds, the precision each was given, and where each
 * came from ({@code stated}, {@code resolved} from a relative expression against the observation date, {@code event},
 * {@code sequence}). Either bound may be unknown.
 */
public record Bounds(String start, String startPrecision, String startSource, String end, String endPrecision,
                     String endSource) {

	public static final Bounds NONE = new Bounds(null, null, null, null, null, null);

	private static final Pattern AGO = Pattern.compile("(\\d+)\\s+(year|month|week|day)s?\\s+ago");
	private static final Pattern LAST = Pattern.compile("last\\s+(year|month|week)");
	private static final Map<String, Integer> WORDS = Map.ofEntries(Map.entry("one", 1), Map.entry("a", 1),
			Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6),
			Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
			Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("fifteen", 15), Map.entry("twenty", 20));

	private record Bound(String iso, String precision, String source) {
		static final Bound UNKNOWN = new Bound(null, null, null);
	}

	/** The bounds a proposal states, normalised; a bound that cannot be read is dropped with a warning. */
	public static Bounds of(ValidTime vt, Instant observedAt, List<String> warnings) {
		if (vt == null) {
			return NONE;
		}
		Bound s = bound(vt.start(), vt.precision(), observedAt, warnings);
		Bound e = bound(vt.end(), vt.precision(), observedAt, warnings);
		return new Bounds(s.iso, s.precision, s.source, e.iso, e.precision, e.source);
	}

	/** The bounds stored on a fact. */
	public static Bounds of(Fact f) {
		return new Bounds(f.validStart(), f.validStartPrecision(), f.startSource(), f.validEnd(),
				f.validEndPrecision(), f.endSource());
	}

	public Bounds withStart(String start, String precision, String source) {
		return new Bounds(start, precision, source, end, endPrecision, endSource);
	}

	public Bounds withEnd(String end, String precision, String source) {
		return new Bounds(start, startPrecision, startSource, end, precision, source);
	}

	/** The parenthesised suffix of a rendering: {@code (since 2018)}, {@code (2018 – 2020)}, {@code (ended)}. */
	public String suffix(boolean ended, Lang lang) {
		if (start != null && end != null) {
			return " (" + show(start, startPrecision) + " – " + show(end, endPrecision) + ")";
		}
		if (start != null) {
			return ended ? lang.fromEnded(show(start, startPrecision)) : lang.since(show(start, startPrecision));
		}
		if (end != null) {
			return lang.until(show(end, endPrecision));
		}
		return ended ? lang.ended() : "";
	}

	/** An ISO bound cut to its precision: {@code 2018}, {@code 2018-03}, {@code 2018-03-05}. */
	public static String show(String iso, String precision) {
		if (iso == null) {
			return "?";
		}
		return switch (precision == null ? "" : precision) {
			case "year" -> iso.substring(0, 4);
			case "month" -> iso.substring(0, 7);
			default -> iso.length() >= 10 ? iso.substring(0, 10) : iso;
		};
	}

	private static Bound bound(String text, String precision, Instant observedAt, List<String> warnings) {
		if (text == null || text.isBlank() || "unknown".equalsIgnoreCase(text) || "null".equals(text)) {
			return Bound.UNKNOWN;
		}
		String t = text.trim();
		if (t.matches("\\d{4}")) {
			return new Bound(t + "-01-01", precisionOr(precision, "year"), "stated");
		}
		if (t.matches("\\d{4}-\\d{2}")) {
			return new Bound(t + "-01", precisionOr(precision, "month"), "stated");
		}
		if (t.matches("\\d{4}-\\d{2}-\\d{2}")) {
			return new Bound(t, precisionOr(precision, "day"), "stated");
		}
		if (t.matches("\\d{4}-\\d{2}-\\d{2}T.*")) {
			return new Bound(t, precisionOr(precision, "instant"), "stated");
		}
		Bound relative = relative(numbersForWords(t.toLowerCase(Locale.ROOT)), observedAt);
		if (relative != null) {
			return relative;
		}
		warnings.add("valid_time '" + t + "' is not an ISO date (YYYY, YYYY-MM, YYYY-MM-DD) or a simple relative "
				+ "expression (N years/months/days ago, last year/month, yesterday). Bound ignored; resolve it "
				+ "against the observation date and give an honest precision, or use \"unknown\".");
		return Bound.UNKNOWN;
	}

	private static String numbersForWords(String t) {
		var sb = new StringBuilder();
		for (String w : t.split("\\s+")) {
			Integer n = WORDS.get(w);
			sb.append(n != null ? n.toString() : w).append(' ');
		}
		return sb.toString().trim();
	}

	private static Bound relative(String t, Instant observedAt) {
		LocalDate base = observedAt == null ? LocalDate.now(ZoneOffset.UTC)
				: observedAt.atZone(ZoneOffset.UTC).toLocalDate();
		Matcher m = AGO.matcher(t);
		if (m.find()) {
			int n = Integer.parseInt(m.group(1));
			return switch (m.group(2)) {
				case "year" -> resolved(base.minusYears(n).getYear() + "-01-01", "year");
				case "month" -> resolved(base.minusMonths(n).withDayOfMonth(1).toString(), "month");
				case "week" -> resolved(base.minusWeeks(n).toString(), "day");
				default -> resolved(base.minusDays(n).toString(), "day");
			};
		}
		m = LAST.matcher(t);
		if (m.find()) {
			return switch (m.group(1)) {
				case "year" -> resolved(base.minusYears(1).getYear() + "-01-01", "year");
				case "month" -> resolved(base.minusMonths(1).withDayOfMonth(1).toString(), "month");
				default -> resolved(base.minusWeeks(1).toString(), "day");
			};
		}
		return switch (t) {
			case "yesterday" -> resolved(base.minusDays(1).toString(), "day");
			case "today", "now" -> resolved(base.toString(), "day");
			case "this year" -> resolved(base.getYear() + "-01-01", "year");
			default -> null;
		};
	}

	private static Bound resolved(String iso, String precision) {
		return new Bound(iso, precision, "resolved");
	}

	private static String precisionOr(String given, String inferred) {
		return given == null || given.isBlank() ? inferred : given.toLowerCase(Locale.ROOT);
	}
}
