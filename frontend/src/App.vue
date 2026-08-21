<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const fallbackConversions = [{
  id: 'ofd-to-docx',
  sourceFormat: 'ofd',
  targetFormat: 'docx',
  sourceLabel: 'OFD',
  targetLabel: 'Word DOCX',
  inputExtension: '.ofd',
  outputExtension: '.docx',
  description: '将文字型 OFD 转换为可编辑 Word 文档',
  status: 'available',
  qualityLevel: 'beta',
  strategy: 'editable',
  requires: [],
  limitations: ['复杂签章、扫描页和厂商私有扩展需要更多样本验证']
}]

const files = ref([])
const conversions = ref(fallbackConversions)
const selectedRouteId = ref(fallbackConversions[0].id)
const routePickerRef = ref(null)
const routePickerOpen = ref(false)
const routeSearch = ref('')
const dragging = ref(false)
const uploadProgress = ref(0)
const task = ref(null)
const busy = ref(false)
const message = ref('')
const diagnosticMessage = ref('')
const limits = ref({ maxFileSize: 50 * 1024 * 1024, maxFilesPerTask: 100, maxTaskUploadBytes: 250 * 1024 * 1024 })
let pollTimer
let pollGeneration = 0
let pollFailures = 0

const selectedRoute = computed(() => conversions.value.find(route => route.id === selectedRouteId.value) || conversions.value[0])
const isPdfMergeRoute = computed(() => selectedRoute.value?.targetFormat === 'pdf-merge')
const isSinglePdfTool = computed(() => ['pdf-split', 'pdf-watermark', 'pdf-compress'].includes(selectedRoute.value?.targetFormat))
const routeFileLimit = computed(() => isSinglePdfTool.value ? 1 : limits.value.maxFilesPerTask)
const canSubmit = computed(() => files.value.length >= (isPdfMergeRoute.value ? 2 : 1)
  && !busy.value && selectedRoute.value?.status === 'available')
const availableRoutes = computed(() => conversions.value.filter(route => route.status === 'available'))
const acceptExtension = computed(() => selectedRoute.value?.inputExtension || '.ofd')
const acceptExtensions = computed(() => selectedRoute.value?.sourceFormat === 'jpg' ? ['.jpg', '.jpeg'] : [acceptExtension.value])
const acceptType = computed(() => `${acceptExtensions.value.join(',')},application/${selectedRoute.value?.sourceFormat || 'ofd'}`)
const statusLabel = computed(() => ({ WAITING: '等待转换', CONVERTING: '正在转换', SUCCESS: '转换完成', FAILED: '转换失败', CANCELLED: '转换已取消' })[task.value?.status] || '')
const progress = computed(() => task.value?.progress ?? uploadProgress.value)
const uploadHint = computed(() => {
  if (isPdfMergeRoute.value) return `至少 2 个 PDF，按上传顺序合并；单文件最大 ${formatBytes(limits.value.maxFileSize)}`
  if (isSinglePdfTool.value) return `一次处理 1 个 PDF，单文件最大 ${formatBytes(limits.value.maxFileSize)}`
  return `最多 ${limits.value.maxFilesPerTask} 个文件，单文件最大 ${formatBytes(limits.value.maxFileSize)}，总计最大 ${formatBytes(limits.value.maxTaskUploadBytes)}`
})
const routeGroups = computed(() => {
  const keyword = routeSearch.value.trim().toLowerCase()
  const groups = new Map()
  for (const route of conversions.value) {
    const text = `${route.id} ${route.sourceFormat} ${route.targetFormat} ${route.sourceLabel} ${route.targetLabel} ${route.description}`.toLowerCase()
    if (keyword && !text.includes(keyword)) continue
    const label = routeGroupLabel(route)
    if (!groups.has(label)) groups.set(label, [])
    groups.get(label).push(route)
  }
  return Array.from(groups, ([label, routes]) => ({ label, routes }))
})

function formatRouteLabel(route) {
  return `${route.sourceLabel} → ${route.targetLabel}`
}

function routeBadge(route) {
  if (route.status === 'unavailable') return '不可用'
  if (route.status !== 'available') return '规划中'
  return ({ stable: '稳定', beta: 'Beta', experimental: '实验' })[route.qualityLevel] || '可用'
}

function routeAvailability(route) {
  if (route.status === 'unavailable') return '（当前环境不可用）'
  if (route.status !== 'available') return '（暂未开放）'
  return ''
}

function responseError(response, fallback) {
  const error = new Error(response.status === 401 ? '任务 API 需要有效的访问令牌' : fallback)
  error.status = response.status
  return error
}

function strategyLabel(route) {
  return ({ editable: '可编辑优先', fidelity: '保真优先', data: '数据优先', extraction: '提取优先', content: '内容优先', compatibility: '兼容优先' })[route.strategy] || ''
}

function routeMeta(route) {
  const values = [routeBadge(route), strategyLabel(route)].filter(Boolean)
  if (Array.isArray(route.requires) && route.requires.length) values.push(`依赖 ${route.requires.join(' / ')}`)
  return values.join(' · ')
}

function routeGroupLabel(route) {
  if (route.status === 'unavailable') return '当前环境不可用'
  if (route.status !== 'available') return '规划路线'
  if (route.sourceFormat === 'ofd') return 'OFD'
  if (route.sourceFormat === 'pdf') return 'PDF'
  if (['csv', 'xlsx'].includes(route.sourceFormat)) return '表格'
  if (['txt', 'docx', 'pptx'].includes(route.sourceFormat)) return 'Office 文档'
  if (['wps', 'et', 'dps', 'uof'].includes(route.sourceFormat)) return '国产格式'
  if (['png', 'jpg', 'jpeg'].includes(route.sourceFormat)) return '图片'
  return '其他'
}

function toggleRoutePicker() {
  if (busy.value) return
  routePickerOpen.value = !routePickerOpen.value
}

function closeRoutePicker() {
  routePickerOpen.value = false
}

function selectRoute(route) {
  if (busy.value || route.status !== 'available') return
  if (route.id !== selectedRouteId.value && (files.value.length || task.value)) startNewBatch()
  selectedRouteId.value = route.id
  routeSearch.value = ''
  closeRoutePicker()
}

function onDocumentClick(event) {
  if (!routePickerRef.value?.contains(event.target)) closeRoutePicker()
}

function onRoutePickerKeydown(event) {
  if (event.key === 'Escape') closeRoutePicker()
}

async function loadCapabilities() {
  try {
    const response = await fetch('/api/tasks/capabilities', { cache: 'no-store' })
    if (!response.ok) {
      if (response.status === 401) message.value = '当前部署启用了访问令牌，内置页面不可用'
      return
    }
    const routes = await response.json()
    if (Array.isArray(routes) && routes.length) {
      conversions.value = routes
      if (!routes.some(route => route.id === selectedRouteId.value)) selectedRouteId.value = routes[0].id
      if (selectedRoute.value?.status !== 'available' && availableRoutes.value.length) {
        selectedRouteId.value = availableRoutes.value[0].id
      }
    }
  } catch (_) {
    conversions.value = fallbackConversions
  }
}

async function loadLimits() {
  try {
    const response = await fetch('/api/diagnostics', { cache: 'no-store' })
    if (!response.ok) return
    const diagnostics = await response.json()
    const configured = diagnostics?.limits || {}
    if (Number.isFinite(configured.maxFileSize) && configured.maxFileSize > 0) limits.value.maxFileSize = configured.maxFileSize
    if (Number.isInteger(configured.maxFilesPerTask) && configured.maxFilesPerTask > 0) limits.value.maxFilesPerTask = configured.maxFilesPerTask
    if (Number.isFinite(configured.maxTaskUploadBytes) && configured.maxTaskUploadBytes > 0) limits.value.maxTaskUploadBytes = configured.maxTaskUploadBytes
  } catch (_) { /* keep safe UI defaults */ }
}

function startNewBatch() {
  pollGeneration++
  pollFailures = 0
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
  const extensions = acceptExtensions.value.map(extension => extension.toLowerCase())
  const selectedFiles = Array.from(selected || [])
  const matching = selectedFiles.filter(file => extensions.some(extension => file.name.toLowerCase().endsWith(extension)))
  const incoming = matching.filter(file => file.size <= limits.value.maxFileSize)
  if (incoming.length && task.value && !busy.value) startNewBatch()
  const known = new Set(files.value.map(file => `${file.name}:${file.size}`))
  let capacityRejected = 0
  let quotaRejected = 0
  let selectedBytes = files.value.reduce((total, file) => total + file.size, 0)
  for (const file of incoming) {
    const key = `${file.name}:${file.size}`
    if (known.has(key)) continue
    if (files.value.length >= routeFileLimit.value) {
      capacityRejected++
      continue
    }
    if (selectedBytes + file.size > limits.value.maxTaskUploadBytes) {
      quotaRejected++
      continue
    }
    files.value.push(file)
    selectedBytes += file.size
    known.add(key)
  }
  const notices = []
  if (matching.length !== selectedFiles.length) notices.push(`非 ${acceptExtensions.value.join(' / ').toUpperCase()} 文件`)
  if (incoming.length !== matching.length) notices.push(`超过 ${formatBytes(limits.value.maxFileSize)} 的文件`)
  if (capacityRejected) notices.push(`超出 ${routeFileLimit.value} 个文件上限的部分`)
  if (quotaRejected) notices.push(`超出 ${formatBytes(limits.value.maxTaskUploadBytes)} 总量上限的部分`)
  message.value = notices.length ? `已忽略${notices.join('、')}` : ''
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
  if (selectedRoute.value?.status !== 'available') {
    message.value = '这条转换路线还在规划中，暂未开放执行'
    return
  }
  busy.value = true
  message.value = ''
  task.value = null
  uploadProgress.value = 0
  pollFailures = 0
  const data = new FormData()
  files.value.forEach(file => data.append('files', file))
  data.append('targetFormat', selectedRoute.value.targetFormat)
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
      else if (request.status === 401) reject(new Error('任务 API 需要有效的访问令牌'))
      else reject(new Error(body.message || `上传失败（${request.status}）`))
    }
    request.onerror = () => reject(new Error('无法连接转换服务'))
    request.onabort = () => reject(new Error('上传已取消'))
    request.send(data)
  })
}

async function poll() {
  clearTimeout(pollTimer)
  const generation = pollGeneration
  try {
    const response = await fetch(`/api/tasks/${task.value.taskId}`, { cache: 'no-store' })
    if (!response.ok) throw responseError(response, '无法查询任务状态')
    const snapshot = await response.json()
    if (generation !== pollGeneration) return
    pollFailures = 0
    message.value = ''
    task.value = snapshot
    if (task.value.status === 'WAITING' || task.value.status === 'CONVERTING') pollTimer = setTimeout(poll, 800)
    else busy.value = false
  } catch (error) {
    if (generation !== pollGeneration) return
    if (error.status === 401) {
      message.value = error.message
      busy.value = false
      return
    }
    pollFailures++
    message.value = error.message
    const delay = Math.min(10000, 800 * (2 ** Math.min(pollFailures, 4)))
    pollTimer = setTimeout(poll, delay)
  }
}

async function download() {
  try {
    const response = await fetch(`/api/tasks/${task.value.taskId}/download`)
    if (!response.ok) throw responseError(response, `下载失败（${response.status}）`)
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = task.value.downloadName || 'converted-file'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  } catch (error) {
    message.value = error.message || '下载失败'
  }
}

async function taskAction(action) {
  const response = await fetch(`/api/tasks/${task.value.taskId}/${action}`, { method: 'POST' })
  let body = {}
  try { body = await response.json() } catch (_) { /* ignore */ }
  if (!response.ok) {
    const error = responseError(response, body.message || `任务操作失败（${response.status}）`)
    if (body.message && response.status !== 401) error.message = body.message
    throw error
  }
  return body
}

async function cancelTask() {
  pollGeneration++
  clearTimeout(pollTimer)
  try {
    task.value = await taskAction('cancel')
    busy.value = false
  } catch (error) {
    message.value = error.message
    if (task.value && ['WAITING', 'CONVERTING'].includes(task.value.status)) poll()
  }
}

async function retryTask() {
  pollGeneration++
  message.value = ''
  busy.value = true
  try {
    task.value = await taskAction('retry')
    poll()
  } catch (error) {
    message.value = error.message
    busy.value = false
  }
}

async function reset() {
  startNewBatch()
}

async function copyDiagnostics() {
  diagnosticMessage.value = ''
  try {
    const response = await fetch('/api/diagnostics', { cache: 'no-store' })
    if (!response.ok) throw new Error(`诊断信息获取失败（${response.status}）`)
    const diagnostics = await response.json()
    const text = JSON.stringify(diagnostics, null, 2)
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const area = document.createElement('textarea')
      area.value = text
      area.setAttribute('readonly', '')
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      document.execCommand('copy')
      document.body.removeChild(area)
    }
    diagnosticMessage.value = '诊断信息已复制'
  } catch (error) {
    diagnosticMessage.value = error.message || '无法复制诊断信息'
  }
}

function successDescription(file) {
  const pages = Number.isInteger(file.pageCount) ? `，共 ${file.pageCount} 页` : ''
  return `已转换为 ${selectedRoute.value.targetLabel}${pages}`
}

onMounted(() => {
  loadCapabilities()
  loadLimits()
  document.addEventListener('click', onDocumentClick)
})
onBeforeUnmount(() => {
  clearTimeout(pollTimer)
  document.removeEventListener('click', onDocumentClick)
})
</script>

<template>
  <main class="shell">
    <header class="masthead">
      <div class="brand-mark">文</div>
      <div>
        <p class="eyebrow">开源文档转换平台</p>
        <h1>FormatConverter</h1>
        <p class="lead">用可验证的转换路线处理办公文档、PDF、OFD 和国产格式。</p>
      </div>
      <span class="privacy"><i></i> 本地处理</span>
    </header>

    <section class="workspace">
      <div class="format-panel">
        <label id="route-label">转换类型</label>
        <div ref="routePickerRef" class="route-picker" @keydown="onRoutePickerKeydown">
          <button
            id="route"
            type="button"
            class="route-trigger"
            :disabled="busy"
            aria-haspopup="listbox"
            :aria-expanded="routePickerOpen"
            aria-labelledby="route-label route"
            @click.stop="toggleRoutePicker"
          >
            <span>
              <strong>{{ formatRouteLabel(selectedRoute) }}</strong>
              <small>{{ selectedRoute.inputExtension }} 转 {{ selectedRoute.outputExtension }}</small>
            </span>
            <i aria-hidden="true"></i>
          </button>

          <div v-if="routePickerOpen" class="route-menu" @click.stop>
            <input
              v-model="routeSearch"
              class="route-search"
              type="search"
              placeholder="搜索格式，如 PDF、Word、WPS"
              aria-label="搜索转换类型"
            />
            <div class="route-list" role="listbox" aria-labelledby="route-label">
              <p v-if="!routeGroups.length" class="route-empty">没有匹配的转换类型</p>
              <section v-for="group in routeGroups" :key="group.label" class="route-group">
                <p class="route-group-title">{{ group.label }}</p>
                <button
                  v-for="route in group.routes"
                  :key="route.id"
                  type="button"
                  class="route-option"
                  :class="{ selected: route.id === selectedRouteId, planned: route.status === 'planned', unavailable: route.status === 'unavailable' }"
                  :disabled="route.status !== 'available'"
                  role="option"
                  :aria-selected="route.id === selectedRouteId"
                  @click="selectRoute(route)"
                >
                  <span class="route-main">
                    <strong>{{ formatRouteLabel(route) }}</strong>
                    <small>{{ route.description }}</small>
                    <em>{{ routeMeta(route) }}</em>
                  </span>
                  <span class="route-badge" :class="route.qualityLevel || route.status">{{ routeBadge(route) }}</span>
                </button>
              </section>
            </div>
          </div>
        </div>
        <span class="route-description">{{ selectedRoute.description }}{{ routeAvailability(selectedRoute) }}</span>
        <span v-if="routeMeta(selectedRoute)" class="route-meta">{{ routeMeta(selectedRoute) }}</span>
      </div>

      <div
        class="drop-zone"
        :class="{ active: dragging, locked: busy }"
        @dragenter.prevent="dragging = true"
        @dragover.prevent
        @dragleave.prevent="dragging = false"
        @drop.prevent="drop"
      >
        <div class="file-symbol">{{ selectedRoute.sourceLabel }}</div>
        <h2>拖放 {{ selectedRoute.sourceLabel }} 文件到这里</h2>
        <p>支持单个或批量上传，{{ uploadHint }}</p>
        <label class="select-button">
          选择文件
          <input type="file" :accept="acceptType" multiple :disabled="busy || selectedRoute.status !== 'available'" @change="accept($event.target.files); $event.target.value = ''" />
        </label>
      </div>

      <div v-if="files.length" class="file-panel">
        <div class="panel-heading">
          <strong>待转换文件</strong>
          <span>{{ files.length }} 个</span>
        </div>
        <ul>
          <li v-for="(file, index) in files" :key="`${file.name}:${file.size}`">
            <span class="mini-icon">{{ selectedRoute.sourceFormat.slice(0, 1).toUpperCase() }}</span>
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
            <p><strong>{{ file.fileName }}</strong><small>{{ file.success ? successDescription(file) : `${file.errorCode}：${file.errorMessage}` }}</small></p>
          </div>
        </div>

        <div v-if="task?.warnings?.length" class="warnings">
          <strong>转换提示</strong>
          <p v-for="(warning, index) in task.warnings" :key="index">{{ warning.message }}</p>
        </div>
      </div>

      <p v-if="message" class="message">{{ message }}</p>

      <div class="actions">
        <button v-if="!task" class="primary" :disabled="!canSubmit" @click="submit">开始转换</button>
        <button v-if="task?.downloadReady" class="primary" @click="download">下载 {{ task.downloadName }}</button>
        <button v-if="task && ['WAITING', 'CONVERTING'].includes(task.status)" class="secondary" @click="cancelTask">取消任务</button>
        <button v-if="task && ['FAILED', 'CANCELLED'].includes(task.status)" class="secondary" @click="retryTask">重试</button>
        <button v-if="task" class="secondary" @click="reset">转换其他文件</button>
      </div>
    </section>

    <footer>
      <span>当前开放文档 / 表格 / PDF / 图片</span><span>国产格式已入矩阵</span><span>不经过外部服务</span>
      <button type="button" class="diagnostic-button" @click="copyDiagnostics">复制诊断信息</button>
    </footer>
    <p v-if="diagnosticMessage" class="diagnostic-message">{{ diagnosticMessage }}</p>
  </main>
</template>
