#!/bin/bash

set -euo pipefail

PRODUCE_GARBAGE="${1:-false}"
ITERATIONS="${2:-1000000}"

if [[ "$PRODUCE_GARBAGE" != "true" && "$PRODUCE_GARBAGE" != "false" ]]; then
	echo "First argument must be true or false: $PRODUCE_GARBAGE" >&2
	exit 2
fi

if ! [[ "$ITERATIONS" =~ ^[1-9][0-9]*$ ]]; then
	echo "Second argument must be a positive integer: $ITERATIONS" >&2
	exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

JAR="target/coralme-all.jar"

if [[ ! -f "$JAR" ]]; then
	echo "Missing $JAR; run 'mvn package' first." >&2
	exit 1
fi

echo "java -Xlog:gc -Xms32m -Xmx64m -cp $JAR com.coralblocks.coralme.example.NoGCTest $PRODUCE_GARBAGE $ITERATIONS"

exec java -Xlog:gc -Xms32m -Xmx64m -cp "$JAR" \
	com.coralblocks.coralme.example.NoGCTest "$PRODUCE_GARBAGE" "$ITERATIONS"
