#!/usr/bin/env bash
# Checks the raw Kaggle files before `docker compose up`: a missing bind-mount source would make Docker
# create an empty directory with that name instead of failing.
set -euo pipefail
cd "$(dirname "$0")/.."

missing=0
for path in data/fraudTrain.csv data/fraudTest.csv data/creditcard.csv; do
  if [[ ! -f "$path" ]]; then
    echo "missing: $path"
    missing=1
  fi
done
if [[ $missing -eq 1 ]]; then
  echo "download them from Kaggle (kartik2112/fraud-detection, mlg-ulb/creditcardfraud) into data/" >&2
  exit 1
fi
echo "preflight ok"
