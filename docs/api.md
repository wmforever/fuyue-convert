# REST API

FormatConverter 的 API 以异步任务为中心：上传一个或多个文件，选择目标格式，后台执行转换，完成后下载单文件或 ZIP。

## 错误格式

所有错误统一返回：

```json
{
  "code": "TASK_NOT_FOUND",
  "message": "任务不存在",
  "timestamp": "2026-08-04T03:00:00Z"
}
```

队列满返回 HTTP 429 / `TASK_QUEUE_FULL`；数据盘低于配置水位返回 HTTP 507 / `INSUFFICIENT_STORAGE`；上传请求或单任务配额超限不会创建任务目录。

## 创建任务

```http
POST /api/tasks
Content-Type: multipart/form-data
```

表单字段：

- `files`：可重复上传一个或多个源文件；
- `targetFormat`：目标格式，当前开放 `docx`、`txt`、`pdf`、`xlsx`、`csv`、`png`、`jpg`，其中 `jpeg` 会按 `jpg` 处理；未传时默认 `docx`。

成功返回 HTTP 202 和任务快照。快照中包含 `sourceFormat`、`targetFormat`、任务状态、进度、警告和文件级结果。

当前 API 只用 `targetFormat` 选择目标格式，转换模式由注册的转换器决定。项目后续会引入显式 `mode=fidelity|editable`，让保真优先和可编辑优先可以由调用方选择。

## 转换能力

```http
GET /api/tasks/capabilities
```

返回当前服务已注册或规划的转换路线，例如：

```json
[
  {
    "id": "ofd-to-docx",
    "sourceFormat": "ofd",
    "targetFormat": "docx",
    "sourceLabel": "OFD",
    "targetLabel": "Word DOCX",
    "inputExtension": ".ofd",
    "outputExtension": ".docx",
    "description": "将文字型 OFD 转换为可编辑 Word 文档，保留段落、表格、图片和页面方向。",
    "status": "available",
    "qualityLevel": "beta",
    "strategy": "editable",
    "requires": [],
    "limitations": ["复杂签章、扫描页和厂商私有扩展需要更多样本验证"]
  }
]
```

`status=available` 表示当前服务可执行该路线，不代表该路线已经达到 `stable`。

`qualityLevel` 表示质量等级：`stable`、`beta`、`experimental`、`planned`。

`strategy` 表示默认转换策略：`editable`、`fidelity`、`data`、`extraction`、`content`、`compatibility`、`planned`。

`requires` 和 `limitations` 给出外部依赖和已知限制，调用方应在 UI 中明确展示。

`status=planned` 表示路线只展示规划，不开放执行。

## 查询任务

```http
GET /api/tasks/{taskId}
```

外部状态：

- `WAITING`
- `CONVERTING`
- `SUCCESS`
- `FAILED`
- `CANCELLED`

`stage` 提供内部阶段，`progress` 为 0 到 100。`warnings` 是非致命限制，例如字体替代、OCR 未配置或图像层保真兜底。`files` 给出每个文件的成功或失败结果；成功结果中的 `pageCount` 是目标文档实际写入页数。

## 下载

```http
GET /api/tasks/{taskId}/download
```

单文件任务返回目标格式文件；批量任务返回 ZIP。任务未完成时返回 HTTP 400。

## 取消任务

```http
POST /api/tasks/{taskId}/cancel
```

等待中或转换中的任务会进入 `CANCELLED`，正在执行的转换线程会被中断，且不会发布下载结果。已结束任务保持原状态并直接返回当前快照。

## 重试任务

```http
POST /api/tasks/{taskId}/retry
```

仅允许重试 `FAILED` 或 `CANCELLED` 任务。服务使用保留的原始上传内容创建一个新的任务 ID 并返回 HTTP 202，不复用旧任务的工作目录或不完整输出。失败/取消任务的原始上传保留到 `result-ttl`，重启后仍可重试；成功任务会立即删除原始上传，过期或主动删除的任务不可重试。

## 删除任务

```http
DELETE /api/tasks/{taskId}
```

删除任务及其输入、工作文件和结果文件，成功返回 HTTP 204。删除运行中任务会先请求取消，并由转换线程退出时完成目录清理，避免一边写入一边删除的竞态。

## 健康检查

```http
GET /api/health
```

返回服务版本、解析器、Java、操作系统、CPU 架构和 Office 引擎状态，不包含文件正文或敏感内容。

`office.available=true` 时，DOCX/XLSX/PPTX 到 PDF 以及部分 WPS/UOF 兼容路线会由本机 LibreOffice headless 执行；否则服务回退到 Java 内置基础转换或将对应路线标为规划中。
