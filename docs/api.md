# REST API

所有错误统一返回：

```json
{
  "code": "TASK_NOT_FOUND",
  "message": "任务不存在",
  "timestamp": "2026-08-04T03:00:00Z"
}
```

## 创建任务

```http
POST /api/tasks
Content-Type: multipart/form-data
```

表单字段 `files`，可重复上传一个或多个 OFD。成功返回 HTTP 202 和任务快照。

## 查询任务

```http
GET /api/tasks/{taskId}
```

外部状态为 `WAITING`、`CONVERTING`、`SUCCESS`、`FAILED`，`stage` 提供内部阶段，`progress` 为 0–100。`warnings` 是非致命限制，`files` 给出每个文件的成功或失败结果；成功结果中的 `pageCount` 是实际写入 DOCX 的完整页数。

## 下载

```http
GET /api/tasks/{taskId}/download
```

单文件任务返回 DOCX；批量任务返回 ZIP。任务未完成时返回 HTTP 400。

## 删除任务

```http
DELETE /api/tasks/{taskId}
```

删除任务及其结果文件，成功返回 HTTP 204。

## 健康检查

```http
GET /api/health
```

返回服务版本、解析器、Java、操作系统和 CPU 架构信息，不包含文件正文或敏感内容。
