#!/usr/bin/env python3
"""Analyze remaining Phase 3a/4a identical candidates for closed promotion clusters."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def collect(root: Path) -> dict[str, Path]:
    out: dict[str, Path] = {}
    for p in root.rglob("*"):
        if p.suffix not in {".clj", ".java"}:
            continue
        if "mcver" in p.parts:
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
    if not m:
        return None
    return f"{m.group(1)}.{path.stem}"


def require_deps(text: str) -> set[str]:
    deps: set[str] = set()
    for m in re.finditer(r"\[(cn\.li\.[a-zA-Z0-9_.\-]+)", text):
        deps.add(m.group(1))
    for m in re.finditer(r"'(cn\.li\.[a-zA-Z0-9_.\-]+)", text):
        deps.add(m.group(1))
    return deps


def java_imports(text: str) -> set[str]:
    return set(re.findall(r"import\s+(cn\.li\.[a-zA-Z0-9_.]+)\s*;", text))


def clj_java_imports(text: str) -> set[str]:
    deps: set[str] = set()
    for block in re.findall(r":import\s*((?:\[[^\]]*\]|\s|#_[^\n]*)+)", text):
        for m in re.finditer(r"(cn\.li\.[a-zA-Z0-9_.]+)", block):
            deps.add(m.group(1))
    return deps


def ns_to_candidates(ns: str, prefix: str) -> list[str]:
    """Map cn.li.PREFIX.a.b.c to possible relative paths."""
    if not ns.startswith(f"cn.li.{prefix}."):
        return []
    rest = ns[len(f"cn.li.{prefix}.") :]
    parts = rest.split(".")
    cands = ["/".join(parts) + ".java", "/".join(parts) + ".clj"]
    # clojure file uses underscores
    cands.append("/".join(parts[:-1] + [parts[-1].replace("-", "_")]) + ".clj")
    return cands


def analyze_neo() -> None:
    n1211 = collect(ROOT / "platform-src/loader/neoforge-1.21.1/src/main")
    shared = collect(ROOT / "platform-src/loader/neoforge-shared/src/main")
    ident = [
        l
        for l in (ROOT / "scripts/phase4a_remaining_identical.txt").read_text().splitlines()
        if l
    ]
    shared_ns = {declared_ns(p) for p in shared.values()} - {None}
    ident_ns = {
        declared_ns(n1211[rel]).replace("neoforge1211", "neoforgebase")
        for rel in ident
        if rel in n1211 and declared_ns(n1211[rel])
    }
    known = shared_ns | ident_ns
    known_rels = set(shared) | set(ident)

    closed: list[str] = []
    blocked: list[tuple[str, list[str]]] = []
    for rel in ident:
        if rel not in n1211:
            continue
        text = n1211[rel].read_text(encoding="utf-8", errors="replace")
        deps: set[str] = set()
        if rel.endswith(".java"):
            deps |= java_imports(text)
        else:
            deps |= require_deps(text)
            deps |= clj_java_imports(text)
        external: list[str] = []
        for d in sorted(deps):
            if not d.startswith("cn.li.neoforge"):
                continue
            db = d.replace("neoforge1211", "neoforgebase").replace(
                "neoforge262", "neoforgebase"
            )
            self_ns = declared_ns(n1211[rel])
            if self_ns and (
                d == self_ns
                or d.startswith(self_ns + ".")
                or self_ns.startswith(d + ".")
            ):
                continue
            if db in known or any(db.startswith(k + ".") for k in known if k):
                continue
            # resolve to file
            cands = ns_to_candidates(db, "neoforgebase")
            # also original versioned prefix in tree
            cands += ns_to_candidates(
                d.replace("neoforgebase", "neoforge1211")
                if "neoforgebase" in d
                else d,
                "neoforge1211",
            )
            if any(c in known_rels for c in cands):
                continue
            if any(c in n1211 and c not in known_rels for c in cands):
                external.append(d)
                continue
            # unresolved versioned symbol — treat as blocked
            if "neoforge1211" in d or "neoforge262" in d:
                external.append(d)
        if external:
            blocked.append((rel, external[:10]))
        else:
            closed.append(rel)

    print("NEO closed", len(closed))
    for x in closed:
        print("  +", x)
    print("NEO blocked", len(blocked))
    for rel, ext in blocked:
        print("  -", rel, "->", ", ".join(ext))


def analyze_mc() -> None:
    mc1201 = collect(ROOT / "platform-src/minecraft/mc-1.20.1/src/main")
    base = collect(ROOT / "platform-src/minecraft/base/src/main")
    ident = [
        l
        for l in (ROOT / "scripts/phase3a_remaining_identical.txt").read_text().splitlines()
        if l
    ]
    base_ns = {declared_ns(p) for p in base.values()} - {None}
    ident_ns = {
        declared_ns(mc1201[rel]).replace("mc1201", "mcbase")
        for rel in ident
        if rel in mc1201 and declared_ns(mc1201[rel])
    }
    known = base_ns | ident_ns
    known_rels = set(base) | set(ident)

    closed: list[str] = []
    blocked: list[tuple[str, list[str]]] = []
    for rel in ident:
        if rel not in mc1201:
            continue
        text = mc1201[rel].read_text(encoding="utf-8", errors="replace")
        deps: set[str] = set()
        if rel.endswith(".java"):
            deps |= java_imports(text)
        else:
            deps |= require_deps(text)
            deps |= clj_java_imports(text)
            # bare same-package Class names aren't caught; FQCN only
            deps |= set(re.findall(r"(cn\.li\.mc1201\.[A-Za-z0-9_.]+)", text))
        external: list[str] = []
        self_ns = declared_ns(mc1201[rel])
        for d in sorted(deps):
            if not d.startswith("cn.li.mc1201"):
                continue
            db = d.replace("mc1201", "mcbase")
            if self_ns and (d == self_ns or d.startswith(self_ns + ".")):
                continue
            if db in known or any(db.startswith(k + ".") for k in known if k):
                continue
            cands = ns_to_candidates(db, "mcbase") + ns_to_candidates(d, "mc1201")
            if any(c in known_rels for c in cands):
                continue
            if any(c in mc1201 and c not in known_rels for c in cands):
                external.append(d)
                continue
            # same-package short class via import cn.li.mc1201.block.* style not used
            external.append(d)
        if external:
            blocked.append((rel, external[:8]))
        else:
            closed.append(rel)

    print("MC closed", len(closed))
    for x in closed:
        print("  +", x)
    print("MC blocked", len(blocked))
    for rel, ext in blocked:
        print("  -", rel, "->", ", ".join(ext))


if __name__ == "__main__":
    analyze_neo()
    print("---")
    analyze_mc()
