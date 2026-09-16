#!/usr/bin/env node
/**
 * cangshu-mvp 验收冒烟：覆盖 docs/开发宪章.md 第 5 节 1-4 项
 *   上传（中文名 + 二进制）→ 列表 → 详情 → 下载（SHA-256 一致）→ 删除（记录 + 物理文件）
 *
 * 用法：node tools/smoke-test.mjs [baseUrl]
 * 环境变量：CANGSHU_FILES_DIR  物理文件目录（默认 E:/AgentWork/CangShu/cangshu-data/files）
 */
import { createHash } from 'node:crypto';
import { readdir } from 'node:fs/promises';

const base = (process.argv[2] ?? 'http://127.0.0.1:8080').replace(/\/+$/, '');
const filesDir = process.env.CANGSHU_FILES_DIR ?? 'E:/AgentWork/CangShu/cangshu-data/files';

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok });
  console.log((ok ? '  PASS  ' : '  FAIL  ') + name + (detail ? '   ' + detail : ''));
}

function sha256(buffer) {
  return createHash('sha256').update(buffer).digest('hex');
}

async function body(res) {
  const text = await res.text();
  try { return { status: res.status, data: JSON.parse(text) }; } catch { return { status: res.status, data: text }; }
}

async function countFiles() {
  try {
    const entries = await readdir(filesDir);
    return entries.filter((name) => !name.startsWith('.upload-')).length;
  } catch {
    return -1;
  }
}

console.log('目标服务：' + base);
console.log('物理文件目录：' + filesDir);
console.log('');

const before = await countFiles();
check('0. 物理文件目录可访问', before >= 0, '当前 ' + before + ' 个文件');

// 1. 上传
const payload = Buffer.alloc(256 * 64);
for (let i = 0; i < payload.length; i += 1) payload[i] = i % 256;
const sampleName = '验收样本-仓鼠.bin';
const form = new FormData();
form.append('file', new Blob([payload], { type: 'application/octet-stream' }), sampleName);

const createdRes = await fetch(base + '/api/resources', { method: 'POST', body: form });
const created = await body(createdRes);
check('1. 上传返回 201', created.status === 201, 'HTTP ' + created.status);
const item = created.data ?? {};
check('1. 服务端 SHA-256 与本地一致', item.sha256 === sha256(payload), item.sha256);
check('1. 字节数一致', item.sizeBytes === payload.length, item.sizeBytes + ' bytes');
check('1. 中文文件名保留', item.originalName === sampleName, item.originalName);
check('1. 返回 Location 头', Boolean(createdRes.headers.get('location')), createdRes.headers.get('location') ?? '');

const afterUpload = await countFiles();
check('1. 物理文件已落盘', afterUpload === before + 1, before + ' → ' + afterUpload);

// 2. 列表
const list = await body(await fetch(base + '/api/resources?page=1&size=10'));
check('2. 列表返回 200', list.status === 200, 'HTTP ' + list.status);
check('2. 列表包含新记录', Array.isArray(list.data?.items) && list.data.items.some((r) => r.id === item.id));
check('2. 分页字段完整', ['page', 'size', 'total'].every((k) => typeof list.data?.[k] === 'number'),
  'page=' + list.data?.page + ' size=' + list.data?.size + ' total=' + list.data?.total);

// 3. 详情
const detail = await body(await fetch(base + '/api/resources/' + item.id));
check('3. 详情返回 200', detail.status === 200, 'HTTP ' + detail.status);
check('3. 详情哈希一致', detail.data?.sha256 === item.sha256);
check('3. createdAt 为 ISO-8601 UTC', /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/.test(detail.data?.createdAt ?? ''),
  detail.data?.createdAt);

// 4. 下载
const download = await fetch(base + '/api/resources/' + item.id + '/content');
const bytes = Buffer.from(await download.arrayBuffer());
const disposition = download.headers.get('content-disposition') ?? '';
check('4. 下载返回 200', download.status === 200, 'HTTP ' + download.status);
check('4. 下载字节数一致', bytes.length === payload.length, bytes.length + ' bytes');
check('4. 下载 SHA-256 一致（往返无损）', sha256(bytes) === sha256(payload), sha256(bytes));
check('4. 响应头带 X-Content-SHA256', download.headers.get('x-content-sha256') === item.sha256);
check('4. Content-Disposition 含 RFC 5987 文件名', /attachment/i.test(disposition) && /filename\*=UTF-8''/i.test(disposition),
  disposition);

// 5. 删除
const deleted = await fetch(base + '/api/resources/' + item.id, { method: 'DELETE' });
check('5. 删除返回 204', deleted.status === 204, 'HTTP ' + deleted.status);
const gone = await body(await fetch(base + '/api/resources/' + item.id));
check('5. 删除后详情 404', gone.status === 404, 'HTTP ' + gone.status);
const afterDelete = await countFiles();
check('5. 物理文件已删除', afterDelete === before, afterUpload + ' → ' + afterDelete);

// 6. 边界
const missing = await body(await fetch(base + '/api/resources/999999'));
check('6. 不存在资源返回 404 + NOT_FOUND', missing.status === 404 && missing.data?.code === 'NOT_FOUND',
  JSON.stringify(missing.data));

const emptyForm = new FormData();
emptyForm.append('file', new Blob([], { type: 'application/octet-stream' }), 'empty.txt');
const empty = await body(await fetch(base + '/api/resources', { method: 'POST', body: emptyForm }));
check('6. 空文件返回 400', empty.status === 400 && empty.data?.code === 'BAD_REQUEST', JSON.stringify(empty.data));
const afterEmpty = await countFiles();
check('6. 空文件未产生残留', afterEmpty === before, afterDelete + ' → ' + afterEmpty);

const failed = results.filter((r) => !r.ok).length;
console.log('');
console.log(failed === 0 ? '全部通过：' + results.length + ' 项' : '失败 ' + failed + ' 项 / 共 ' + results.length + ' 项');
process.exitCode = failed === 0 ? 0 : 1;
