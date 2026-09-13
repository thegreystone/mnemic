#!/usr/bin/env bash
# The stratified subset: N questions of each LongMemEval type into one result directory, then a paired
# comparison against the lexical baseline on the same questions.
# Usage: bench/scripts/stratified.sh <proposer-spec|none> <run-name> [per-type N] [workers]
set -u
cd "$(dirname "$0")/../.."
proposer="${1:?proposer spec, e.g. lmstudio:proposer or none}"
name="${2:?run name}"
per="${3:-20}"
workers="${4:-4}"
TYPES="single-session-user single-session-assistant single-session-preference multi-session temporal-reasoning knowledge-update"
start=$(date +%s)
for t in $TYPES; do
  echo "################ $t x $per -> results/$name $(date +%T)"
  ( cd bench && mvn -q exec:java -Dexec.args="run --data data/longmemeval_s_cleaned.json --out results/$name --granularity session --proposer $proposer --types $t --limit $per --workers $workers" 2>&1 \
    | grep -E "run complete|error on|proposal failed|questions \(" | tail -4 )
done
echo "elapsed $(( ($(date +%s)-start)/60 )) min"
( cd bench && mvn -q exec:java -Dexec.args="metrics --run results/$name" 2>&1 | grep -E 'recall_at_5"|recall_at_10"|by_type|"[a-z-]+" : "0\.|facts_per|ingest_ms_p50' )
echo "== m3-lexical vs $name (paired on the $name questions)"
mvn -q -pl bench exec:java -Dexec.args="compare --a bench/results/m3-lexical --b bench/results/$name" 2>&1 | grep -E "diff|by_type|\"[a-z-]+\" :"
echo "################ done $(date +%T)"
