#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
python3 "$repo/tools/protocol-validator/validate.py"
python3 "$repo/tools/protocol-validator/wire_vectors.py" --check
python3 -m py_compile "$repo/tools/protocol-validator/validate.py" "$repo/tools/protocol-validator/wire_vectors.py"
