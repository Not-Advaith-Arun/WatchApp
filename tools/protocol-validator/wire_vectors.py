#!/usr/bin/env python3
"""Generate/check dependency-free deterministic CBOR, KDF, SAS and fragmentation vectors."""
import argparse, hashlib, hmac, json, struct
from pathlib import Path

def cbor(value):
    def head(major, number):
        if number < 24: return bytes([(major << 5) | number])
        if number <= 0xff: return bytes([(major << 5) | 24, number])
        if number <= 0xffff: return bytes([(major << 5) | 25]) + struct.pack(">H", number)
        if number <= 0xffffffff: return bytes([(major << 5) | 26]) + struct.pack(">I", number)
        return bytes([(major << 5) | 27]) + struct.pack(">Q", number)
    if value is False: return b"\xf4"
    if value is True: return b"\xf5"
    if value is None: return b"\xf6"
    if isinstance(value, int): return head(0, value) if value >= 0 else head(1, -1-value)
    if isinstance(value, float): return b"\xfb" + struct.pack(">d", value)
    if isinstance(value, bytes): return head(2, len(value)) + value
    if isinstance(value, str):
        raw=value.encode(); return head(3,len(raw))+raw
    if isinstance(value, list): return head(4,len(value))+b"".join(cbor(v) for v in value)
    if isinstance(value, dict):
        entries=sorted(((cbor(k), cbor(v)) for k,v in value.items()), key=lambda p:(len(p[0]),p[0]))
        return head(5,len(entries))+b"".join(k+v for k,v in entries)
    raise TypeError(type(value))

def hkdf(ikm, salt, info, length):
    prk=hmac.new(salt,ikm,hashlib.sha256).digest(); out=b""; block=b""
    for i in range(1,(length+31)//32+1):
        block=hmac.new(prk,block+info+bytes([i]),hashlib.sha256).digest(); out+=block
    return out[:length]

def vectors():
    header={0:1,1:0,2:21,3:0,4:1,5:42}
    payload={0:"heart_rate",1:72.0,2:"{beats}/min",3:1783758612425,4:"good"}
    shared=bytes(range(1,33)); watch=bytes(range(16)); phone=bytes(range(16,32))
    salt=hashlib.sha256(watch+phone).digest(); info=b"WatchApp enrollment v1"+bytes(range(65))+bytes(range(65,130))
    keymat=hkdf(shared,salt,info,96); transcript=hashlib.sha256(b"WatchApp deterministic enrollment transcript v1").digest()
    sas=int.from_bytes(hmac.new(keymat[64:96],b"SAS"+transcript,hashlib.sha256).digest()[:4],"big")%1000000
    frame=cbor(header); fragments=[]; message_id=0x1234
    for index in range((len(frame)+6)//7):
        part=frame[index*7:(index+1)*7]; fragments.append((struct.pack(">HHH",message_id,index,(len(frame)+6)//7)+part).hex())
    uid=bytes.fromhex("018f47a24cf47a91a3123d3a7d2a80b1"); device=bytes.fromhex("018f47a29ea17c22b5154a013abf8539")
    measurement={0:uid,1:device,2:43,3:"heart_rate",4:72.0,5:"{beats}/min",6:1783758612425,7:"wear_health_services",8:"good",9:[]}
    layouts={
      "commandStartMeasurement":cbor({0:"start_measurement",1:{0:"heart_rate"}}).hex(),
      "commandStartRaw":cbor({0:"start_raw",1:{0:"ecg",1:30}}).hex(),
      "measurement":cbor(measurement).hex(), "measurementBatch":cbor([measurement]).hex(),
      "status":cbor({0:87,1:3,2:1,3:1783758612000}).hex(),
      "syncRequest":cbor({0:42}).hex(), "syncAck":cbor({0:[uid],1:43}).hex(),
      "rawManifest":cbor({0:"raw_manifest",1:uid,2:device,3:"ecg",4:1783758610000,5:1783758640000,6:500.0,7:["ecg"],8:1,9:"00"*32}).hex(),
      "rawChunk":cbor({0:"raw_chunk",1:uid,2:0,3:"11"*32,4:b"\x01\x02\x03",5:24}).hex(),
      "rawMissingRequest":cbor({0:"raw_missing",1:uid,2:[0,2]}).hex(),
      "rawAck":cbor({0:"raw_ack",1:uid,2:"00"*32}).hex(),
      "rawSamples":cbor([[0.125,-0.25],[0.5,-1.0]]).hex()
    }
    return {"version":1,"canonicalCbor":{"headerHex":frame.hex(),"liveValuePayloadHex":cbor(payload).hex()},"payloadLayouts":layouts,
      "nonce":{"keyEpoch":1,"frameCounter":"42","hex":(struct.pack(">IQ",1,42)).hex()},
      "enrollment":{"sharedSecretHex":shared.hex(),"saltHex":salt.hex(),"infoHex":info.hex(),"keyMaterialHex":keymat.hex(),"transcriptSha256":transcript.hex(),"sas":f"{sas:06d}"},
      "fragmentation":{"messageId":message_id,"fragmentPayloadLimit":7,"fragmentsHex":fragments}}

def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--check",action="store_true"); args=parser.parse_args()
    path=Path(__file__).resolve().parents[2]/"contracts"/"vectors"/"protocol-v1.json"
    rendered=json.dumps(vectors(),indent=2)+"\n"
    if args.check:
        if not path.exists() or path.read_text()!=rendered:
            print(f"FAIL {path}: regenerate with --print and apply the result"); return 1
        print("PASS deterministic protocol vectors"); return 0
    print(rendered,end="")
if __name__=="__main__": raise SystemExit(main())
