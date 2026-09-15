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

import java.util.*;

/**
 * The numbers the benchmark reports: means with bootstrap intervals, per-type slices, session recall@k, latency
 * percentiles, and the paired bootstrap that decides whether two runs differ. Bootstrap uses a fixed seed so a report
 * is reproducible from its inputs.
 */
public final class Metrics {

	public static final int RESAMPLES = 2000;
	public static final long SEED = 20260907L;

	/** Point estimate with a 95% percentile-bootstrap interval. */
	public record Estimate(double value, double low, double high, int n) {
		@Override
		public String toString() {
			return String.format(Locale.ROOT, "%.3f [%.3f, %.3f] n=%d", value, low, high, n);
		}
	}

	private Metrics() {
	}

	public static Estimate mean(List<Double> xs) {
		int n = xs.size();
		if (n == 0) {
			return new Estimate(Double.NaN, Double.NaN, Double.NaN, 0);
		}
		double[] a = xs.stream().mapToDouble(Double::doubleValue).toArray();
		double m = Arrays.stream(a).average().orElse(Double.NaN);
		Random rnd = new Random(SEED);
		double[] boots = new double[RESAMPLES];
		for (int b = 0; b < RESAMPLES; b++) {
			double s = 0;
			for (int i = 0; i < n; i++) {
				s += a[rnd.nextInt(n)];
			}
			boots[b] = s / n;
		}
		Arrays.sort(boots);
		return new Estimate(m, boots[(int) (RESAMPLES * 0.025)], boots[(int) (RESAMPLES * 0.975) - 1], n);
	}

	public static Estimate accuracy(List<Boolean> labels) {
		return mean(labels.stream().map(b -> b ? 1.0 : 0.0).toList());
	}

	/**
	 * Paired bootstrap of the per-item difference {@code b - a} over items present in both runs. The interval excluding
	 * zero is the bar for calling a change a gain.
	 */
	public static Estimate pairedDifference(Map<String, Boolean> a, Map<String, Boolean> b) {
		var diffs = new ArrayList<Double>();
		for (String id : a.keySet()) {
			if (b.containsKey(id)) {
				diffs.add((b.get(id) ? 1.0 : 0.0) - (a.get(id) ? 1.0 : 0.0));
			}
		}
		return mean(diffs);
	}

	/** Fraction of the answer sessions found in the top {@code k} ranked sessions. */
	public static double recallAtK(List<String> ranked, Set<String> answers, int k) {
		if (answers.isEmpty()) {
			return Double.NaN;
		}
		long hit = ranked.stream().limit(k).filter(answers::contains).distinct().count();
		return (double) hit / answers.size();
	}

	public static double percentile(List<Long> values, double p) {
		if (values.isEmpty()) {
			return Double.NaN;
		}
		long[] a = values.stream().mapToLong(Long::longValue).sorted().toArray();
		int idx = (int) Math.min(a.length - 1, Math.max(0, Math.ceil(p * a.length) - 1));
		return a[idx];
	}

	/** Groups per-item values by a key, preserving first-seen order, for per-type slices. */
	public static <T> Map<String, List<T>> byKey(List<String> keys, List<T> values) {
		var out = new LinkedHashMap<String, List<T>>();
		for (int i = 0; i < keys.size(); i++) {
			out.computeIfAbsent(keys.get(i), k -> new ArrayList<>()).add(values.get(i));
		}
		return out;
	}
}
