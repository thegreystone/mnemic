#!/usr/bin/env bash
# The stratified subset with the semantic channel on (M4 ablation): the same questions and proposer as
# stratified.sh, plus the in-process embedder, into one result directory; then the paired comparison against a
# run without it on the same questions. Proposals come from the cache when the proposer has already read the
# sessions, so a run with a cached proposer needs no model server.
# EXTRA_MVN adds Maven options to the run, e.g. EXTRA_MVN='-Dmnemic.semantic.kinds=fact -Dmnemic.semantic.weight=0.5'.
# Usage: bench/scripts/stratified-embed.sh <proposer-spec|none> <run-name> <without-run-name> <embed-model-dir> <ort-library> [per-type N] [workers]
set -u
cd "$(dirname "$0")/../.."
proposer="${1:?proposer spec, e.g. lmstudio:proposer or none}"
name="${2:?run name}"
without="${3:?the run without the semantic channel to compare against}"
model="${4:?embedding model directory (model.onnx + tokenizer.json)}"
ort="${5:?onnxruntime shared library}"
per="${6:-20}"
workers="${7:-4}"
TYPES="single-session-user single-session-assistant single-session-preference multi-session temporal-reasoning knowledge-update"
start=$(date +%s)
for t in $TYPES; do
  echo "################ $t x $per -> results/$name $(date +%T)"
  ( cd bench && mvn -q ${EXTRA_MVN:-} exec:java -Dexec.args="run --data data/longmemeval_s_cleaned.json --out results/$name --granularity session --proposer $proposer --types $t --limit $per --workers $workers --embed-model $model --ort-library $ort" 2>&1 \
    | grep -E "run complete|error on|proposal failed|questions \(|Exception" | tail -4 )
done
echo "elapsed $(( ($(date +%s)-start)/60 )) min"
( cd bench && mvn -q exec:java -Dexec.args="metrics --run results/$name" 2>&1 | grep -E 'recall_at_5"|recall_at_10"|by_type|"[a-z-]+" : "0\.|facts_per|ingest_ms_p50' )
echo "== $without vs $name (paired on the $name questions): the semantic channel's effect"
mvn -q -pl bench exec:java -Dexec.args="compare --a bench/results/$without --b bench/results/$name" 2>&1 | grep -E "diff|by_type|\"[a-z-]+\" :"
echo "################ done $(date +%T)"
