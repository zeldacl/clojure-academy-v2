#!/usr/bin/env python3
"""Promote closed bootstrap/init_common into mcbase."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MC = [("mc-1.20.1", "mc1201"), ("mc-1.21.1", "mc1211"), ("mc-26.2", "mc262")]


def main() -> None:
    src = ROOT / "platform-src/minecraft/mc-1.20.1/src/main/clojure/cn/li/mc1201/bootstrap/init_common.clj"
    text = src.read_text(encoding="utf-8")
    # Use stable label (no version token)
    text = text.replace("cn.li.mc1201", "cn.li.mcbase").replace(
        '"mcbase-bootstrap"', '"mcbase-bootstrap"'
    )
    # original had mc1201-bootstrap → after replace becomes mcbase-bootstrap already
    dest = ROOT / "platform-src/minecraft/base/src/main/clojure/cn/li/mcbase/bootstrap/init_common.clj"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(text, encoding="utf-8", newline="\n")
    print("write", dest.relative_to(ROOT))

    for folder, ns in MC:
        p = ROOT / f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/bootstrap/init_common.clj"
        if p.exists():
            p.unlink()
            print("delete", p.relative_to(ROOT))

    reps = sorted(
        [
            ("cn.li.mc1201.bootstrap.init-common", "cn.li.mcbase.bootstrap.init-common"),
            ("cn.li.mc1211.bootstrap.init-common", "cn.li.mcbase.bootstrap.init-common"),
            ("cn.li.mc262.bootstrap.init-common", "cn.li.mcbase.bootstrap.init-common"),
        ],
        key=lambda x: -len(x[0]),
    )
    for path in (ROOT / "platform-src").rglob("*.clj"):
        if "/minecraft/base/" in path.as_posix().replace("\\", "/"):
            continue
        t = path.read_text(encoding="utf-8", errors="surrogateescape")
        o = t
        for a, b in reps:
            t = t.replace(a, b)
        if t != o:
            path.write_text(t, encoding="utf-8", errors="surrogateescape", newline="\n")
            print("rewrite", path.relative_to(ROOT))
    print("INIT_COMMON DONE")


if __name__ == "__main__":
    main()
