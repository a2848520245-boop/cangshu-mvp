# 仓鼠 MVP 原型（cangshu-mvp）

> 可丢弃演示原型：只验证「上传 → 元数据 → 列表/详情 → 下载 → 删除」最小闭环，**不作为 M1 基线**。
> 决策依据：笔记库 `ADR-0001-项目启动与技术栈裁决.md`，并与 2026-09-11 导师技术选型会议原话对齐（Flyway 不引入、ORM 待定等）。

## 技术栈

Java 21 · Spring Boot 3.5.16 · Maven · MyBatis-Plus 3.5.17 · H2 2.3.232（文件模式，Spring Boot 托管版本）· 单页 HTML（无框架）

## 数据与文件位置（全部在库外，可整体删除）

| 内容 | 路径 |
| --- | --- |
| 上传文件 | `E:\AgentWork\CangShu\cangshu-data\files\<32位随机名>` |
| H2 数据库 | `E:\AgentWork\CangShu\cangshu-data\db\cangshu.mv.db` |
| 清理 | 停掉进程后 `Remove-Item -Recurse -Force E:\AgentWork\CangShu\cangshu-data`（下次启动自动重建） |

## 本地运行

```powershell
$env:JAVA_HOME = "E:\JDK\jdk-21\jdk-21.0.12.1+1"

# 打包（跳过测试；原型以冒烟脚本验收）
mvn -DskipTests package

# 启动
java -jar target\cangshu-mvp-0.0.1-SNAPSHOT.jar

# 或开发期直接跑
mvn spring-boot:run
```

打开 <http://127.0.0.1:8080/>。

## 接口（供导师查阅）

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

## 验收冒烟（上传/列表/详情/下载哈希/删除物理文件）

```powershell
# 应用启动后另开一个终端
node tools/smoke-test.mjs
```

脚本逐步打印 PASS/FAIL，任何一步失败即退出码 1。验收口径见 `docs/开发宪章.md` 第 5 节。

## 刻意不做

预览、逻辑删除、权限/多用户、去重、Flyway 迁移、对象存储 —— 属 M1 或后续，原型不引入。

## 两个已知事项（原型期结论）

- **H2 不追最新**：曾用 2.5.250，强杀 JVM 后其 MVStore 出现 `flush` panic，已提交记录不再落盘；回退到 Spring Boot 托管的 2.3.232 后，同样的强杀重启流程数据完好。
- **停止服务尽量用 Ctrl+C**：直接强杀进程虽然已验证不丢数据，但让 Spring 正常关闭更稳妥。
