# Protocol validator

Run from anywhere with Python 3.9 or newer; no packages are required:

```sh
python3 tools/protocol-validator/validate.py
```

With no arguments it verifies that every `contracts/fixtures/valid` document passes, every `invalid` document fails, raw chunk bytes match their declared integrity metadata, and complete raw-session fixtures agree across documents. It also enforces semantic rules such as unique capability IDs and enrollment nonce separation.

Run the complete dependency-free suite with:

```sh
sh tools/run-contract-tests.sh
```

`wire_vectors.py --check` verifies deterministic CBOR, nonce construction, HKDF/SAS, and fragmentation against `contracts/vectors/protocol-v1.json`. Swift and Kotlin tests must load that same golden file. Pass explicit JSON paths to validate selected documents. This validator implements only the Draft 2020-12 keywords used here; use a standards-complete implementation as an additional CI check when available.
