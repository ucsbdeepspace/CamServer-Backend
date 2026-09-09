#!/usr/bin/env python3
"""Remove retired web pages from an existing backend JAR without rebuilding other code.

Useful when production includes separately deployed backend changes. Normal future
builds should use `mvn clean package`; the legacy controller/resources are removed
from source as well. The original JAR is never modified.
"""
import argparse
import hashlib
from pathlib import Path
import zipfile


def legacy(name):
    return name.startswith(("BOOT-INF/classes/templates/", "BOOT-INF/classes/static/")) or name == (
        "BOOT-INF/classes/edu/camserver/app/controller/PageController.class"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.source.resolve() == args.output.resolve() or args.output.exists():
        parser.error("output must be a new path, distinct from the source")
    with zipfile.ZipFile(args.source) as src:
        removed = [item.filename for item in src.infolist() if legacy(item.filename)]
        if not removed:
            parser.error("source contains no legacy UI")
        with zipfile.ZipFile(args.output, "x") as dst:
            dst.comment = src.comment
            for item in src.infolist():
                if not legacy(item.filename):
                    dst.writestr(item, src.read(item.filename))
        with zipfile.ZipFile(args.output) as result:
            expected = {n for n in src.namelist() if not legacy(n)}
            if set(result.namelist()) != expected:
                raise RuntimeError("unexpected output entries")
            for name in expected:
                if result.read(name) != src.read(name):
                    raise RuntimeError("non-UI entry changed: " + name)
    for name in removed:
        print("Removed", name)
    print("SHA256", hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
