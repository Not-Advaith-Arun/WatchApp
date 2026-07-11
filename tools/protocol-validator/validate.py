#!/usr/bin/env python3
"""Dependency-free validator for WatchApp JSON schemas and fixtures.

This intentionally implements only the JSON Schema keywords used by contracts/.
CI may additionally run a standards-complete Draft 2020-12 validator.
"""

import argparse
import base64
import hashlib
import json
import re
import sys
import zlib
from datetime import datetime
from pathlib import Path


def resolve(schema, root, ref):
    if not ref.startswith("#/"):
        raise ValueError(f"external $ref is unsupported: {ref}")
    node = root
    for part in ref[2:].split("/"):
        node = node[part.replace("~1", "/").replace("~0", "~")]
    return node


def same(a, b):
    return type(a) is type(b) and a == b


def validate(value, schema, root, path="$"):
    errors = []
    if "$ref" in schema:
        return validate(value, resolve(schema, root, schema["$ref"]), root, path)
    if "const" in schema and not same(value, schema["const"]):
        errors.append(f"{path}: must equal {schema['const']!r}")
    if "enum" in schema and not any(same(value, item) for item in schema["enum"]):
        errors.append(f"{path}: value is not in enum")

    kind = schema.get("type")
    matches = {
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "number": isinstance(value, (int, float)) and not isinstance(value, bool),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
    }
    if kind and not matches[kind]:
        return errors + [f"{path}: expected {kind}"]

    if isinstance(value, dict):
        required = schema.get("required", [])
        errors += [f"{path}: missing required property {key!r}" for key in required if key not in value]
        props = schema.get("properties", {})
        if schema.get("additionalProperties") is False:
            errors += [f"{path}: unknown property {key!r}" for key in value if key not in props]
        for key, child in props.items():
            if key in value:
                errors += validate(value[key], child, root, f"{path}.{key}")
        count = len(value)
        if count < schema.get("minProperties", 0):
            errors.append(f"{path}: too few properties")
        if count > schema.get("maxProperties", sys.maxsize):
            errors.append(f"{path}: too many properties")

    if isinstance(value, list):
        if len(value) < schema.get("minItems", 0):
            errors.append(f"{path}: too few items")
        if len(value) > schema.get("maxItems", sys.maxsize):
            errors.append(f"{path}: too many items")
        if schema.get("uniqueItems"):
            canonical = [json.dumps(item, sort_keys=True, separators=(",", ":")) for item in value]
            if len(canonical) != len(set(canonical)):
                errors.append(f"{path}: items must be unique")
        for index, item in enumerate(value):
            errors += validate(item, schema.get("items", {}), root, f"{path}[{index}]")

    if isinstance(value, str):
        if "pattern" in schema and re.search(schema["pattern"], value) is None:
            errors.append(f"{path}: does not match required pattern")
        if schema.get("format") == "date-time":
            try:
                datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError:
                errors.append(f"{path}: invalid date-time")
        if schema.get("contentEncoding") == "base64":
            try:
                base64.b64decode(value, validate=True)
            except Exception:
                errors.append(f"{path}: invalid base64")

    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if value < schema.get("minimum", float("-inf")):
            errors.append(f"{path}: below minimum")
        if value > schema.get("maximum", float("inf")):
            errors.append(f"{path}: above maximum")
        if "exclusiveMinimum" in schema and value <= schema["exclusiveMinimum"]:
            errors.append(f"{path}: must exceed exclusive minimum")

    for subschema in schema.get("allOf", []):
        errors += validate(value, subschema, root, path)
    if "oneOf" in schema:
        outcomes = [validate(value, candidate, root, path) for candidate in schema["oneOf"]]
        if sum(not outcome for outcome in outcomes) != 1:
            errors.append(f"{path}: must match exactly one oneOf branch")
    if "not" in schema and not validate(value, schema["not"], root, path):
        errors.append(f"{path}: matches forbidden schema")
    if "if" in schema and not validate(value, schema["if"], root, path):
        errors += validate(value, schema.get("then", {}), root, path)
    return errors


def raw_integrity(document):
    if document.get("documentType") != "raw_session_chunk":
        return []
    try:
        compressed = base64.b64decode(document["payloadBase64"], validate=True)
        payload = zlib.decompress(compressed, wbits=-15)
    except Exception as exc:
        return [f"$: raw DEFLATE payload cannot be decoded: {exc}"]
    errors = []
    if len(payload) != document["uncompressedLength"]:
        errors.append("$: uncompressedLength does not match payload")
    if hashlib.sha256(payload).hexdigest() != document["uncompressedSha256"]:
        errors.append("$: uncompressedSha256 does not match payload")
    return errors


def semantic_integrity(document):
    """Rules that JSON Schema cannot express clearly across fields/documents."""
    errors = []
    kind = document.get("documentType")
    for field in ("sequence", "snapshotVersion", "frameCounter"):
        if field in document:
            try:
                if int(document[field]) > 18446744073709551615:
                    errors.append(f"$.{field}: exceeds uint64")
            except (TypeError, ValueError):
                pass
    if "observedAt" in document and "createdAt" in document:
        try:
            if datetime.fromisoformat(document["createdAt"].replace("Z", "+00:00")) < datetime.fromisoformat(document["observedAt"].replace("Z", "+00:00")):
                errors.append("$: createdAt must not precede observedAt")
        except ValueError:
            pass
    if kind == "raw_session_manifest":
        try:
            if datetime.fromisoformat(document["endedAt"].replace("Z", "+00:00")) <= datetime.fromisoformat(document["startedAt"].replace("Z", "+00:00")):
                errors.append("$: endedAt must be later than startedAt")
        except (KeyError, ValueError):
            pass
    if kind == "capability_snapshot":
        ids = [item.get("id") for item in document.get("capabilities", [])]
        if len(ids) != len(set(ids)):
            errors.append("$.capabilities: capability ids must be unique")
        for index, item in enumerate(document.get("capabilities", [])):
            blocked = item.get("state") != "available"
            if blocked != ("reasonCode" in item):
                errors.append(f"$.capabilities[{index}]: reasonCode is required exactly when unavailable")
            if item.get("state") == "available" and not item.get("modes"):
                errors.append(f"$.capabilities[{index}]: available capability needs at least one mode")
    if kind == "enrollment_phone_hello" and document.get("observedWatchNonce") == document.get("phoneNonce"):
        errors.append("$: phoneNonce must differ from watch nonce")
    return errors


def fixture_set_integrity(documents):
    """Validate relationships among valid fixtures, including complete raw sessions."""
    errors = []
    manifests = {d["sessionId"]: d for _, d in documents if d.get("documentType") == "raw_session_manifest"}
    chunks = {}
    for _, document in documents:
        if document.get("documentType") == "raw_session_chunk":
            chunks.setdefault(document["sessionId"], []).append(document)
    for session_id, manifest in manifests.items():
        members = sorted(chunks.get(session_id, []), key=lambda d: d["chunkIndex"])
        if len(members) != manifest["chunkCount"]:
            errors.append(f"session {session_id}: chunkCount does not match fixture set")
            continue
        if [d["chunkIndex"] for d in members] != list(range(len(members))):
            errors.append(f"session {session_id}: chunk indexes are not contiguous")
        if sum(d["sampleCount"] for d in members) != manifest["sampleCount"]:
            errors.append(f"session {session_id}: sampleCount does not match chunks")
        expected_start = 0
        uncompressed = []
        widths = {"float32_le": 4, "int16_le": 2, "int32_le": 4}
        bytes_per_sample = sum(widths[channel["sampleType"]] for channel in manifest["channels"])
        for chunk in members:
            if chunk["sampleStartIndex"] != expected_start:
                errors.append(f"session {session_id}: sample ranges are not contiguous")
            expected_start += chunk["sampleCount"]
            raw = zlib.decompress(base64.b64decode(chunk["payloadBase64"]), wbits=-15)
            if len(raw) != chunk["sampleCount"] * bytes_per_sample:
                errors.append(f"session {session_id}: raw byte count disagrees with channels/sampleCount")
            uncompressed.append(raw)
        if uncompressed and hashlib.sha256(b"".join(uncompressed)).hexdigest() != manifest["sessionSha256"]:
            errors.append(f"session {session_id}: sessionSha256 does not match ordered chunks")
    return errors


def main():
    here = Path(__file__).resolve()
    repo = here.parents[2]
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="*", type=Path, help="JSON documents; defaults to all fixtures")
    args = parser.parse_args()
    fixtures = repo / "contracts" / "fixtures"
    paths = args.paths or sorted(fixtures.rglob("*.json"))
    schemas = {
        "measurement": json.loads((repo / "contracts" / "measurement.schema.json").read_text()),
        "raw": json.loads((repo / "contracts" / "raw-session.schema.json").read_text()),
        "capability": json.loads((repo / "contracts" / "capability.schema.json").read_text()),
        "enrollment": json.loads((repo / "contracts" / "enrollment.schema.json").read_text()),
        "ble": json.loads((repo / "contracts" / "ble-message.schema.json").read_text()),
    }
    failures = 0
    valid_documents = []
    for path in paths:
        try:
            document = json.loads(path.read_text(), parse_constant=lambda token: (_ for _ in ()).throw(ValueError(f"non-finite number {token}")))
        except (json.JSONDecodeError, ValueError) as exc:
            expected_invalid = "invalid" in path.parts
            print(f"{'PASS' if expected_invalid else 'FAIL'} {path.relative_to(repo) if path.is_relative_to(repo) else path}")
            if not expected_invalid:
                failures += 1
                print(f"  invalid JSON: {exc}")
            continue
        document_type = document.get("documentType", "")
        schema_key = ("raw" if document_type.startswith("raw_session_") else
                      "capability" if document_type == "capability_snapshot" else
                      "enrollment" if document_type.startswith("enrollment_") else
                      "ble" if "ciphertextBase64" in document else "measurement")
        schema = schemas[schema_key]
        errors = validate(document, schema, schema) + raw_integrity(document) + semantic_integrity(document)
        expected_invalid = "invalid" in path.parts
        passed = bool(errors) if expected_invalid else not errors
        print(f"{'PASS' if passed else 'FAIL'} {path.relative_to(repo) if path.is_relative_to(repo) else path}")
        if not passed:
            failures += 1
            for error in errors or ["fixture expected invalid but validation succeeded"]:
                print(f"  {error}")
        elif not expected_invalid:
            valid_documents.append((path, document))
    set_errors = fixture_set_integrity(valid_documents)
    if set_errors:
        failures += 1
        print("FAIL valid fixture cross-document invariants")
        for error in set_errors:
            print(f"  {error}")
    else:
        print("PASS valid fixture cross-document invariants")
    print(f"\n{len(paths) - failures}/{len(paths)} fixture expectations passed")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
