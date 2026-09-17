# 仓鼠 MVP 原型（cangshu-mvp）

> 可丢弃演示原型：只验证「上传 → 元数据 → 列表/详情 → 下载 → 删除」最小闭环，**不作为 M1 基线**。
> 决策依据：项目内部决策记录，与 2026-09-11 技术选型会议结论对齐（Flyway 不引入、ORM 待定、对象存储不引入等）。

## 技术栈

Java 21 · Spring Boot 3.5.16 · Maven · MyBatis-Plus 3.5.17 · H2 2.3.232（文件模式，Spring Boot 托管版本）· 单页 HTML（无框架）

## 数据与文件位置（默认在仓库目录下，可整体删除）

数据根目录由配置项 `cangshu.data-dir` 决定，默认 `./cangshu-data`（相对**启动进程时的工作目录**，按下面的命令即在仓库目录下）。下表路径均相对仓库目录：

| 内容 | 默认路径 |
| --- | --- |
| 上传文件 | `<仓库目录>/cangshu-data/files/<32位随机名>` |
| H2 数据库 | `<仓库目录>/cangshu-data/db/cangshu.mv.db` |
| 清理 | 停掉进程后删除数据目录（在仓库根目录执行 `Remove-Item -Recurse -Force cangshu-data`），下次启动自动重建 |

要把数据放到仓库之外（推荐长期演示，便于隔离与清理），设环境变量 `CANGSHU_DATA_DIR` 指向任意目录即可，无需改代码：

```powershell
$env:CANGSHU_DATA_DIR = "<你的数据目录>"
```

## 本地运行

```powershell
$env:JAVA_HOME = "<你的 JDK 21 路径>"   # 只要求 JDK 21，发行版不限
# 若 PATH 上的 java 不是 21，下面的 java 命令请改用 "<你的 JDK 21 路径>\bin\java"

# 打包（跳过测试；原型以冒烟脚本验收）
mvn -DskipTests package

# 启动
java -jar target\cangshu-mvp-0.0.1-SNAPSHOT.jar

# 或开发期直接跑
mvn spring-boot:run
```

打开 <http://127.0.0.1:8080/>。

## 接口

JSON 字段 camelCase；时间为 ISO-8601 UTC；`sha256` 为小写十六进制，仅作完整性校验、**不作唯一键**。

| # | 方法 | 路径 | 说明 | 成功 | 失败 |
| --- | --- | --- | --- | --- | --- |
| 1 | POST | `/api/resources` | multipart `file`，流式写入 + SHA-256 | `201` + 资源 JSON | `400` 空文件 / `413` 超限 |
| 2 | GET | `/api/resources?page=&size=` | 分页列表（默认 1/10，size 上限 100） | `200` | — |
| 3 | GET | `/api/resources/{id}` | 详情 | `200` | `404` |
| 4 | GET | `/api/resources/{id}/content` | 下载（`Content-Disposition` + `X-Content-SHA256`） | `200` | `404` |
| 5 | DELETE | `/api/resources/{id}` | 删除记录 + 物理文件 | `204` | `404` |

示例：

```json
// GET /api/resources?page=1&size=10
{
  "items": [
    {
      "id": 1,
      "originalName": "示例.pdf",
      "contentType": "application/pdf",
      "sizeBytes": 12345,
      "sha256": "9f86d081...0f00a08",
      "createdAt": "2026-09-16T02:00:00Z"
    }
  ],
  "page": 1,
  "size": 10,
  "total": 1
}
```

```json
// 错误
{"code": "NOT_FOUND", "message": "资源不存在：1"}
```

## 验收脚本（两套，都在应用启动后另开一个终端执行）

### 接口冒烟：24 项

```powershell
# 应用启动后另开一个终端
node tools/smoke-test.mjs
```

覆盖上传 → 列表 → 详情 → 下载（SHA-256 一致）→ 删除（记录 + 物理文件）→ 边界。脚本逐步打印 PASS/FAIL，任何一步失败即退出码 1。验收口径见 `docs/开发宪章.md` 第 5 节。

### 页面交互端到端：12 项

```powershell
# 前置：Python 3.10+（验证环境 3.12）+ playwright
python -m pip install playwright

# 应用启动后另开一个终端执行
python tools\ui-e2e.py
```

覆盖真实浏览器里的上传 → 列表 → 详情 → 下载 → 删除，含「删除确认框写明不动原文件」。最后打印「全部通过：12 项」，整页截图落在 `target/ui-artifacts/ui-after.png`。

- 浏览器：默认按 **本机 Edge → 本机 Chrome → Playwright 自带 chromium** 顺序自动探测（自带 chromium 需先 `python -m playwright install chromium`）；要固定通道设 `$env:CANGSHU_BROWSER_CHANNEL = "msedge"`。
- 换地址/端口：`python tools\ui-e2e.py http://127.0.0.1:9090`，或设 `$env:CANGSHU_BASE_URL`（默认 `http://127.0.0.1:8080`）。
- 中间产物目录：默认 `<仓库目录>/target/ui-artifacts/`（`target/` 被忽略，不进仓库），可用 `$env:CANGSHU_E2E_ARTIFACTS_DIR` 改到别处。

## 刻意不做

预览、逻辑删除、权限/多用户、去重、Flyway 迁移、对象存储 —— 属 M1 或后续，原型不引入。

## 两个已知事项（原型期结论）

- **H2 不追最新**：曾用 2.5.250，强杀 JVM 后其 MVStore 出现 `flush` panic，已提交记录不再落盘；回退到 Spring Boot 托管的 2.3.232 后，同样的强杀重启流程数据完好。
- **停止服务尽量用 Ctrl+C**：直接强杀进程虽然已验证不丢数据，但让 Spring 正常关闭更稳妥。
