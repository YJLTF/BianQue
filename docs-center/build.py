# 文档中心构建管线（S2 完整实现；当前提供 manifest 校验与搜索索引雏形）
#
# 输入：materials/arthas-packaging-4.3.5-doc.zip 的解包目录 docs/arthas-docs/{version}/
#       design/arthas-docs-manifest.json
# 输出：deploy/docs-site/{version}/search-index.json（供前端文档中心全文检索）

import json
import re
import sys
import html
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = "4.3.5"
UNPACK = ROOT / "docs" / "arthas-docs" / VERSION
MANIFEST = ROOT / "design" / "arthas-docs-manifest.json"
OUT = ROOT / "deploy" / "docs-site" / VERSION


def text_of(page: Path, limit: int = 4000) -> str:
    src = page.read_text(encoding="utf-8", errors="ignore")
    body = re.sub(r"<script.*?</script>", "", src, flags=re.S)
    body = re.sub(r"<style.*?</style>", "", body, flags=re.S)
    body = re.sub(r"<[^>]+>", " ", body)
    body = html.unescape(body)
    return re.sub(r"\s+", " ", body).strip()[:limit]


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    base = ROOT / manifest["unpack_root"]
    if not base.exists():
        print(f"[FAIL] 解包目录不存在：{base}")
        return 1

    index, missing = [], 0
    for page in manifest["pages"]:
        zh = base / page["zh_path"]
        if not zh.exists():
            missing += 1
            print(f"[WARN] 缺页：{page['zh_path']}")
            continue
        index.append({
            "slug": page["slug"],
            "title": page["title"],
            "category": page["category"],
            "risk": page["risk"],
            "zh_path": page["zh_path"],
            "en_path": page["en_path"],
            "text": text_of(zh),
        })

    OUT.mkdir(parents=True, exist_ok=True)
    out_file = OUT / "search-index.json"
    out_file.write_text(json.dumps(index, ensure_ascii=False), encoding="utf-8")
    print(f"[OK] 校验 {len(index)} 页（缺失 {missing}），索引已输出：{out_file}")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
