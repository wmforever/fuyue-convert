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
const pickerSource = ref('popular')
const dragging = ref(false)
const uploadProgress = ref(0)
const task = ref(null)
const busy = ref(false)
const message = ref('')
const diagnosticMessage = ref('')
const compressionMode = ref('balanced')
const watermarkText = ref('机密资料')
const watermarkOpacity = ref(0.18)
const watermarkAngle = ref(35)
const watermarkPosition = ref('center')
const watermarkTiled = ref(false)
const watermarkPages = ref('all')
const watermarkColor = ref('#969696')
const limits = ref({ maxFileSize: 50 * 1024 * 1024, maxFilesPerTask: 100, maxTaskUploadBytes: 250 * 1024 * 1024 })
let pollTimer
let pollGeneration = 0
let pollFailures = 0

const selectedRoute = computed(() => conversions.value.find(route => route.id === selectedRouteId.value) || conversions.value[0])
const isPdfMergeRoute = computed(() => selectedRoute.value?.targetFormat === 'pdf-merge')
const isSinglePdfTool = computed(() => ['pdf-split', 'pdf-watermark', 'pdf-compress'].includes(selectedRoute.value?.targetFormat))
const isPdfCompressRoute = computed(() => selectedRoute.value?.targetFormat === 'pdf-compress')
const isPdfWatermarkRoute = computed(() => selectedRoute.value?.targetFormat === 'pdf-watermark')
const hasToolOptions = computed(() => isPdfCompressRoute.value || isPdfWatermarkRoute.value)
const watermarkPagesValid = computed(() => validWatermarkPages(watermarkPages.value))
const toolOptionsValid = computed(() => !isPdfWatermarkRoute.value
  || (watermarkText.value.trim().length > 0 && watermarkPagesValid.value))
const routeFileLimit = computed(() => isSinglePdfTool.value ? 1 : limits.value.maxFilesPerTask)
const canSubmit = computed(() => files.value.length >= (isPdfMergeRoute.value ? 2 : 1)
  && !busy.value && toolOptionsValid.value && selectedRoute.value?.status === 'available')
const availableRoutes = computed(() => conversions.value.filter(route => route.status === 'available'))
const selectedBytes = computed(() => files.value.reduce((total, file) => total + file.size, 0))
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
const popularRouteIds = ['pdf-to-docx', 'docx-to-pdf', 'pdf-to-pdf-merge', 'ofd-to-docx', 'pdf-to-pdf-compress', 'png-to-pdf']
const pdfToolRouteIds = ['pdf-to-pdf-compress', 'pdf-to-pdf-merge', 'pdf-to-pdf-split', 'pdf-to-pdf-watermark']
const sourceOrder = ['pdf', 'ofd', 'docx', 'txt', 'xlsx', 'csv', 'png', 'jpg', 'pptx', 'wps', 'et', 'dps', 'uof']
const sourceOptions = computed(() => {
  const sources = new Map()
  for (const route of conversions.value) {
    if (!sources.has(route.sourceFormat)) {
      sources.set(route.sourceFormat, { id: route.sourceFormat, label: route.sourceLabel, count: 0 })
    }
    sources.get(route.sourceFormat).count++
  }
  const options = Array.from(sources.values()).sort((a, b) => {
    const left = sourceOrder.indexOf(a.id)
    const right = sourceOrder.indexOf(b.id)
    return (left < 0 ? 999 : left) - (right < 0 ? 999 : right)
  })
  return [
    { id: 'popular', label: '常用转换', count: popularRouteIds.filter(id => conversions.value.some(route => route.id === id)).length },
    { id: 'pdf-tools', label: 'PDF 工具', count: pdfToolRouteIds.filter(id => conversions.value.some(route => route.id === id)).length },
    ...options
  ]
})
const pickerRoutes = computed(() => {
  const keyword = routeSearch.value.trim().toLowerCase()
  if (keyword) return conversions.value.filter(route => {
    const text = `${route.id} ${route.sourceFormat} ${route.targetFormat} ${route.sourceLabel} ${route.targetLabel} ${route.description}`.toLowerCase()
    return text.includes(keyword)
  })
  if (pickerSource.value === 'popular') {
    return popularRouteIds.map(id => conversions.value.find(route => route.id === id)).filter(Boolean)
  }
  if (pickerSource.value === 'pdf-tools') {
    return pdfToolRouteIds.map(id => conversions.value.find(route => route.id === id)).filter(Boolean)
  }
  return conversions.value.filter(route => route.sourceFormat === pickerSource.value)
})
const pickerTitle = computed(() => {
  if (routeSearch.value.trim()) return `搜索结果 · ${pickerRoutes.value.length}`
  if (pickerSource.value === 'popular') return '常用转换'
  if (pickerSource.value === 'pdf-tools') return 'PDF 实用工具'
  const source = sourceOptions.value.find(option => option.id === pickerSource.value)
  return `${source?.label || '当前格式'} 可以转换为`
})

function formatRouteLabel(route) {
  return `${route.sourceLabel} → ${route.targetLabel}`
}

function validWatermarkPages(value) {
  const normalized = String(value || '').replace(/\s+/g, '').toLowerCase()
  if (normalized === 'all') return true
  if (!/^[1-9]\d*(?:-[1-9]\d*)?(?:,[1-9]\d*(?:-[1-9]\d*)?)*$/.test(normalized)) return false
  return normalized.split(',').every(part => {
    const [start, end = start] = part.split('-').map(Number)
    return Number.isSafeInteger(start) && Number.isSafeInteger(end) && start <= end && end <= 1000000
  })
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

function toggleRoutePicker() {
  if (busy.value) return
  routePickerOpen.value = !routePickerOpen.value
  if (routePickerOpen.value) pickerSource.value = selectedRoute.value?.sourceFormat || 'popular'
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

function selectPickerSource(source) {
  pickerSource.value = source
  routeSearch.value = ''
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
  if (isPdfCompressRoute.value) data.append('compressionMode', compressionMode.value)
  if (isPdfWatermarkRoute.value) {
    data.append('watermarkText', watermarkText.value)
    data.append('watermarkOpacity', watermarkOpacity.value)
    data.append('watermarkAngle', watermarkAngle.value)
    data.append('watermarkPosition', watermarkPosition.value)
    data.append('watermarkTiled', watermarkTiled.value)
    data.append('watermarkPages', watermarkPages.value)
    data.append('watermarkColor', watermarkColor.value)
  }
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
    <nav class="topbar" aria-label="品牌导航">
      <a class="brand" href="/" aria-label="FormatConverter 首页">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 32 32" fill="none">
            <path d="M9 5.5h10l5 5v16H9z" />
            <path d="M19 5.5v5h5M13 16h7M13 20h7" />
          </svg>
        </span>
        <span><strong>FormatConverter</strong><small>开源文档转换平台</small></span>
      </a>
      <div class="topbar-meta">
        <span class="privacy"><i></i> 数据留在本机</span>
        <a class="github-link" href="https://github.com/wmforever/fuyue-convert" target="_blank" rel="noreferrer">开源项目 <span aria-hidden="true">↗</span></a>
      </div>
    </nav>

    <header class="hero">
      <div class="hero-copy">
        <p class="eyebrow"><span></span> LOCAL-FIRST DOCUMENT TOOLS</p>
        <h1>文件格式转换，<br /><em>简单、可靠、全程本地。</em></h1>
        <p class="lead">覆盖办公文档、PDF、OFD 与国产格式。每条转换路线都有明确的质量等级和适用边界。</p>
        <div class="hero-points" aria-label="产品特点">
          <span><b>✓</b> 无需上传云端</span>
          <span><b>✓</b> 批量任务处理</span>
          <span><b>✓</b> {{ availableRoutes.length }} 条可用路线</span>
        </div>
      </div>
      <aside class="trust-card" aria-label="本地处理说明">
        <span class="trust-icon">
          <svg viewBox="0 0 24 24" fill="none"><path d="M12 3 5.5 5.8v5.4c0 4.3 2.7 8.2 6.5 9.8 3.8-1.6 6.5-5.5 6.5-9.8V5.8z"/><path d="m9.2 12 1.8 1.8 4-4"/></svg>
        </span>
        <div><small>PRIVACY BY DESIGN</small><strong>本地处理模式</strong><p>文件仅由当前服务处理，不经过第三方云端。</p></div>
      </aside>
    </header>

    <section class="workspace">
      <div class="workspace-head">
        <div>
          <p class="section-kicker">CONVERT A FILE</p>
          <h2>开始转换</h2>
        </div>
        <ol class="steps" aria-label="转换步骤">
          <li class="current"><span>1</span>选择格式</li>
          <li :class="{ current: files.length }"><span>2</span>添加文件</li>
          <li :class="{ current: task?.downloadReady }"><span>3</span>完成下载</li>
        </ol>
      </div>

      <div class="format-panel">
        <div class="field-heading">
          <span class="field-number">01</span>
          <div><label id="route-label">选择转换类型</label><small>告诉我们文件要变成什么格式</small></div>
        </div>
        <div class="route-field">
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
              <span class="route-formats">
                <b>{{ selectedRoute.sourceLabel }}</b><i aria-hidden="true">→</i><b>{{ selectedRoute.targetLabel }}</b>
              </span>
              <span class="chevron" aria-hidden="true"></span>
            </button>

            <div v-if="routePickerOpen" class="route-menu" @click.stop>
              <div class="route-search-wrap">
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="11" cy="11" r="6"/><path d="m16 16 4 4"/></svg>
                <input
                  v-model="routeSearch"
                  class="route-search"
                  type="search"
                  placeholder="搜索 PDF、Word、WPS..."
                  aria-label="搜索转换类型"
                />
                <kbd>搜索</kbd>
              </div>
              <div class="route-explorer">
                <aside class="source-list" aria-label="按来源格式筛选">
                  <button
                    v-for="source in sourceOptions"
                    :key="source.id"
                    type="button"
                    :class="{ active: pickerSource === source.id && !routeSearch.trim() }"
                    @click="selectPickerSource(source.id)"
                  >
                    <span>{{ source.id === 'popular' ? '★' : source.id === 'pdf-tools' ? '◆' : source.id.slice(0, 3).toUpperCase() }}</span>
                    <strong>{{ source.label }}</strong>
                    <small>{{ source.count }}</small>
                  </button>
                </aside>
                <section class="target-panel">
                  <div class="target-heading">
                    <div><strong>{{ pickerTitle }}</strong><small>选择一个目标格式即可</small></div>
                    <button v-if="routeSearch" type="button" @click="routeSearch = ''">清除搜索</button>
                  </div>
                  <div class="route-list" role="listbox" aria-labelledby="route-label">
                    <p v-if="!pickerRoutes.length" class="route-empty"><b>没有找到匹配路线</b><span>试试搜索格式名或“合并”“压缩”等功能</span></p>
                    <button
                      v-for="route in pickerRoutes"
                      :key="route.id"
                      type="button"
                      class="route-option"
                      :class="{ selected: route.id === selectedRouteId, planned: route.status === 'planned', unavailable: route.status === 'unavailable' }"
                      :disabled="route.status !== 'available'"
                      role="option"
                      :aria-selected="route.id === selectedRouteId"
                      @click="selectRoute(route)"
                    >
                      <span class="route-format-icon">{{ route.targetFormat.split('-')[0].slice(0, 4).toUpperCase() }}</span>
                      <span class="route-main">
                        <strong>{{ route.sourceLabel }} <i>→</i> {{ route.targetLabel }}</strong>
                        <small>{{ route.description }}</small>
                        <em>{{ routeMeta(route) }}</em>
                      </span>
                      <span class="route-badge" :class="route.qualityLevel || route.status">{{ routeBadge(route) }}</span>
                    </button>
                  </div>
                </section>
              </div>
            </div>
          </div>
          <div class="route-detail">
            <span class="route-description">{{ selectedRoute.description }}{{ routeAvailability(selectedRoute) }}</span>
            <span v-if="routeMeta(selectedRoute)" class="route-meta">{{ routeMeta(selectedRoute) }}</span>
          </div>
        </div>
      </div>

      <div v-if="hasToolOptions" class="tool-options-section">
        <div class="field-heading">
          <span class="field-number">02</span>
          <div><label>{{ isPdfCompressRoute ? '压缩设置' : '水印设置' }}</label><small>根据使用场景调整处理参数</small></div>
        </div>

        <div v-if="isPdfCompressRoute" class="compression-options" role="radiogroup" aria-label="PDF 压缩等级">
          <label :class="{ selected: compressionMode === 'lossless' }">
            <input v-model="compressionMode" type="radio" value="lossless" />
            <span class="option-check"></span>
            <strong>无损优化</strong>
            <small>不改变图片质量，清理并压缩 PDF 结构</small>
            <em>画质优先</em>
          </label>
          <label :class="{ selected: compressionMode === 'balanced' }">
            <input v-model="compressionMode" type="radio" value="balanced" />
            <span class="option-check"></span>
            <strong>均衡压缩</strong>
            <small>适度优化图片，兼顾清晰度和文件体积</small>
            <em>推荐</em>
          </label>
          <label :class="{ selected: compressionMode === 'strong' }">
            <input v-model="compressionMode" type="radio" value="strong" />
            <span class="option-check"></span>
            <strong>强力压缩</strong>
            <small>显著降低图片分辨率，适合在线传输</small>
            <em>体积优先</em>
          </label>
          <p class="option-notice"><span>i</span> 若处理后的文件没有变小，系统会自动保留原文件。</p>
        </div>

        <div v-else class="watermark-options">
          <label class="wide-field">
            <span>水印文字</span>
            <input v-model="watermarkText" type="text" maxlength="80" placeholder="例如：机密资料" />
          </label>
          <label>
            <span>应用页面</span>
            <input v-model="watermarkPages" type="text" placeholder="all 或 1,3-5" :class="{ invalid: !watermarkPagesValid }" />
            <small v-if="!watermarkPagesValid" class="field-error">请输入 all、1 或 1,3-5</small>
          </label>
          <label>
            <span>位置</span>
            <select v-model="watermarkPosition" :disabled="watermarkTiled">
              <option value="center">页面居中</option>
              <option value="top-left">左上角</option>
              <option value="top-right">右上角</option>
              <option value="bottom-left">左下角</option>
              <option value="bottom-right">右下角</option>
            </select>
          </label>
          <label>
            <span>颜色</span>
            <span class="color-field"><input v-model="watermarkColor" type="color" /><b>{{ watermarkColor.toUpperCase() }}</b></span>
          </label>
          <label class="range-field">
            <span>透明度 <b>{{ Math.round(watermarkOpacity * 100) }}%</b></span>
            <input v-model.number="watermarkOpacity" type="range" min="0.05" max="0.85" step="0.01" />
          </label>
          <label class="range-field">
            <span>旋转角度 <b>{{ watermarkAngle }}°</b></span>
            <input v-model.number="watermarkAngle" type="range" min="-180" max="180" step="1" />
          </label>
          <label class="toggle-field">
            <input v-model="watermarkTiled" type="checkbox" />
            <span class="toggle"></span>
            <span><strong>平铺水印</strong><small>在整页重复显示水印</small></span>
          </label>
        </div>
      </div>

      <div class="upload-section">
        <div class="field-heading">
          <span class="field-number">{{ hasToolOptions ? '03' : '02' }}</span>
          <div><label>添加文件</label><small>拖放文件，或从设备中选择</small></div>
        </div>
        <div
          class="drop-zone"
          :class="{ active: dragging, locked: busy, filled: files.length }"
          @dragenter.prevent="dragging = true"
          @dragover.prevent
          @dragleave.prevent="dragging = false"
          @drop.prevent="drop"
        >
          <div class="file-symbol" aria-hidden="true">
            <svg viewBox="0 0 42 48" fill="none"><path d="M7 2h19l9 9v35H7z"/><path d="M26 2v10h9"/><path d="M15 25h12M15 31h12"/></svg>
            <span>{{ selectedRoute.sourceLabel }}</span>
          </div>
          <div class="drop-content">
            <h3>{{ files.length ? `已添加 ${files.length} 个文件` : `拖放 ${selectedRoute.sourceLabel} 文件到这里` }}</h3>
            <p>{{ files.length ? `总计 ${formatBytes(selectedBytes)}，可以继续添加或开始转换` : uploadHint }}</p>
          </div>
          <label class="select-button">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 16V4M7.5 8.5 12 4l4.5 4.5M5 14v5h14v-5"/></svg>
            {{ files.length ? '继续添加' : '选择文件' }}
            <input type="file" :accept="acceptType" multiple :disabled="busy || selectedRoute.status !== 'available'" @change="accept($event.target.files); $event.target.value = ''" />
          </label>
        </div>
      </div>

      <div v-if="files.length" class="file-panel">
        <div class="panel-heading">
          <div><strong>待转换文件</strong><small>将按下方顺序处理</small></div>
          <span>{{ files.length }} 个 · {{ formatBytes(selectedBytes) }}</span>
        </div>
        <ul>
          <li v-for="(file, index) in files" :key="`${file.name}:${file.size}`">
            <span class="mini-icon">{{ selectedRoute.sourceFormat.slice(0, 3).toUpperCase() }}</span>
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
        <button v-if="!task" class="primary" :disabled="!canSubmit" @click="submit">开始转换 <span aria-hidden="true">→</span></button>
        <button v-if="task?.downloadReady" class="primary" @click="download">下载 {{ task.downloadName }} <span aria-hidden="true">↓</span></button>
        <button v-if="task && ['WAITING', 'CONVERTING'].includes(task.status)" class="secondary" @click="cancelTask">取消任务</button>
        <button v-if="task && ['FAILED', 'CANCELLED'].includes(task.status)" class="secondary" @click="retryTask">重试</button>
        <button v-if="task" class="secondary" @click="reset">转换其他文件</button>
      </div>
    </section>

    <footer>
      <div class="footer-brand"><span class="footer-dot"></span><strong>FormatConverter</strong><small>让文档转换更透明、更可控。</small></div>
      <div class="footer-actions"><span>文档 · 表格 · PDF · 图片</span><button type="button" class="diagnostic-button" @click="copyDiagnostics">复制诊断信息</button></div>
    </footer>
    <p v-if="diagnosticMessage" class="diagnostic-message">{{ diagnosticMessage }}</p>
  </main>
</template>
