#!/usr/bin/env python3
"""Map remaining identical candidates' deps to identical/diff files."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOKENS = [
    ("mc-1.20.1", "mc1201"),
    ("mc-1.21.1", "mc1211"),
    ("mc-26.2", "mc262"),
]


def collect(folder: str) -> dict[str, Path]:
    root = ROOT / f"platform-src/minecraft/{folder}/src/main"
    out: dict[str, Path] = {}
    for p in root.rglob("*"):
        if p.suffix not in {".clj", ".java"} or "mcver" in p.parts:
            continue
        parts = p.parts
        i = parts.index("li")
        rel = Path(*parts[i + 2 :]).as_posix()
        out[rel] = p
    return out


TREES = {tok: collect(folder) for folder, tok in TOKENS}


def norm_text(t: str) -> str:
    for tok in ("mc1201", "mc1211", "mc262"):
        t = t.replace(tok, "mcbase")
    return t


def identical(rel: str) -> bool:
    if not all(rel in TREES[t] for _, t in TOKENS):
        return False
    texts = [
        norm_text(TREES[t][rel].read_text(encoding="utf-8", errors="replace"))
        for _, t in TOKENS
    ]
    return texts[0] == texts[1] == texts[2]


def deps_of(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    deps: set[str] = set()
    deps |= set(re.findall(r"\[(cn\.li\.mc1201\.[a-zA-Z0-9_.\-]+)", text))
    deps |= set(re.findall(r"'(cn\.li\.mc1201\.[a-zA-Z0-9_.\-]+)", text))
    for block in re.findall(r":import\s*((?:\[[^\]]*\]|\s|#_[^\n]*)+)", text):
        deps |= set(re.findall(r"(cn\.li\.mc1201\.[a-zA-Z0-9_.]+)", block))
    return deps


def ns_to_rel(ns: str):
    rest = ns[len("cn.li.mc1201.") :]
    parts = rest.split(".")
    cands = [
        "/".join(parts) + ".java",
        "/".join(parts) + ".clj",
        "/".join(parts[:-1] + [parts[-1].replace("-", "_")]) + ".clj",
    ]
    for c in cands:
        if c in TREES["mc1201"]:
            return c
    prefix = "/".join(parts) + "/"
    hits = [r for r in TREES["mc1201"] if r.startswith(prefix)]
    return ("PACKAGE", hits[:5]) if hits else None


def main() -> None:
    cands = [
        "entity/hook/effect/ScriptedEffectHook.java",
        "entity/hook/effect/ScriptedEffectHooks.java",
        "entity/hook/marker/ScriptedMarkerHook.java",
        "entity/hook/marker/ScriptedMarkerHooks.java",
        "entity/hook/marker/OwnerFollowMarkerHook.java",
        "entity/hook/ray/ScriptedRayHook.java",
        "entity/hook/ray/ScriptedRayHooks.java",
        "client/MinecraftClientAccess.java",
        "block/SharedDynamicStateBlock.java",
        "block/BlockPlacementHelper.java",
        "block/AbstractDynamicStateBlock.java",
    ]
    print("=== candidate identical? ===")
    for c in cands:
        print(("IDENT" if identical(c) else "diff/miss"), c)

    blocked = (ROOT / "scripts/phase3a_remaining_identical.txt").read_text().splitlines()
    print("\n=== blocked dep resolution ===")
    for rel in blocked:
        if not rel.endswith(".clj") or rel not in TREES["mc1201"]:
            continue
        for d in sorted(deps_of(TREES["mc1201"][rel])):
            mapped = ns_to_rel(d)
            if mapped is None:
                print(f"{rel} -> {d} UNRESOLVED")
            elif isinstance(mapped, tuple):
                print(f"{rel} -> {d} PACKAGE {mapped[1]}")
            else:
                flag = "IDENT" if identical(mapped) else "DIFF"
                print(f"{rel} -> {d} {flag} {mapped}")


if __name__ == "__main__":
    main()
