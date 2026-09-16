# MVP SPEC（原型版）

> 一页讲清原型做什么。术语与 ADR-0001 保持一致。

## 场景

在浏览器打开单页 → 上传文件 → 系统流式落盘并计算 SHA-256、写入元数据 → 列表分页展示 → 点开看详情 → 下载得到与原文件字节一致的内容 → 删除后记录与磁盘文件同时消失。

## 数据模型

表 `resource_item`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT IDENTITY | 主键 |
| original_name | VARCHAR(255) | 上传时的文件名（仅展示与下载用） |
| content_type | VARCHAR(128) | MIME 类型，缺省 `application/octet-stream` |
| size_bytes | BIGINT | 字节数 |
| sha256 | VARCHAR(64) | 小写十六进制，**非唯一** |
| storage_name | VARCHAR(64) | 落盘文件名（32 位随机十六进制） |
| created_at | BIGINT | epoch 毫秒（UTC） |

## 接口

| # | 方法 | 路径 | 请求 | 成功响应 |
| --- | --- | --- | --- | --- |
| 1 | POST | `/api/resources` | multipart 字段 `file` | `201` + 资源 JSON + `Location` |
| 2 | GET | `/api/resources?page=&size=` | 分页（默认 1/10，size 上限 100） | `200` + `{items,page,size,total}` |
| 3 | GET | `/api/resources/{id}` | — | `200` + 资源 JSON；不存在 `404` |
| 4 | GET | `/api/resources/{id}/content` | — | `200` 文件流 + `Content-Disposition` + `X-Content-SHA256`；`404` |
| 5 | DELETE | `/api/resources/{id}` | — | `204`（记录 + 物理文件）；`404` |

资源 JSON：

```json
{
  "id": 1,
  "originalName": "示例.pdf",
  "contentType": "application/pdf",
  "sizeBytes": 12345,
  "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "createdAt": "2026-09-16T02:00:00Z"
}
```

错误响应：`{"code":"NOT_FOUND","message":"资源不存在：1"}`

## 上传落盘流程（为什么这么写）

1. 流式读入 + `DigestInputStream` 同步计算 SHA-256（大文件不占内存）
2. 先写同目录临时文件，完成后 `ATOMIC_MOVE` 到最终名（半截文件不进库）
3. 落库失败则删除已落盘文件（不留孤儿）
4. 删除时**先删物理文件、再删记录**：文件删不掉就报错，避免「库里有、盘上无」

## 边界

- 单文件 ≤ 200MB；空文件拒绝（400）
- 同哈希允许多条记录（不去重）；哈希冲突设计留到 M1
- 单机单进程运行；H2 文件模式不支持多进程同时打开（原型可接受）
