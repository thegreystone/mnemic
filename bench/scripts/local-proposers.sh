#!/usr/bin/env bash
# Runs the local-proposer comparison for a list of LM Studio models on the same 20 LongMemEval questions.
# Usage: bench/scripts/local-proposers.sh "<model-key>[@quant]=<run-name>[:<context>:<parallel>]" ...
# Each model is downloaded if missing (resuming on LM Studio's transfer timeouts), loaded under the identifier
# "proposer", run with --limit 20, and compared against the Haiku pilot, the 9B pilot, and lexical.
set -u
cd "$(dirname "$0")/../.."

# LM Studio's downloads folder: a specific quantization is a file <publisher>/<Name>-GGUF/<Name>-<QUANT>.gguf there.
# `lms ls` does not list variants and `lms load --estimate-only` rejects @quant, so the file is the only reliable check.
DL="${LMS_MODELS_DIR:-}"
if [ -z "$DL" ]; then
  DL=$(grep -rhoE '"downloadsFolder"\s*:\s*"[^"]+"' ~/.cache/lm-studio/settings.json ~/.cache/lm-studio/.internal/*.json 2>/dev/null | head -1 | sed -E 's/.*:\s*"//; s/"$//; s#\\\\#/#g')
fi
echo "models folder: $DL"

present() {
  local key="$1" quant="$2"
  if [ -n "$quant" ]; then
    find "$DL" -maxdepth 3 -type f -iname "*${quant}*.gguf" -ipath "*/$(basename "$key")-GGUF/*" 2>/dev/null | grep -q .
  else
    lms ls 2>&1 | grep -qi "$key"
  fi
}

for spec in "$@"; do
  model="${spec%%=*}"; rest="${spec#*=}"
  name="${rest%%:*}"; rest2="${rest#*:}"
  if [ "$rest2" = "$rest" ]; then ctx=65536; par=4; else ctx="${rest2%%:*}"; par="${rest2#*:}"; fi
  key="${model%%@*}"
  quant="${model#*@}"; [ "$quant" = "$model" ] && quant=""
  echo "################ $model -> results/$name (context $ctx, parallel $par) $(date +%T)"
  for i in 1 2 3 4 5 6 7 8; do
    if present "$key" "$quant"; then break; fi
    lms get "$model" -y 2>&1 | tr '\r' '\n' | sed 's/\x1b\[[0-9;?]*[a-zA-Z]//g' | grep -vE '^\s*$' | tail -1 | cut -c1-140
    sleep 60  # LM Studio's daemon keeps a timed-out download going; give it time before asking again
  done
  present "$key" "$quant" || { echo "download failed for $model"; continue; }
  lms unload --all >/dev/null 2>&1
  lms load "$model" --context-length "$ctx" --parallel "$par" --identifier proposer -y 2>&1 | tr '\r' '\n' \
    | sed 's/\x1b\[[0-9;?]*[a-zA-Z]//g' | grep -vE '^\s*$|Loading.*%' | tail -1
  lms ps 2>&1 | grep -q proposer || { echo "load failed for $model"; continue; }
  lms ps 2>&1 | grep proposer | awk '{print "loaded:", $2, $4, $5, "context", $6, "parallel", $7}'
  nvidia-smi --query-gpu=memory.used --format=csv,noheader
  start=$(date +%s)
  ( cd bench && rm -rf "results/$name" && mvn -q exec:java -Dexec.args="run --data data/longmemeval_s_cleaned.json --out results/$name --granularity session --proposer lmstudio:proposer --limit 20 --workers $par" 2>&1 \
    | grep -E "run complete|recall_at_5\"|recall_at_10\"|facts_per|error on|proposal failed|questions \(" | tail -8 )
  echo "elapsed $(( ($(date +%s)-start)/60 )) min"
  grep '"proposer"' "bench/results/$name/config.json"
  for a in m3-facts-pilot-d m3-facts-local-qwen9b-b m3-lexical; do
    echo "== $a vs $name"
    mvn -q -pl bench exec:java -Dexec.args="compare --a bench/results/$a --b bench/results/$name" 2>&1 | grep diff
  done
done
lms unload --all >/dev/null 2>&1
echo "################ all done $(date +%T)"
