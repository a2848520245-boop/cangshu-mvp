#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""仓鼠 MVP 原型 · 页面交互端到端验收（12 项）

在真实浏览器里走一遍页面闭环：遮罩层不挡点击 → 上传 → 列表 → 详情 → 下载 → 删除，
与 tools/smoke-test.mjs（接口 24 项）互补：那一套只打 HTTP，这一套验证页面交互与确认框文案。

用法：
    python tools/ui-e2e.py                            # 默认 http://127.0.0.1:8080
    python tools/ui-e2e.py http://127.0.0.1:9090      # 换端口（端口被占用时）
    $env:CANGSHU_BASE_URL = "http://127.0.0.1:9090"   # 等价的环境变量写法

前置条件（缺一不可）：
    1) 应用已启动：见 README「本地运行」（默认 8080 端口）。
    2) Python 3.10+（验证环境 3.12）与 Playwright：
           python -m pip install playwright
    3) 浏览器：默认自动探测 —— 依次尝试本机 Microsoft Edge（channel="msedge"）→ 本机 Chrome
       → Playwright 自带 chromium（后者需先 python -m playwright install chromium）。
       只想固定某一个通道：$env:CANGSHU_BROWSER_CHANNEL = "msedge"（或 "chrome"、"auto"）。
       Playwright 按通道名自行定位安装位置，本脚本不写死任何浏览器可执行文件路径。

中间产物（上传样本、下载文件、整页截图）默认落在 <仓库目录>/target/ui-artifacts/
（target/ 已被 .gitignore 忽略，不污染仓库），可用 $env:CANGSHU_E2E_ARTIFACTS_DIR 改到任意目录；
默认目录不可写时回退到系统临时目录。脚本不写死任何盘符。

退出码：12 项全通过为 0，任一项失败为 1。断言条目固定为 12 项（含「确认框写明不动原文件」），
验收口径见 docs/开发宪章.md 第 5 节。
"""
import os
import re
import sys
import tempfile
from pathlib import Path

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import sync_playwright

DEFAULT_BASE = "http://127.0.0.1:8080"
REPO_ROOT = Path(__file__).resolve().parent.parent
SAMPLE_NAME = "ui-sample-仓鼠.txt"


def resolve_base_url():
    raw = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("CANGSHU_BASE_URL", DEFAULT_BASE)
    return raw.rstrip("/") + "/"


def resolve_artifacts_dir():
    override = (os.environ.get("CANGSHU_E2E_ARTIFACTS_DIR") or "").strip()
    candidates = ([Path(override)] if override else []) + [
        REPO_ROOT / "target" / "ui-artifacts",
        Path(tempfile.gettempdir()) / "cangshu-ui-e2e",
    ]
    for candidate in candidates:
        try:
            candidate.mkdir(parents=True, exist_ok=True)
            return candidate
        except OSError as exc:
            print("提示：产物目录不可用 " + str(candidate) + "（" + str(exc) + "），换下一个候选")
    raise SystemExit("无法创建产物目录：" + " / ".join(str(c) for c in candidates))


def launch_browser(playwright):
    preferred = (os.environ.get("CANGSHU_BROWSER_CHANNEL") or "auto").strip()
    channels = ["msedge", "chrome", None] if preferred.lower() in ("", "auto") else [preferred]
    failures = []
    for channel in channels:
        label = channel if channel else "playwright-chromium"
        try:
            if channel:
                browser = playwright.chromium.launch(channel=channel, headless=True)
            else:
                browser = playwright.chromium.launch(headless=True)
            print("浏览器通道：" + label)
            return browser
        except PlaywrightError as exc:
            text = str(exc).strip()
            failures.append(label + " → " + (text.splitlines()[0][:160] if text else exc.__class__.__name__))
    print("没有可用浏览器：")
    for item in failures:
        print("  - " + item)
    raise SystemExit("请安装 Edge 或 Chrome；或执行 python -m playwright install chromium 后重跑。")


BASE = resolve_base_url()
ARTIFACTS = resolve_artifacts_dir()
sample = ARTIFACTS / SAMPLE_NAME
saved = ARTIFACTS / "ui-downloaded.txt"
shot = ARTIFACTS / "ui-after.png"

print("目标服务：" + BASE)
print("产物目录：" + str(ARTIFACTS))
print("")

sample.write_text("仓鼠 MVP 页面交互验收 " * 40, encoding="utf-8")

results = []
def check(name, ok, detail=""):
    results.append(ok)
    print(("  PASS  " if ok else "  FAIL  ") + name + (("   " + str(detail)) if detail else ""))

with sync_playwright() as p:
    browser = launch_browser(p)
    page = browser.new_page(viewport={"width": 1440, "height": 900})
    dialog_messages = []
    page.on("dialog", lambda d: (dialog_messages.append(d.message), d.accept()))
    page.goto(BASE, wait_until="networkidle")

    check("1. 遮罩层默认不可见", page.locator("#overlay").is_hidden())
    check("2. 列表已渲染（空态或数据）", page.locator("#rows tr").count() >= 1, page.locator("#listMeta").inner_text())

    before = page.locator("#rows tr").count()
    page.set_input_files("#fileInput", str(sample))
    page.wait_for_function("document.querySelector('#rows').innerText.includes('ui-sample')", timeout=15000)
    row = page.locator("#rows tr").filter(has_text="ui-sample").first
    check("3. 上传后列表出现该行", row.count() == 1)
    check("4. 上传区回显 SHA-256", "SHA-256" in page.locator("#dropFile").inner_text(),
          page.locator("#dropFile").inner_text()[:70])

    row.locator("button[data-act='detail']").click()
    page.wait_for_selector("#overlay:not([hidden])", timeout=5000)
    body = page.locator("#modalBody").inner_text()
    check("5. 点「详情」弹出模态", page.locator("#overlay").is_visible())
    check("6. 详情含完整 SHA-256", bool(re.search(r"[0-9a-f]{64}", body)))
    check("7. 详情含中文文件名", "ui-sample" in body and "仓鼠" in body, body.splitlines()[1] if len(body.splitlines()) > 1 else "")

    page.locator("#modalClose").click()
    check("8. 关闭按钮生效", page.locator("#overlay").is_hidden())

    with page.expect_download() as dl:
        page.locator("#rows tr").filter(has_text="ui-sample").first.locator("a", has_text="下载").click()
    download = dl.value
    download.save_as(str(saved))
    check("9. 点「下载」得到文件", saved.exists() and saved.read_bytes() == sample.read_bytes(),
          download.suggested_filename + " · " + str(saved.stat().st_size) + " bytes")

    page.locator("#rows tr").filter(has_text="ui-sample").first.locator("button[data-act='delete']").click()
    page.wait_for_function("!document.querySelector('#rows').innerText.includes('ui-sample')", timeout=10000)
    check("10. 点「删除」后列表移除该行", "ui-sample" not in page.locator("#rows").inner_text())
    check("10b. 确认框写明不动原文件", any("原文件不受影响" in m for m in dialog_messages),
          (dialog_messages[0].replace("\n", " / ") if dialog_messages else "无对话框"))
    check("11. 行数回到上传前", page.locator("#rows tr").count() == before,
          str(before) + " → " + str(page.locator('#rows tr').count()))

    page.screenshot(path=str(shot), full_page=True)
    browser.close()

failed = results.count(False)
print("")
print(("全部通过：" + str(len(results)) + " 项") if failed == 0 else ("失败 " + str(failed) + " 项 / 共 " + str(len(results)) + " 项"))
print("截图留档：" + str(shot))
raise SystemExit(0 if failed == 0 else 1)
