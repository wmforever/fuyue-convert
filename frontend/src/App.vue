<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

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
const activeView = ref('overview')
const appScrollRef = ref(null)
const routePickerRef = ref(null)
const routeTriggerRef = ref(null)
const routeMenuRef = ref(null)
const routePickerOpen = ref(false)
const routeMenuStyle = ref({})
const routeSearch = ref('')
const pickerSource = ref('popular')
const dragging = ref(false)
const uploadProgress = ref(0)
const task = ref(null)
const busy = ref(false)
const message = ref('')
const diagnosticMessage = ref('')
const diagnostics = ref(null)
const diagnosticsFailed = ref(false)
const capabilityMessage = ref('')
const recentTasks = ref([])
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
const availableSourceCount = computed(() => new Set(availableRoutes.value.map(route => route.sourceFormat)).size)
const stableRoutes = computed(() => conversions.value.filter(route => route.status === 'available' && route.qualityLevel === 'stable'))
const betaRoutes = computed(() => conversions.value.filter(route => route.status === 'available' && route.qualityLevel === 'beta'))
const pdfToolRoutes = computed(() => pdfToolRouteIds.map(id => conversions.value.find(route => route.id === id)).filter(Boolean))
const quickRoutes = computed(() => popularRouteIds.map(id => conversions.value.find(route => route.id === id)).filter(route => route?.status === 'available').slice(0, 6))
const successfulTasks = computed(() => recentTasks.value.filter(item => item.status === 'SUCCESS').length)
const serviceHealthy = computed(() => Boolean(diagnostics.value))
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
const routeSearchAliases = {
  'pdf-compress': '压缩 瘦身 缩小 减小体积 文件变小 optimize optimization',
  'pdf-merge': '合并 拼接 组合 merge',
  'pdf-split': '拆分 分割 按页 split',
  'pdf-watermark': '水印 标记 盖章 watermark'
}
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
const quickSourceOptions = computed(() => sourceOptions.value.filter(source => ['popular', 'pdf-tools'].includes(source.id)))
const formatSourceOptions = computed(() => sourceOptions.value.filter(source => !['popular', 'pdf-tools'].includes(source.id)))
const pickerRoutes = computed(() => {
  const keyword = routeSearch.value.trim().toLowerCase()
  if (keyword) return conversions.value.filter(route => {
    const aliases = routeSearchAliases[route.targetFormat] || ''
    const text = `${route.id} ${route.sourceFormat} ${route.targetFormat} ${route.sourceLabel} ${route.targetLabel} ${route.description} ${aliases}`.toLowerCase()
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

const viewTitle = computed(() => ({
  overview: ['概览', '掌握本地转换服务和最近任务'],
  convert: ['转换工作台', '选择路线并处理你的文件'],
  pdf: ['PDF 工具', '合并、拆分、压缩与文字水印'],
  history: ['任务记录', '仅保存在当前设备上的最近转换'],
  settings: ['运行设置', '查看本地引擎、资源限制和诊断状态']
})[activeView.value] || ['概览', '本地文档转换工作台'])

const navItems = [
  { id: 'overview', label: '概览' },
  { id: 'convert', label: '开始转换' },
  { id: 'pdf', label: 'PDF 工具' },
  { id: 'history', label: '任务记录' },
  { id: 'settings', label: '设置' }
]

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

async function toggleRoutePicker() {
  if (busy.value) return
  routePickerOpen.value = !routePickerOpen.value
  if (routePickerOpen.value) {
    pickerSource.value = selectedRoute.value?.sourceFormat || 'popular'
    await nextTick()
    positionRouteMenu()
    routeMenuRef.value?.querySelector('.route-search')?.focus()
  }
}

function closeRoutePicker(restoreFocus = false) {
  routePickerOpen.value = false
  if (restoreFocus) nextTick(() => routeTriggerRef.value?.focus())
}

function positionRouteMenu() {
  if (!routePickerOpen.value || !routeTriggerRef.value || window.innerWidth <= 720) return
  const trigger = routeTriggerRef.value.getBoundingClientRect()
  const gutter = 14
  const menuWidth = Math.min(760, window.innerWidth - gutter * 2)
  const left = Math.min(Math.max(gutter, trigger.right - menuWidth), window.innerWidth - menuWidth - gutter)
  const availableBelow = window.innerHeight - trigger.bottom - gutter
  const availableAbove = trigger.top - gutter
  const openUpward = availableBelow < 360 && availableAbove > availableBelow
  const available = openUpward ? availableAbove : availableBelow
  routeMenuStyle.value = {
    left: `${left}px`,
    width: `${menuWidth}px`,
    top: openUpward ? 'auto' : `${trigger.bottom + 8}px`,
    bottom: openUpward ? `${window.innerHeight - trigger.top + 8}px` : 'auto',
    '--route-menu-height': `${Math.max(270, Math.min(500, available - 8))}px`
  }
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

async function navigate(view) {
  activeView.value = view
  routePickerOpen.value = false
  await nextTick()
  if (appScrollRef.value) appScrollRef.value.scrollTop = 0
}

function openRoute(route) {
  if (!route || route.status !== 'available') return
  selectRoute(route)
  activeView.value = 'convert'
  requestAnimationFrame(() => document.querySelector('.workspace')?.scrollIntoView({ block: 'start' }))
}

function openRouteById(routeId) {
  openRoute(conversions.value.find(route => route.id === routeId))
}

function openPdfWorkspace() {
  const firstAvailable = pdfToolRoutes.value.find(route => route.status === 'available')
  if (firstAvailable) openRoute(firstAvailable)
}

function loadRecentTasks() {
  try {
    const stored = JSON.parse(localStorage.getItem('format-converter-recent-tasks') || '[]')
    if (Array.isArray(stored)) {
      recentTasks.value = stored.filter(item => item
        && typeof item.taskId === 'string' && item.taskId.length > 0
        && ['SUCCESS', 'FAILED', 'CANCELLED'].includes(item.status))
        .map(item => ({
          taskId: item.taskId,
          status: item.status,
          sourceLabel: typeof item.sourceLabel === 'string' ? item.sourceLabel : '文件',
          targetLabel: typeof item.targetLabel === 'string' ? item.targetLabel : '输出文件',
          fileCount: Number.isInteger(item.fileCount) && item.fileCount >= 0 ? item.fileCount : 0,
          updatedAt: typeof item.updatedAt === 'string' ? item.updatedAt : null
        })).slice(0, 12)
    }
  } catch (_) { recentTasks.value = [] }
}

function recordRecentTask(snapshot) {
  if (!snapshot?.taskId || !['SUCCESS', 'FAILED', 'CANCELLED'].includes(snapshot.status)) return
  const entry = {
    taskId: snapshot.taskId,
    status: snapshot.status,
    sourceLabel: selectedRoute.value?.sourceLabel || snapshot.sourceFormat?.toUpperCase(),
    targetLabel: selectedRoute.value?.targetLabel || snapshot.targetFormat?.toUpperCase(),
    fileCount: Array.isArray(snapshot.files) && snapshot.files.length > 0 ? snapshot.files.length : files.value.length,
    updatedAt: snapshot.updatedAt || new Date().toISOString()
  }
  recentTasks.value = [entry, ...recentTasks.value.filter(item => item.taskId !== entry.taskId)].slice(0, 12)
  try { localStorage.setItem('format-converter-recent-tasks', JSON.stringify(recentTasks.value)) } catch (_) { /* session still works */ }
}

function clearRecentTasks() {
  recentTasks.value = []
  try { localStorage.removeItem('format-converter-recent-tasks') } catch (_) { /* ignore */ }
}

function onDocumentClick(event) {
  if (!routePickerRef.value?.contains(event.target)) closeRoutePicker()
}

function onRoutePickerKeydown(event) {
  if (event.key === 'Escape') closeRoutePicker(true)
}

async function loadCapabilities() {
  try {
    const response = await fetch('/api/tasks/capabilities', { cache: 'no-store' })
    if (!response.ok) {
      capabilityMessage.value = response.status === 401
        ? '转换能力接口需要访问令牌，请从桌面应用或正确配置的本地入口打开。'
        : `转换能力加载失败（${response.status}），当前仅显示基础路线。`
      return
    }
    const routes = await response.json()
    if (Array.isArray(routes) && routes.length) {
      capabilityMessage.value = ''
      conversions.value = routes
      if (!routes.some(route => route.id === selectedRouteId.value)) selectedRouteId.value = routes[0].id
      if (selectedRoute.value?.status !== 'available' && availableRoutes.value.length) {
        selectedRouteId.value = availableRoutes.value[0].id
      }
    }
  } catch (_) {
    conversions.value = fallbackConversions
    capabilityMessage.value = '暂时无法连接转换服务，当前仅显示基础路线。'
  }
}

async function loadLimits() {
  diagnosticsFailed.value = false
  try {
    const response = await fetch('/api/diagnostics', { cache: 'no-store' })
    if (!response.ok) {
      diagnosticsFailed.value = true
      return
    }
    const payload = await response.json()
    diagnostics.value = payload
    const configured = payload?.limits || {}
    if (Number.isFinite(configured.maxFileSize) && configured.maxFileSize > 0) limits.value.maxFileSize = configured.maxFileSize
    if (Number.isInteger(configured.maxFilesPerTask) && configured.maxFilesPerTask > 0) limits.value.maxFilesPerTask = configured.maxFilesPerTask
    if (Number.isFinite(configured.maxTaskUploadBytes) && configured.maxTaskUploadBytes > 0) limits.value.maxTaskUploadBytes = configured.maxTaskUploadBytes
  } catch (_) {
    diagnosticsFailed.value = true
  }
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

function formatTaskTime(value) {
  if (!value) return '刚刚'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '最近'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}

function recentStatusLabel(status) {
  return ({ SUCCESS: '已完成', FAILED: '失败', CANCELLED: '已取消' })[status] || status
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
    else {
      busy.value = false
      recordRecentTask(task.value)
    }
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
    recordRecentTask(task.value)
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
  loadRecentTasks()
  loadCapabilities()
  loadLimits()
  document.addEventListener('click', onDocumentClick)
  window.addEventListener('resize', positionRouteMenu)
})
onBeforeUnmount(() => {
  clearTimeout(pollTimer)
  document.removeEventListener('click', onDocumentClick)
  window.removeEventListener('resize', positionRouteMenu)
})
</script>

<template>
  <div class="desktop-app">
    <aside class="app-sidebar">
      <button class="app-brand" type="button" aria-label="返回概览" @click="navigate('overview')">
        <span class="app-logo" aria-hidden="true"><b>F</b><i></i></span>
        <span><strong>Fuyue Convert</strong><small>LOCAL DESKTOP</small></span>
      </button>

      <nav class="side-nav" aria-label="应用导航">
        <button v-for="item in navItems" :key="item.id" type="button" :class="{ active: activeView === item.id }" :aria-label="item.label" :title="item.label" @click="navigate(item.id)">
          <span class="nav-icon" aria-hidden="true">
            <svg v-if="item.id === 'overview'" viewBox="0 0 24 24" fill="none"><path d="M4 13h7V4H4zM13 20h7V11h-7zM4 20h7v-5H4zM13 9h7V4h-7z"/></svg>
            <svg v-else-if="item.id === 'convert'" viewBox="0 0 24 24" fill="none"><path d="M4 7h12M13 4l3 3-3 3M20 17H8M11 14l-3 3 3 3"/></svg>
            <svg v-else-if="item.id === 'pdf'" viewBox="0 0 24 24" fill="none"><path d="M6 3h8l4 4v14H6zM14 3v5h4M9 13h6M9 17h4"/></svg>
            <svg v-else-if="item.id === 'history'" viewBox="0 0 24 24" fill="none"><path d="M4.8 8A8 8 0 1 1 4 14M4 4v4h4M12 8v5l3 2"/></svg>
            <svg v-else viewBox="0 0 24 24" fill="none"><path d="M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z"/><path d="m19 13.5 1.5 1.2-1.8 3.1-1.9-.7a7 7 0 0 1-2.3 1.3L14.2 21h-4.4l-.3-2.6a7 7 0 0 1-2.3-1.3l-1.9.7-1.8-3.1L5 13.5a7 7 0 0 1 0-3L3.5 9.3l1.8-3.1 1.9.7a7 7 0 0 1 2.3-1.3L9.8 3h4.4l.3 2.6a7 7 0 0 1 2.3 1.3l1.9-.7 1.8 3.1-1.5 1.2a7 7 0 0 1 0 3z"/></svg>
          </span>
          <span>{{ item.label }}</span>
          <i v-if="item.id === 'history' && recentTasks.length" class="nav-count">{{ recentTasks.length }}</i>
        </button>
      </nav>

      <div class="sidebar-service">
        <span class="service-orb" :class="{ online: serviceHealthy }"></span>
        <span><strong>{{ serviceHealthy ? '本地服务在线' : '正在连接服务' }}</strong><small>数据不会离开设备</small></span>
      </div>
    </aside>

    <main class="app-shell">
      <header class="app-header">
        <div class="page-heading"><span>{{ viewTitle[0] }}</span><small>{{ viewTitle[1] }}</small></div>
        <div class="header-actions">
          <span class="local-chip"><i></i> LOCAL ONLY</span>
          <button type="button" class="header-cta" @click="navigate('convert')"><b>＋</b> 新建转换</button>
        </div>
      </header>

      <div ref="appScrollRef" class="app-scroll" @scroll.passive="positionRouteMenu">
        <aside v-if="capabilityMessage" class="global-notice" role="status">
          <span>!</span><p><strong>转换能力未完整加载</strong><small>{{ capabilityMessage }}</small></p>
          <button type="button" @click="loadCapabilities">重新连接</button>
        </aside>
        <section v-show="activeView === 'overview'" class="dashboard-page">
          <article class="welcome-banner">
            <div>
              <p><span></span> FORMAT WORKSPACE / {{ diagnostics?.version || '0.1.3' }}</p>
              <h1>欢迎回来，开始处理文档。</h1>
              <small>转换、整理和导出都在本机完成。你可以从常用路线开始，也可以进入完整工作台。</small>
              <button type="button" @click="navigate('convert')">开始新任务 <span>→</span></button>
            </div>
            <div class="banner-visual" aria-hidden="true">
              <span class="doc-card one">PDF</span><span class="doc-card two">DOCX</span><span class="doc-card three">OFD</span>
              <i class="orbit one"></i><i class="orbit two"></i>
            </div>
          </article>

          <div class="metric-grid" aria-label="运行概览">
            <article><span class="metric-icon blue">↗</span><div><small>可用路线</small><strong>{{ availableRoutes.length }}</strong><em>覆盖 {{ availableSourceCount }} 种可用输入格式</em></div></article>
            <article><span class="metric-icon violet">✓</span><div><small>稳定路线</small><strong>{{ stableRoutes.length }}</strong><em>{{ betaRoutes.length }} 条 Beta 持续优化</em></div></article>
            <article><span class="metric-icon cyan">▣</span><div><small>已完成任务</small><strong>{{ successfulTasks }}</strong><em>仅记录任务摘要</em></div></article>
            <article><span class="metric-icon green">●</span><div><small>服务状态</small><strong>{{ serviceHealthy ? '正常' : '连接中' }}</strong><em>独立 Worker {{ diagnostics?.limits?.workerEnabled ? '已启用' : '检测中' }}</em></div></article>
          </div>

          <div class="dashboard-grid">
            <section class="dash-panel quick-panel">
              <div class="panel-title"><div><small>QUICK ACTIONS</small><h2>常用转换</h2></div><button type="button" @click="navigate('convert')">查看全部</button></div>
              <div class="quick-grid">
                <button v-for="(route, index) in quickRoutes" :key="route.id" type="button" @click="openRoute(route)">
                  <span :class="`quick-icon tone-${index % 4}`">{{ route.sourceFormat.slice(0, 3).toUpperCase() }}</span>
                  <span><strong>{{ formatRouteLabel(route) }}</strong><small>{{ route.description }}</small></span>
                  <i>→</i>
                </button>
              </div>
            </section>

            <aside class="dash-panel engine-panel">
              <div class="panel-title"><div><small>LOCAL ENGINES</small><h2>运行环境</h2></div><span class="health-pill">{{ serviceHealthy ? 'HEALTHY' : 'LOADING' }}</span></div>
              <div class="engine-list">
                <div><span class="engine-dot" :class="{ online: serviceHealthy }"></span><p><strong>转换服务</strong><small>Java {{ diagnostics?.runtime?.javaVersion || '17' }}</small></p><b>{{ serviceHealthy ? '在线' : '检测中' }}</b></div>
                <div><span class="engine-dot" :class="{ online: diagnostics?.office?.available }"></span><p><strong>Office 引擎</strong><small>{{ diagnostics?.office?.binaryName || 'LibreOffice' }}</small></p><b>{{ diagnostics ? (diagnostics.office?.available ? '可用' : '不可用') : (diagnosticsFailed ? '检测失败' : '检测中') }}</b></div>
                <div><span class="engine-dot" :class="{ online: diagnostics?.ocr?.available }"></span><p><strong>OCR 识别</strong><small>本地文字识别</small></p><b>{{ diagnostics ? (diagnostics.ocr?.available ? '可用' : '未启用') : (diagnosticsFailed ? '检测失败' : '检测中') }}</b></div>
              </div>
              <button type="button" class="engine-action" @click="navigate('settings')">查看系统详情 <span>→</span></button>
            </aside>
          </div>

          <section class="dash-panel recent-panel">
            <div class="panel-title"><div><small>RECENT ACTIVITY</small><h2>最近任务</h2></div><button type="button" @click="navigate('history')">任务记录</button></div>
            <div v-if="recentTasks.length" class="recent-list compact">
              <div v-for="item in recentTasks.slice(0, 4)" :key="item.taskId">
                <span class="history-icon">{{ item.sourceLabel?.slice(0, 3) }}</span>
                <p><strong>{{ item.sourceLabel }} → {{ item.targetLabel }}</strong><small>{{ item.fileCount }} 个文件 · {{ formatTaskTime(item.updatedAt) }}</small></p>
                <em :class="item.status.toLowerCase()">{{ recentStatusLabel(item.status) }}</em>
              </div>
            </div>
            <div v-else class="empty-state compact"><span>⌁</span><p><strong>还没有转换记录</strong><small>完成第一项任务后会在这里显示摘要。</small></p><button type="button" @click="navigate('convert')">开始转换</button></div>
          </section>
        </section>

        <section v-show="activeView === 'pdf'" class="content-page pdf-page">
          <div class="section-intro"><span>PDF LAB</span><h2>一组专注、可靠的 PDF 工具。</h2><p>不上传云端，所有修改在本机完成。选择一个工具即可进入工作台。</p></div>
          <div class="tool-card-grid">
            <button v-for="(route, index) in pdfToolRoutes" :key="route.id" type="button" :disabled="route.status !== 'available'" @click="openRoute(route)">
              <span class="tool-number">0{{ index + 1 }}</span><span class="tool-symbol">{{ ['↘', '⊕', '✂', 'W'][index] }}</span>
              <div><small>{{ routeBadge(route) }} · {{ strategyLabel(route) }}</small><h3>{{ route.targetLabel }}</h3><p>{{ route.description }}</p></div><i>进入工具 →</i>
            </button>
          </div>
          <aside class="privacy-banner"><span>◆</span><div><strong>保护原始文档</strong><small>数字签名文件会被严格拒绝修改，转换限制会在执行前后明确展示。</small></div></aside>
        </section>

        <section v-show="activeView === 'history'" class="content-page history-page">
          <div class="content-toolbar"><div><span>LOCAL ACTIVITY</span><h2>最近任务</h2><p>这里只保存路线、状态与时间，不保存文件内容。</p></div><button v-if="recentTasks.length" type="button" @click="clearRecentTasks">清空记录</button></div>
          <div v-if="recentTasks.length" class="recent-list full">
            <div v-for="item in recentTasks" :key="item.taskId">
              <span class="history-icon">{{ item.sourceLabel?.slice(0, 3) }}</span>
              <p><strong>{{ item.sourceLabel }} → {{ item.targetLabel }}</strong><small>任务 {{ item.taskId.slice(0, 8) }} · {{ item.fileCount }} 个文件</small></p>
              <time>{{ formatTaskTime(item.updatedAt) }}</time><em :class="item.status.toLowerCase()">{{ recentStatusLabel(item.status) }}</em>
            </div>
          </div>
          <div v-else class="empty-state large"><span>⌁</span><p><strong>暂无本地任务记录</strong><small>开始转换后，任务摘要会显示在这里。</small></p><button type="button" @click="navigate('convert')">创建任务</button></div>
        </section>

        <section v-show="activeView === 'settings'" class="content-page settings-page">
          <div class="settings-grid">
            <section class="settings-card"><div class="settings-head"><span>01</span><div><strong>应用信息</strong><small>当前运行版本与平台</small></div></div><dl><div><dt>版本</dt><dd>{{ diagnostics?.version || '0.1.3' }}</dd></div><div><dt>系统</dt><dd>{{ diagnostics?.runtime?.os || '检测中' }} · {{ diagnostics?.runtime?.arch || '' }}</dd></div><div><dt>处理器</dt><dd>{{ diagnostics?.runtime?.availableProcessors || '—' }} 核心</dd></div></dl></section>
            <section class="settings-card"><div class="settings-head"><span>02</span><div><strong>转换引擎</strong><small>本机依赖可用状态</small></div></div><dl><div><dt>Office</dt><dd :class="{ good: diagnostics?.office?.available }">{{ diagnostics?.office?.message || '检测中' }}</dd></div><div><dt>OCR</dt><dd :class="{ good: diagnostics?.ocr?.available }">{{ diagnostics?.ocr?.message || '检测中' }}</dd></div><div><dt>Worker</dt><dd :class="{ good: diagnostics?.limits?.workerEnabled }">{{ diagnostics?.limits?.workerEnabled ? '独立进程已启用' : '未启用' }}</dd></div></dl></section>
            <section class="settings-card"><div class="settings-head"><span>03</span><div><strong>资源限制</strong><small>保护本机运行稳定</small></div></div><dl><div><dt>单文件</dt><dd>{{ formatBytes(limits.maxFileSize) }}</dd></div><div><dt>单任务</dt><dd>{{ limits.maxFilesPerTask }} 个文件</dd></div><div><dt>并发任务</dt><dd>{{ diagnostics?.limits?.concurrency || '—' }}</dd></div></dl></section>
            <section class="settings-card action-card"><div class="settings-head"><span>04</span><div><strong>诊断信息</strong><small>复制脱敏后的运行状态</small></div></div><p>遇到转换问题时，可复制诊断信息随问题反馈提交。</p><button type="button" @click="copyDiagnostics">复制诊断信息</button><small v-if="diagnosticMessage">{{ diagnosticMessage }}</small></section>
          </div>
        </section>

        <section v-show="activeView === 'convert'" class="workspace">
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
              ref="routeTriggerRef"
              id="route"
              type="button"
              class="route-trigger"
              :disabled="busy"
              aria-haspopup="listbox"
              aria-controls="route-menu"
              :aria-expanded="routePickerOpen"
              aria-labelledby="route-label route"
              @click.stop="toggleRoutePicker"
            >
              <span class="route-formats">
                <b>{{ selectedRoute.sourceLabel }}</b><i aria-hidden="true">→</i><b>{{ selectedRoute.targetLabel }}</b>
              </span>
              <span class="chevron" aria-hidden="true"></span>
            </button>

            <div v-if="routePickerOpen" id="route-menu" ref="routeMenuRef" class="route-menu" :style="routeMenuStyle" @click.stop>
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
                <aside class="source-sidebar" aria-label="按来源格式筛选">
                  <div class="source-quick">
                    <button
                      v-for="source in quickSourceOptions"
                      :key="source.id"
                      type="button"
                      :class="{ active: pickerSource === source.id && !routeSearch.trim() }"
                      @click="selectPickerSource(source.id)"
                    >
                      <span>{{ source.id === 'popular' ? '★' : '◆' }}</span>
                      <strong>{{ source.label }}</strong>
                      <small>{{ source.count }}</small>
                    </button>
                  </div>
                  <div class="source-list">
                    <p>按文件类型</p>
                    <button
                      v-for="source in formatSourceOptions"
                      :key="source.id"
                      type="button"
                      :class="{ active: pickerSource === source.id && !routeSearch.trim() }"
                      @click="selectPickerSource(source.id)"
                    >
                      <span>{{ source.id.slice(0, 3).toUpperCase() }}</span>
                      <strong>{{ source.label }}</strong>
                      <small>{{ source.count }}</small>
                    </button>
                  </div>
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
            <details v-if="selectedRoute.limitations?.length" class="route-limitations">
              <summary>查看适用边界 · {{ selectedRoute.limitations.length }} 项</summary>
              <ul><li v-for="item in selectedRoute.limitations" :key="item">{{ item }}</li></ul>
            </details>
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
      </div>

      <footer class="app-statusbar">
        <span><i :class="{ online: serviceHealthy }"></i>{{ serviceHealthy ? '本地服务已连接' : '正在连接本地服务' }}</span>
        <span>文档不会上传云端</span>
        <span>v{{ diagnostics?.version || '0.1.3' }}</span>
      </footer>
    </main>
  </div>
</template>
