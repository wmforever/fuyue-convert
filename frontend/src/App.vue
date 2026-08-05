<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'

const files = ref([])
const dragging = ref(false)
const uploadProgress = ref(0)
const task = ref(null)
const busy = ref(false)
const message = ref('')
let pollTimer

const canSubmit = computed(() => files.value.length > 0 && !busy.value)
const statusLabel = computed(() => ({ WAITING: '等待转换', CONVERTING: '正在转换', SUCCESS: '转换完成', FAILED: '转换失败' })[task.value?.status] || '')
const progress = computed(() => task.value?.progress ?? uploadProgress.value)

function startNewBatch() {
  clearTimeout(pollTimer)
  const previousTaskId = task.value?.taskId
  files.value = []
  task.value = null
  uploadProgress.value = 0
  busy.value = false
  message.value = ''
  if (previousTaskId) fetch(`/api/tasks/${previousTaskId}`, { method: 'DELETE' }).catch(() => {})
}

function accept(selected) {
  const incoming = Array.from(selected || []).filter(file => file.name.toLowerCase().endsWith('.ofd'))
  if (incoming.length && task.value && !busy.value) startNewBatch()
  const known = new Set(files.value.map(file => `${file.name}:${file.size}`))
  for (const file of incoming) if (!known.has(`${file.name}:${file.size}`)) files.value.push(file)
  if (incoming.length !== (selected?.length || 0)) message.value = '已忽略非 OFD 文件'
}

function drop(event) {
  dragging.value = false
  accept(event.dataTransfer.files)
}

function remove(index) { if (!busy.value) files.value.splice(index, 1) }
function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function submit() {
  busy.value = true
  message.value = ''
  task.value = null
  uploadProgress.value = 0
  const data = new FormData()
  files.value.forEach(file => data.append('files', file))
  try {
    const created = await upload(data)
    task.value = created
    poll()
  } catch (error) {
    message.value = error.message
    busy.value = false
  }
}

function upload(data) {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest()
    request.open('POST', '/api/tasks')
    request.upload.onprogress = event => { if (event.lengthComputable) uploadProgress.value = Math.round(event.loaded / event.total * 100) }
    request.onload = () => {
      let body = {}
      try { body = JSON.parse(request.responseText) } catch (_) { /* ignore */ }
      if (request.status >= 200 && request.status < 300) resolve(body)
      else reject(new Error(body.message || `上传失败（${request.status}）`))
    }
    request.onerror = () => reject(new Error('无法连接转换服务'))
    request.send(data)
  })
}

async function poll() {
  clearTimeout(pollTimer)
  try {
    const response = await fetch(`/api/tasks/${task.value.taskId}`, { cache: 'no-store' })
    if (!response.ok) throw new Error('无法查询任务状态')
    task.value = await response.json()
    if (task.value.status === 'WAITING' || task.value.status === 'CONVERTING') pollTimer = setTimeout(poll, 800)
    else busy.value = false
  } catch (error) {
    message.value = error.message
    busy.value = false
  }
}

function download() { window.location.href = `/api/tasks/${task.value.taskId}/download` }

async function reset() {
  startNewBatch()
}

onBeforeUnmount(() => clearTimeout(pollTimer))
</script>

<template>
  <main class="shell">
    <header class="masthead">
      <div class="brand-mark">文</div>
      <div>
        <p class="eyebrow">内网文档转换工具</p>
        <h1>OFD 转可编辑 Word</h1>
        <p class="lead">保留真实文字与表格结构，文件只在本机服务器处理。</p>
      </div>
      <span class="privacy"><i></i> 本地处理</span>
    </header>

    <section class="workspace">
      <div
        class="drop-zone"
        :class="{ active: dragging, locked: busy }"
        @dragenter.prevent="dragging = true"
        @dragover.prevent
        @dragleave.prevent="dragging = false"
        @drop.prevent="drop"
      >
        <div class="file-symbol">OFD</div>
        <h2>拖放 OFD 文件到这里</h2>
        <p>支持单个或批量上传，单文件最大 50 MB</p>
        <label class="select-button">
          选择文件
          <input type="file" accept=".ofd,application/ofd" multiple :disabled="busy" @change="accept($event.target.files); $event.target.value = ''" />
        </label>
      </div>

      <div v-if="files.length" class="file-panel">
        <div class="panel-heading">
          <strong>待转换文件</strong>
          <span>{{ files.length }} 个</span>
        </div>
        <ul>
          <li v-for="(file, index) in files" :key="`${file.name}:${file.size}`">
            <span class="mini-icon">O</span>
            <span class="file-name">{{ file.name }}</span>
            <span class="file-size">{{ formatBytes(file.size) }}</span>
            <button class="remove" :disabled="busy" title="移除" @click="remove(index)">×</button>
          </li>
        </ul>
      </div>

      <div v-if="busy || task" class="task-panel" :class="task?.status?.toLowerCase()">
        <div class="status-row">
          <div>
            <span class="status-dot"></span>
            <strong>{{ statusLabel || '正在上传' }}</strong>
            <small v-if="task?.stage">{{ task.stage }}</small>
          </div>
          <b>{{ progress }}%</b>
        </div>
        <div class="progress-track"><span :style="{ width: `${progress}%` }"></span></div>

        <div v-if="task?.files?.length" class="result-list">
          <div v-for="file in task.files" :key="file.fileName" :class="file.success ? 'ok' : 'error'">
            <span>{{ file.success ? '✓' : '!' }}</span>
            <p><strong>{{ file.fileName }}</strong><small>{{ file.success ? `已完整转换 ${file.pageCount ?? 0} 页，生成可编辑 DOCX` : `${file.errorCode}：${file.errorMessage}` }}</small></p>
          </div>
        </div>

        <div v-if="task?.warnings?.length" class="warnings">
          <strong>转换提示</strong>
          <p v-for="(warning, index) in task.warnings" :key="index">{{ warning.message }}</p>
        </div>
      </div>

      <p v-if="message" class="message">{{ message }}</p>

      <div class="actions">
        <button v-if="!task?.downloadReady" class="primary" :disabled="!canSubmit" @click="submit">开始转换</button>
        <button v-else class="primary" @click="download">下载 {{ task.downloadName }}</button>
        <button v-if="task" class="secondary" @click="reset">转换其他文件</button>
      </div>
    </section>

    <footer>
      <span>文字可编辑</span><span>真实 Word 表格</span><span>不经过外部服务</span>
    </footer>
  </main>
</template>
