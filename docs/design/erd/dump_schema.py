# -*- coding: utf-8 -*-
import json
from pathlib import Path
S = json.loads(Path(r"D:\G14(1)\docs\design\erd\schema.json").read_text(encoding="utf-8"))
out = []
for n, d in S.items():
    out.append("%s | %s | %s | %d cols" % (n, d["cn"], d.get("module", ""), len(d["columns"])))
    for c in d["columns"]:
        out.append("    %-18s %-42s %-10s nn=%d def=%-8s %s" % (
            c["name"], c["type"], ",".join(c["keys"]) or "-", 1 if c["not_null"] else 0,
            str(c.get("default"))[:8], c["comment"]))
    for ix in d["indexes"]:
        out.append("    IDX %-22s %-40s uniq=%d pk=%d" % (ix["name"], ",".join(ix["cols"]), 1 if ix["unique"] else 0, 1 if ix["primary"] else 0))
Path(r"D:\G14(1)\docs\design\erd\schema_dump.txt").write_text("\n".join(out), encoding="utf-8")
print("ok", len(out))
