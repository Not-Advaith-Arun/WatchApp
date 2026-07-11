# Protocol interoperability guide

`contracts/ble-protocol.md` is normative. JSON schemas describe storage/interchange forms; BLE uses their deterministic-CBOR equivalents. JSON uint64 fields are decimal strings, while CBOR carries unsigned integers.

Both implementations must consume `contracts/vectors/protocol-v1.json` in tests and reproduce every byte. Never regenerate expected values inside the assertion with the production encoder. The vector splits the 96-byte enrollment output into phone-to-watch key `[0,32)`, watch-to-phone key `[32,64)`, and rekey secret `[64,96)`.

Raw sample bytes concatenate channel values in manifest order and samples in chronological order, using the declared little-endian sample type. Chunks use raw DEFLATE without zlib/gzip wrappers. Integrity is checked per chunk after decompression and then across all uncompressed chunks in index order.

Compatibility rules:

- Reject a different protocol major before application sync.
- Negotiate the highest mutually supported minor and ignore unknown optional payload keys.
- Treat unknown enum values as unsupported input, not as a fallback sensor.
- Persist receive high-water marks and sender counters transactionally.
- Apply the 64 KiB ordinary and 8 MiB reassembled frame limits before allocation.
