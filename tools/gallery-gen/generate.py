#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 6.1 - 虚拟账号配图本地图库生成脚本

为每个主题生成 25 张 SVG 占位图片（共 8 类，总计 200 张），同时生成
index.json 索引文件。

输出位置：
    app/src/main/assets/gallery/<theme>/<n>.svg
    app/src/main/assets/gallery/index.json

设计要点：
- 仅使用 Python 标准库，无外部依赖。
- 每张图 1080x1080，含 3-7 个随机几何图形（圆 / 矩形 / 三角形组合）。
- 左下角绘制主题名（英文），用于辨识。
- 每个主题有独特配色（landscape 绿色系、food 橙色系、city 灰蓝系、
  pet 棕黄系、sport 红色系、art 紫色系、tech 蓝色系、nature 绿青系）。
- 单张 SVG 目标 < 5KB（RISK-10）。
"""

from __future__ import annotations

import json
import math
import random
from pathlib import Path
from typing import Dict, List, Tuple

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------

# 项目根目录（脚本位于 <root>/tools/gallery-gen/generate.py）
ROOT = Path(__file__).resolve().parents[2]

# 资源输出根目录
ASSETS_DIR = ROOT / "app" / "src" / "main" / "assets" / "gallery"

# 画布尺寸
CANVAS_SIZE = 1080

# 每个主题生成的图片数量
IMAGES_PER_THEME = 25

# 主题定义：name -> (主色系列表, 背景渐变起止色)
# 颜色采用 HSL 派生，每类提供 6 个调色板色，便于几何图形配色。
THEMES: Dict[str, Dict] = {
    "landscape": {
        "label": "landscape",
        "background": ("#1f3b2c", "#3e6f4e"),
        "palette": ["#7cb342", "#aed581", "#558b2f", "#33691e", "#c5e1a5", "#9ccc65"],
    },
    "food": {
        "label": "food",
        "background": ("#7a3018", "#c75b2c"),
        "palette": ["#ff8a65", "#ffab91", "#e64a19", "#bf360c", "#ffcc80", "#ff7043"],
    },
    "city": {
        "label": "city",
        "background": ("#2c3e50", "#5d7a91"),
        "palette": ["#90a4ae", "#cfd8dc", "#546e7a", "#37474f", "#b0bec5", "#78909c"],
    },
    "pet": {
        "label": "pet",
        "background": ("#5d4037", "#a1733f"),
        "palette": ["#bcaaa4", "#d7ccc8", "#8d6e63", "#6d4c41", "#ffe0b2", "#a1887f"],
    },
    "sport": {
        "label": "sport",
        "background": ("#5b0f0f", "#b71c1c"),
        "palette": ["#ef5350", "#e57373", "#c62828", "#b71c1c", "#ffcdd2", "#ff8a80"],
    },
    "art": {
        "label": "art",
        "background": ("#3a1a5d", "#6a3aa0"),
        "palette": ["#ba68c8", "#ce93d8", "#7b1fa2", "#4a148c", "#e1bee7", "#ab47bc"],
    },
    "tech": {
        "label": "tech",
        "background": ("#0d2540", "#1565c0"),
        "palette": ["#42a5f5", "#64b5f6", "#1976d2", "#0d47a1", "#bbdefb", "#1e88e5"],
    },
    "nature": {
        "label": "nature",
        "background": ("#0d4036", "#1c8a73"),
        "palette": ["#26a69a", "#4db6ac", "#00695c", "#004d40", "#b2dfdb", "#80cbc4"],
    },
}

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------


def clamp(value: float, low: float, high: float) -> float:
    """将数值限制在 [low, high] 区间。"""
    return max(low, min(high, value))


def fmt(value: float, ndigits: int = 2) -> str:
    """格式化浮点数，避免出现 0.30000000000004 之类输出，减小 SVG 体积。"""
    rounded = round(value, ndigits)
    # 去掉无意义的尾零：1.50 -> 1.5，2.00 -> 2
    text = f"{rounded:.{ndigits}f}"
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text


def hex_to_rgb(hex_color: str) -> Tuple[int, int, int]:
    """#rrggbb -> (r, g, b)。"""
    h = hex_color.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def rgb_to_hex(rgb: Tuple[int, int, int]) -> str:
    """(r, g, b) -> #rrggbb。"""
    return "#{:02x}{:02x}{:02x}".format(*rgb)


def mix_color(c1: str, c2: str, ratio: float) -> str:
    """在两种 hex 颜色间按 ratio 线性混合，ratio 范围 [0,1]。"""
    r1, g1, b1 = hex_to_rgb(c1)
    r2, g2, b2 = hex_to_rgb(c2)
    ratio = clamp(ratio, 0.0, 1.0)
    mixed = (
        int(r1 + (r2 - r1) * ratio),
        int(g1 + (g2 - g1) * ratio),
        int(b1 + (b2 - b1) * ratio),
    )
    return rgb_to_hex(mixed)


# ---------------------------------------------------------------------------
# SVG 元素生成
# ---------------------------------------------------------------------------


def svg_header(width: int, height: int, background: Tuple[str, str]) -> str:
    """生成 SVG 文件头与背景渐变定义。"""
    top, bottom = background
    grad_id = "bg"
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" '
        f'width="{width}" height="{height}" viewBox="0 0 {width} {height}">\n'
        f'<defs>\n'
        f'<linearGradient id="{grad_id}" x1="0" y1="0" x2="0" y2="1">\n'
        f'<stop offset="0" stop-color="{top}"/>\n'
        f'<stop offset="1" stop-color="{bottom}"/>\n'
        f'</linearGradient>\n'
        f'</defs>\n'
        f'<rect width="{width}" height="{height}" fill="url(#{grad_id})"/>\n'
    )


def svg_footer() -> str:
    return "</svg>\n"


def gen_circle(rng: random.Random, palette: List[str]) -> str:
    """生成一个圆形元素。"""
    cx = rng.uniform(60, CANVAS_SIZE - 60)
    cy = rng.uniform(60, CANVAS_SIZE - 60)
    r = rng.uniform(50, 220)
    fill = rng.choice(palette)
    opacity = rng.uniform(0.35, 0.85)
    return (
        f'<circle cx="{fmt(cx)}" cy="{fmt(cy)}" r="{fmt(r)}" '
        f'fill="{fill}" fill-opacity="{fmt(opacity)}"/>\n'
    )


def gen_rect(rng: random.Random, palette: List[str]) -> str:
    """生成一个矩形元素（可带旋转）。"""
    w = rng.uniform(80, 320)
    h = rng.uniform(80, 320)
    x = rng.uniform(0, CANVAS_SIZE - w)
    y = rng.uniform(0, CANVAS_SIZE - h)
    fill = rng.choice(palette)
    opacity = rng.uniform(0.35, 0.85)
    angle = rng.uniform(0, 360)
    cx = x + w / 2
    cy = y + h / 2
    return (
        f'<rect x="{fmt(x)}" y="{fmt(y)}" width="{fmt(w)}" height="{fmt(h)}" '
        f'fill="{fill}" fill-opacity="{fmt(opacity)}" '
        f'transform="rotate({fmt(angle)} {fmt(cx)} {fmt(cy)})"/>\n'
    )


def gen_triangle(rng: random.Random, palette: List[str]) -> str:
    """生成一个三角形元素（polygon 三点）。"""
    cx = rng.uniform(120, CANVAS_SIZE - 120)
    cy = rng.uniform(120, CANVAS_SIZE - 120)
    r = rng.uniform(70, 240)
    # 随机起始角，再以 120 度间隔放置三个顶点
    a0 = rng.uniform(0, math.tau)
    points = []
    for i in range(3):
        a = a0 + i * (math.tau / 3)
        px = cx + r * math.cos(a)
        py = cy + r * math.sin(a)
        points.append(f"{fmt(px)},{fmt(py)}")
    fill = rng.choice(palette)
    opacity = rng.uniform(0.35, 0.85)
    return (
        f'<polygon points="{" ".join(points)}" '
        f'fill="{fill}" fill-opacity="{fmt(opacity)}"/>\n'
    )


SHAPE_GENERATORS = (gen_circle, gen_rect, gen_triangle)


def gen_label(label: str, palette: List[str]) -> str:
    """在左下角绘制主题标识文字。"""
    # 文字使用调色板中最浅的色，保证对比度
    text_color = mix_color(palette[-2], "#ffffff", 0.7)
    x = 36
    y = CANVAS_SIZE - 32
    return (
        f'<text x="{x}" y="{y}" '
        f'font-family="sans-serif" font-size="48" '
        f'fill="{text_color}" fill-opacity="0.85">{label}</text>\n'
    )


def gen_svg(rng: random.Random, theme_cfg: Dict) -> str:
    """生成单张 SVG 文本。"""
    parts: List[str] = []
    parts.append(svg_header(CANVAS_SIZE, CANVAS_SIZE, theme_cfg["background"]))

    n_shapes = rng.randint(3, 7)
    for _ in range(n_shapes):
        gen = rng.choice(SHAPE_GENERATORS)
        parts.append(gen(rng, theme_cfg["palette"]))

    parts.append(gen_label(theme_cfg["label"], theme_cfg["palette"]))
    parts.append(svg_footer())
    return "".join(parts)


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------


def ensure_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)


def main() -> None:
    ensure_dir(ASSETS_DIR)

    index: Dict[str, List[str]] = {}

    for theme_name, theme_cfg in THEMES.items():
        theme_dir = ASSETS_DIR / theme_name
        ensure_dir(theme_dir)

        files: List[str] = []
        # 每个主题使用固定种子，保证可复现
        seed = hash(theme_name) & 0xFFFFFFFF
        rng = random.Random(seed)

        for n in range(1, IMAGES_PER_THEME + 1):
            svg_text = gen_svg(rng, theme_cfg)
            file_name = f"{n}.svg"
            (theme_dir / file_name).write_text(svg_text, encoding="utf-8")
            files.append(file_name)

            size = len(svg_text.encode("utf-8"))
            if size >= 5 * 1024:
                # 超过 5KB 仅警告，仍保留（极少触发）
                print(f"[WARN] {theme_name}/{file_name} size={size}B > 5KB")

        index[theme_name] = files

    index_path = ASSETS_DIR / "index.json"
    index_path.write_text(
        json.dumps(index, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    total = sum(len(v) for v in index.values())
    print(
        f"[OK] generated {total} SVG(s) under {ASSETS_DIR}\n"
        f"[OK] index written to {index_path}"
    )


if __name__ == "__main__":
    main()
