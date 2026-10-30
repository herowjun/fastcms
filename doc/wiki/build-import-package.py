#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build-import-package.py — 把 doc/wiki/fastcms-site/ 多页静态原型打包为
FastCMS「设计稿先行 · zip 整站导入」管线的输入包（fastcms-site-import.zip）。

只读 doc/wiki/fastcms-site/，输出 doc/wiki/fastcms-site-import.zip。
不触碰任何源码。可重复执行（幂等）。

打包前转换（全部基于 ai-starter 代码核实的管线行为，2026-10-16）：
  T1 内联 JS：4 个外部 js 以 IIFE 包裹内联进 index.html body 尾部。
      原因：MockupConverter 只迁移「首页」的 inline script（extractInlineScripts 取
      首个非空页），body 尾部外部 <script src> 在转化中直接丢弃；
      合并后 ~11.5KB > 2048 阈值（EXTERNAL_SCRIPT_THRESHOLD）→ 自动外置为
      static/js/imported.js，由 _layout.html 全局引用（所有页面生效）。
      IIFE 包裹防跨文件顶层声明冲突（实测 home.js/detail.js 有 s/i 重名）。
  T2 导航锚点抑制：index 导航 4 个页内锚点 href（index.html#features/#ai/
      #pricing/#stack）改写为 "#"。
      原因：zip 导入无 site-ia.json，MockupConverter.deriveNavMenus 会把 nav 中
      每个非首页链接派生 page_<slug> 单页（#features→page_features 等 4 个冗余页
      + 7 项菜单）；"#" 在 NAV_HOME_HREFS 白名单内 → 跳过派生。
      最终锚点（/#features 等）由导入后 AI 调整轮统一恢复（方案文档 §5.4）。
  T3 剔除 README.md 与 js/：扩展名白名单（FastcmsAiProperties.Import.allowExts）
      不含 md，含 md 会直接抛「不支持的文件类型」。
  T4 zip 条目顺序：index.html 必须第一（脚本提取取首个页）。

用法：  python doc/wiki/build-import-package.py
产物：  doc/wiki/fastcms-site-import.zip（5 个文件：3 html + 2 css）
"""
import io
import os
import re
import sys
import zipfile

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fastcms-site")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fastcms-site-import.zip")

# 内联进 index.html 的脚本（顺序即拼接顺序；IIFE 隔离作用域）
JS_ORDER = ["js/site.js", "js/home.js", "js/market.js", "js/detail.js"]

# 导航页内锚点：href → "#"（仅 4 个 IA 派生目标项；CTA 的 index.html#cta 不动——
# 其 class 含 btn → isNavChromeAnchor 跳过，不参与菜单口径）
NAV_ANCHOR_REWRITES = {
    "index.html#features": "#",
    "index.html#ai": "#",
    "index.html#pricing": "#",
    "index.html#stack": "#",
}


def read(rel):
    with open(os.path.join(BASE, rel), encoding="utf-8") as f:
        return f.read()


def inline_scripts_into_index(html):
    """T1：删除 3 个外部 <script src>，在 </body> 前插入 4 个 IIFE 内联块。"""
    ext = re.findall(r'<script src="js/[\w.-]+"></script>', html)
    assert len(ext) == 3, f"index.html 外部脚本数 {len(ext)} != 3: {ext}"
    for tag in ext:
        html = html.replace(tag, "")
    blocks = []
    for rel in JS_ORDER:
        src = read(rel).strip()
        blocks.append(f"<!-- 导入管线：原 {rel}（T1 内联，IIFE 隔离） -->\n"
                      f"<script>\n(function(){{\n{src}\n}})();\n</script>")
    assert "</body>" in html
    html = html.replace("</body>", "\n".join(blocks) + "\n</body>", 1)
    assert '<script src="js/' not in html
    return html


def strip_ext_scripts(html, expect):
    ext = re.findall(r'<script src="js/[\w.-]+"></script>', html)
    assert len(ext) == expect, f"外部脚本数 {len(ext)} != {expect}: {ext}"
    for tag in ext:
        html = html.replace(tag, "")
    return html


def rewrite_nav_anchors(html, page):
    """T2：改写导航 4 锚点 href → '#'。仅作用于 <nav>…</nav> 块。"""
    m = re.search(r"(<nav[^>]*>.*?</nav>)", html, re.S)
    assert m, f"{page}: 未找到 nav 块"
    nav = m.group(1)
    changed = 0
    for old, new in NAV_ANCHOR_REWRITES.items():
        token = f'href="{old}"'
        if token in nav:
            nav = nav.replace(token, f'href="{new}"')
            changed += 1
    assert changed == 4, f"{page}: 导航锚点改写 {changed} != 4"
    return html[:m.start()] + nav + html[m.end():]


def main():
    index = read("index.html")
    plugins = read("plugins.html")
    detail = read("plugin-detail.html")
    css_site = read("css/site.css")
    css_pages = read("css/pages.css")

    index = inline_scripts_into_index(rewrite_nav_anchors(index, "index.html"))
    plugins = strip_ext_scripts(rewrite_nav_anchors(plugins, "plugins.html"), 2)
    detail = strip_ext_scripts(rewrite_nav_anchors(detail, "plugin-detail.html"), 2)

    # 最终断言
    assert index.count("<script>") == 4, "index 内联脚本块应为 4"
    assert index.count("</script>") == 4
    for name, h in (("plugins", plugins), ("detail", detail)):
        assert "<script" not in h, f"{name} 仍含 script 标签"
        nav_part = h.split("</nav>")[0]
        for old in NAV_ANCHOR_REWRITES:
            assert f'href="{old}"' not in nav_part, f"{name} 导航仍有未改写的 IA 锚点 {old}"
    for name, h in (("index", index), ("plugins", plugins), ("detail", detail)):
        assert 'href="css/site.css"' in h and 'href="css/pages.css"' in h, f"{name} 丢失 css 引用"

    entries = [
        ("index.html", index),          # 必须第一：脚本提取取首个页
        ("plugins.html", plugins),
        ("plugin-detail.html", detail),
        ("css/site.css", css_site),
        ("css/pages.css", css_pages),
    ]
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, data in entries:
            zf.writestr(name, data)

    with zipfile.ZipFile(OUT) as zf:
        names = zf.namelist()
    assert names == [e[0] for e in entries], f"zip 条目顺序异常: {names}"
    size = os.path.getsize(OUT)
    print(f"OK  {OUT}  ({size/1024:.1f} KB, {len(names)} 文件)")
    for n in names:
        with zipfile.ZipFile(OUT) as zf:
            print(f"  {n:22s} {zf.getinfo(n).file_size/1024:7.1f} KB")


if __name__ == "__main__":
    sys.exit(main())
