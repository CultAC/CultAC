#!/usr/bin/env python3
"""Rewrite model registry tables (protocol/.../protocol/model/*.tsv.gz) from format 1 to the compact format 2.

Format 2 drops the implicit contiguous IDs and writes each block's states as one `block` row listing its
properties' values; ModelRegistryData expands the cartesian product with the last property varying fastest.
A block whose states are not exactly that product keeps explicit `state` rows. Every file is decoded again
and must reproduce the original registries and states exactly before it is written."""
import argparse
import gzip
import itertools
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("directory", type=Path, nargs="?",
                    default=Path(__file__).resolve().parents[1] / "protocol/src/main/resources/ac/cult/cultac/protocol/model")
args = parser.parse_args()


def parse_state(state):
    if "[" not in state:
        return state, []
    name, properties = state[:-1].split("[", 1)
    return name, [tuple(p.split("=", 1)) for p in properties.split(",")]


def expand(name, properties):
    if not properties:
        return [name]
    keys = [key for key, _ in properties]
    return [name + "[" + ",".join(f"{k}={v}" for k, v in zip(keys, combo)) + "]"
            for combo in itertools.product(*(values for _, values in properties))]


def decode_v1(lines):
    registries, states = {}, []
    for line in lines:
        fields = line.split("\t")
        if fields[0] == "registry":
            names = registries.setdefault(fields[1], [])
            assert int(fields[2]) == len(names)
            names.append(fields[3])
        else:
            assert fields[0] == "state" and int(fields[1]) == len(states)
            states.append(fields[2])
    return registries, states


def decode_v2(lines):
    registries, states = {}, []
    for line in lines:
        fields = line.split("\t")
        if fields[0] == "registry":
            registries.setdefault(fields[1], []).append(fields[2])
        elif fields[0] == "block":
            states.extend(expand(fields[1], [(k, v.split(",")) for k, v in (f.split("=", 1) for f in fields[2:])]))
        else:
            states.append(fields[1])
    return registries, states


def encode_states(states):
    rows, start = [], 0
    while start < len(states):
        name, properties = parse_state(states[start])
        end = start
        while end < len(states) and parse_state(states[end])[0] == name:
            end += 1
        group, keys = states[start:end], [key for key, _ in properties]
        values = {key: [] for key in keys}
        for state in group:
            for key, value in parse_state(state)[1]:
                if key in values and value not in values[key]:
                    values[key].append(value)
        if expand(name, [(key, values[key]) for key in keys]) == group:
            rows.append("\t".join(["block", name] + [f"{key}={','.join(values[key])}" for key in keys]))
        else:
            rows.extend(f"state\t{state}" for state in group)
        start = end
    return rows


for path in sorted(args.directory.glob("*.tsv.gz")):
    lines = gzip.decompress(path.read_bytes()).decode("utf-8").splitlines()
    header = lines[0].split("\t")
    if header[:2] == ["cult-model-registries", "2"]:
        print(f"{path.name}: already compact")
        continue
    assert header[:2] == ["cult-model-registries", "1"], f"{path.name}: unexpected header {lines[0]!r}"
    registries, states = decode_v1(lines[1:])
    rows = ["\t".join(["cult-model-registries", "2"] + header[2:])]
    rows += [f"registry\t{key}\t{name}" for key, names in registries.items() for name in names]
    rows += encode_states(states)
    if decode_v2(rows[1:]) != (registries, states):
        raise SystemExit(f"{path.name}: compact encoding does not round-trip")
    data = gzip.compress(("\n".join(rows) + "\n").encode("utf-8"), compresslevel=9, mtime=0)
    print(f"{path.name}: {path.stat().st_size} -> {len(data)} bytes")
    path.write_bytes(data)
