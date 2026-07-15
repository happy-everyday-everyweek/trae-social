#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 7: 人设种子数据校验脚本。

校验项：
- 加载所有 personas_*.json
- 断言数量 >= 220
- 断言 username 唯一
- 断言 id 唯一
- 断言 displayName 唯一（UI 主要标识，重复会造成识别困难）
- 断言 (worldview, values, languageStyle) 三元组无完全重复
- 断言所有必要字段非空
- 断言 activeWindows 长度 = 24
- 输出各职业/年龄段/文化背景分布统计

注意：本脚本代码与注释中不包含任何 emoji。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Dict, List

REPO_ROOT = Path(__file__).resolve().parents[2]
PERSONAS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "personas"

REQUIRED_STR_FIELDS = [
    "id", "displayName", "username", "avatarSeed", "bio",
    "worldview", "values", "languageStyle",
    "profession", "ageRange", "culturalBackground",
]

EXPECTED_MIN_COUNT = 220
ACTIVE_WINDOW_SIZE = 24


def load_all_personas() -> List[dict]:
    """加载所有 personas_*.json 分片。"""
    personas: List[dict] = []
    files = sorted(PERSONAS_DIR.glob("personas_*.json"))
    for f in files:
        data = json.loads(f.read_text(encoding="utf-8"))
        if isinstance(data, list):
            personas.extend(data)
    return personas


def validate(personas: List[dict]) -> List[str]:
    """执行所有断言，返回错误信息列表（空列表表示全部通过）。"""
    errors: List[str] = []

    # 1. 数量 >= 220
    if len(personas) < EXPECTED_MIN_COUNT:
        errors.append(
            f"数量不足: {len(personas)} < {EXPECTED_MIN_COUNT}"
        )

    # 2. id 唯一
    ids = [p.get("id", "") for p in personas]
    seen_ids: set = set()
    dup_ids: List[str] = []
    for i in ids:
        if i in seen_ids:
            dup_ids.append(i)
        else:
            seen_ids.add(i)
    if dup_ids:
        errors.append(f"id 重复: {len(dup_ids)} 个 ({dup_ids[:3]}...)")

    # 3. username 唯一
    usernames = [p.get("username", "") for p in personas]
    seen_un: set = set()
    dup_un: List[str] = []
    for u in usernames:
        if u in seen_un:
            dup_un.append(u)
        else:
            seen_un.add(u)
    if dup_un:
        errors.append(
            f"username 重复: {len(dup_un)} 个 ({dup_un[:3]}...)"
        )

    # 3.1 displayName 唯一（UI 主要标识，重复造成识别困难）
    display_names = [p.get("displayName", "") for p in personas]
    seen_dn: set = set()
    dup_dn: List[str] = []
    for dn in display_names:
        if dn in seen_dn:
            dup_dn.append(dn)
        else:
            seen_dn.add(dn)
    if dup_dn:
        errors.append(
            f"displayName 重复: {len(dup_dn)} 个 ({dup_dn[:5]}...)"
        )

    # 4. 三元组无完全重复
    triples = [
        (p.get("worldview", ""), p.get("values", ""),
         p.get("languageStyle", ""))
        for p in personas
    ]
    seen_tr: set = set()
    dup_tr: List[tuple] = []
    for t in triples:
        if t in seen_tr:
            dup_tr.append(t)
        else:
            seen_tr.add(t)
    if dup_tr:
        errors.append(
            f"(worldview, values, languageStyle) 三元组重复: "
            f"{len(dup_tr)} 个"
        )

    # 5. 必要字段非空 + activeWindows 长度
    for idx, p in enumerate(personas):
        for field in REQUIRED_STR_FIELDS:
            val = p.get(field, "")
            if not isinstance(val, str) or not val.strip():
                errors.append(
                    f"[{idx}] 字段 {field} 为空 (id={p.get('id')})"
                )
        aw = p.get("activeWindows")
        if not isinstance(aw, list) or len(aw) != ACTIVE_WINDOW_SIZE:
            errors.append(
                f"[{idx}] activeWindows 长度 != {ACTIVE_WINDOW_SIZE} "
                f"(实际 {len(aw) if isinstance(aw, list) else 'N/A'}, "
                f"id={p.get('id')})"
            )
        # avatarSeed 应等于 username（约定）
        if p.get("avatarSeed") != p.get("username"):
            errors.append(
                f"[{idx}] avatarSeed != username (id={p.get('id')})"
            )
        # historicalTweets 数量 5-10
        ht = p.get("historicalTweets", [])
        if not isinstance(ht, list) or not (5 <= len(ht) <= 10):
            errors.append(
                f"[{idx}] historicalTweets 数量 {len(ht)} 不在 5-10 "
                f"(id={p.get('id')})"
            )

    return errors


def print_stats(personas: List[dict]) -> None:
    """输出各维度分布统计。"""
    prof_dist: Dict[str, int] = {}
    age_dist: Dict[str, int] = {}
    culture_dist: Dict[str, int] = {}
    style_dist: Dict[str, int] = {}
    tweet_total = 0
    for p in personas:
        prof_dist[p["profession"]] = prof_dist.get(p["profession"], 0) + 1
        age_dist[p["ageRange"]] = age_dist.get(p["ageRange"], 0) + 1
        culture_dist[p["culturalBackground"]] = \
            culture_dist.get(p["culturalBackground"], 0) + 1
        style_dist[p["languageStyle"]] = \
            style_dist.get(p["languageStyle"], 0) + 1
        tweet_total += len(p.get("historicalTweets", []))

    print("==== 分布统计 ====")
    print(f"人设总数: {len(personas)}")
    print(f"历史推文总数: {tweet_total} (人均 "
          f"{tweet_total / len(personas):.2f})")
    print(f"职业分布 (共 {len(prof_dist)} 种):")
    for k in sorted(prof_dist.keys()):
        print(f"  {k}: {prof_dist[k]}")
    print(f"年龄段分布 (共 {len(age_dist)} 档):")
    for k in sorted(age_dist.keys()):
        print(f"  {k}: {age_dist[k]}")
    print(f"文化背景分布 (共 {len(culture_dist)} 种):")
    for k in sorted(culture_dist.keys()):
        print(f"  {k}: {culture_dist[k]}")
    print(f"语言风格分布 (共 {len(style_dist)} 种):")
    for k in sorted(style_dist.keys()):
        print(f"  {k}: {style_dist[k]}")

    # 最小值校验摘要
    print("\n==== 最小覆盖校验 ====")
    prof_ok = all(v >= 8 for v in prof_dist.values())
    age_ok = all(v >= 40 for v in age_dist.values())
    culture_ok = all(v >= 30 for v in culture_dist.values())
    print(f"每个职业 >= 8: {'PASS' if prof_ok else 'FAIL'} "
          f"(min={min(prof_dist.values()) if prof_dist else 0})")
    print(f"每个年龄段 >= 40: {'PASS' if age_ok else 'FAIL'} "
          f"(min={min(age_dist.values()) if age_dist else 0})")
    print(f"每个文化背景 >= 30: {'PASS' if culture_ok else 'FAIL'} "
          f"(min={min(culture_dist.values()) if culture_dist else 0})")


def main() -> int:
    if not PERSONAS_DIR.exists():
        print(f"FAIL: 目录不存在 {PERSONAS_DIR}")
        return 1

    personas = load_all_personas()
    if not personas:
        print("FAIL: 未加载到任何人设数据")
        return 1

    print_stats(personas)

    print("\n==== 断言校验 ====")
    errors = validate(personas)
    if errors:
        print(f"FAIL: 共 {len(errors)} 个错误:")
        for e in errors[:30]:
            print(f"  - {e}")
        if len(errors) > 30:
            print(f"  ... 还有 {len(errors) - 30} 个错误未显示")
        return 1

    print("PASS: 所有断言通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
