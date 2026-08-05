#!/usr/bin/env python3
"""Find promotable identical clusters (deps only to already-promoted or within cluster)."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def collect(root: Path) -> dict[str, Path]:
    out: dict[str, Path] = {}
    for p in root.rglob("*"):
        if p.suffix not in {".clj", ".java"} or "mcver" in p.parts:
            continue
        parts = p.parts
        try:
            i = parts.index("li")
            rel = Path(*parts[i + 2 :]).as_posix()
        except ValueError:
            continue
        if rel:
            out[rel] = p
    return out


def declared_ns(path: Path) -> str | None:
    text = path.read_text(encoding="utf-8", errors="replace")
    if path.suffix == ".clj":
        m = re.search(r"\(ns\s+(cn\.li\.[^\s]+)", text)
        return m.group(1) if m else None
    m = re.search(r"package\s+(cn\.li\.[a-zA-Z0-9_.]+)\s*;", text)
    return f"{m.group(1)}.{path.stem}" if m else None


def deps_of(path: Path, old_prefix: str) -> set[str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    deps: set[str] = set()
    if path.suffix == ".java":
        deps |= set(re.findall(r"import\s+(cn\.li\.[a-zA-Z0-9_.]+)\s*;", text))
    else:
        deps |= set(re.findall(r"\[(cn\.li\.[a-zA-Z0-9_.\-]+)", text))
        deps |= set(re.findall(r"'(cn\.li\.[a-zA-Z0-9_.\-]+)", text))
        for block in re.findall(r":import\s*((?:\[[^\]]*\]|\s|#_[^\n]*)+)", text):
            deps |= set(re.findall(r"(cn\.li\.[a-zA-Z0-9_.]+)", block))
        deps |= set(re.findall(rf"({re.escape(old_prefix)}\.[A-Za-z0-9_.]+)", text))
    return deps


def ns_to_cands(ns: str, token: str) -> list[str]:
    prefix = f"cn.li.{token}."
    if not ns.startswith(prefix):
        return []
    parts = ns[len(prefix) :].split(".")
    return [
        "/".join(parts) + ".java",
        "/".join(parts) + ".clj",
        "/".join(parts[:-1] + [parts[-1].replace("-", "_")]) + ".clj",
    ]


def resolve_rel(ns: str, tree: dict[str, Path], token: str) -> str | None:
    for c in ns_to_cands(ns, token):
        if c in tree:
            return c
    return None


def analyze(
    label: str,
    tree: dict[str, Path],
    promoted: dict[str, Path],
    identical: list[str],
    old_prefix: str,
    new_token: str,
) -> list[str]:
    old_token = old_prefix.split(".")[-1]
    promoted_ns = {declared_ns(p) for p in promoted.values()} - {None}
    ident = set(identical)
    graph: dict[str, set[str]] = {r: set() for r in ident}
    externals: dict[str, set[str]] = {r: set() for r in ident}

    version_prefixes = (
        "cn.li.mc1201",
        "cn.li.mc1211",
        "cn.li.mc262",
        "cn.li.neoforge1211",
        "cn.li.neoforge262",
    )

    for rel in ident:
        self_ns = declared_ns(tree[rel])
        for d in deps_of(tree[rel], old_prefix):
            if self_ns and (d == self_ns or d.startswith(self_ns + ".")):
                continue
            # already-promoted target namespace
            if d.startswith(f"cn.li.{new_token}") or d.startswith("cn.li.mcbase") or d.startswith(
                "cn.li.neoforgebase"
            ):
                continue
            if d.startswith("cn.li.mcmod") or d.startswith("cn.li.ac") or d.startswith("cn.li.acapi"):
                continue
            if not d.startswith(old_prefix) and any(d.startswith(p) for p in version_prefixes):
                externals[rel].add(d)
                continue
            if not d.startswith(old_prefix):
                continue
            new_ns = d.replace(old_prefix, f"cn.li.{new_token}")
            if new_ns in promoted_ns or any(new_ns.startswith(p + ".") for p in promoted_ns):
                continue
            dep_rel = resolve_rel(d, tree, old_token)
            if dep_rel and dep_rel in ident:
                graph[rel].add(dep_rel)
            elif dep_rel and dep_rel in promoted:
                continue
            else:
                externals[rel].add(d)

    def transitive(rel: str) -> set[str]:
        seen: set[str] = set()
        stack = [rel]
        while stack:
            cur = stack.pop()
            if cur in seen:
                continue
            seen.add(cur)
            stack.extend(graph[cur] - seen)
        return seen

    remaining = set(ident)
    batches: list[list[str]] = []
    while remaining:
        groups: list[set[str]] = []
        for rel in sorted(remaining):
            g = transitive(rel)
            if not g.issubset(remaining):
                continue
            if any(externals[x] for x in g):
                continue
            if all(graph[x].issubset(g) for x in g):
                groups.append(g)
        if not groups:
            break
        groups.sort(key=len, reverse=True)
        chosen = groups[0]
        batches.append(sorted(chosen))
        remaining -= chosen

    print(f"=== {label} ===")
    print(
        "promotable groups",
        len(batches),
        "files",
        sum(len(b) for b in batches),
    )
    for i, b in enumerate(batches, 1):
        print(f" batch{i} ({len(b)}):")
        for x in b:
            print("   +", x)
    print("still blocked", len(remaining))
    for rel in sorted(remaining):
        print(
            "  -",
            rel,
            "ext=",
            sorted(externals[rel])[:4],
            "id-deps=",
            sorted(graph[rel])[:4],
        )
    return [x for b in batches for x in b]


def main() -> None:
    mc_ident = [
        l
        for l in (ROOT / "scripts/phase3a_remaining_identical.txt").read_text().splitlines()
        if l
    ]
    neo_ident = [
        l
        for l in (ROOT / "scripts/phase4a_remaining_identical.txt").read_text().splitlines()
        if l
    ]
    mc_files = analyze(
        "MC",
        collect(ROOT / "platform-src/minecraft/mc-1.20.1/src/main"),
        collect(ROOT / "platform-src/minecraft/base/src/main"),
        mc_ident,
        "cn.li.mc1201",
        "mcbase",
    )
    neo_files = analyze(
        "NEO",
        collect(ROOT / "platform-src/loader/neoforge-1.21.1/src/main"),
        collect(ROOT / "platform-src/loader/neoforge-shared/src/main"),
        neo_ident,
        "cn.li.neoforge1211",
        "neoforgebase",
    )
    (ROOT / "scripts/phase3a_promotable_now.txt").write_text(
        "\n".join(mc_files) + ("\n" if mc_files else ""), encoding="utf-8"
    )
    (ROOT / "scripts/phase4a_promotable_now.txt").write_text(
        "\n".join(neo_files) + ("\n" if neo_files else ""), encoding="utf-8"
    )


if __name__ == "__main__":
    main()
