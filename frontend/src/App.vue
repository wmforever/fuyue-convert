<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ImageCollectionPreview from './components/ImageCollectionPreview.vue'
import PdfPreview from './components/PdfPreview.vue'
import { blocksPdfSubmission, loadPdfJs, pdfPreviewError } from './pdfPreviewRuntime.js'

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
const historyLoading = ref(false)
const historyMessage = ref('')
const downloadingTaskId = ref('')
const compressionMode = ref('balanced')
const splitPages = ref('all')
const watermarkText = ref('机密资料')
const watermarkOpacity = ref(0.18)
const watermarkAngle = ref(35)
const watermarkPosition = ref('center')
const watermarkTiled = ref(false)
const watermarkPages = ref('all')
const watermarkColor = ref('#969696')
const watermarkPreviewCanvas = ref(null)
const watermarkPreviewStage = ref(null)
const watermarkPreviewRef = ref(null)
const watermarkPreviewState = ref('empty')
const watermarkPreviewError = ref('')
const watermarkPreviewBlocksSubmit = ref(false)
const watermarkPreviewCheckedFile = ref(null)
const watermarkPreviewPage = ref(1)
const watermarkPreviewPageCount = ref(0)
const watermarkPreviewWidth = ref(0)
const watermarkPreviewHeight = ref(0)
const watermarkPreviewScale = ref(1)
const pdfSourcePreviewRef = ref(null)
const pdfSourcePreviewState = ref('empty')
const pdfSourcePreviewError = ref('')
const pdfSourcePreviewBlocksSubmit = ref(false)
const pdfSourcePreviewCheckedFile = ref(null)
const pdfSourcePreviewPage = ref(1)
const pdfSourcePreviewPageCount = ref(0)
const pdfSourcePreviewFileIndex = ref(0)
const submittedBatchFingerprint = ref('')
const resultPreviewBlob = ref(null)
const resultPreviewKind = ref('')
const resultPreviewText = ref('')
const resultPreviewImageUrl = ref('')
const resultPreviewState = ref('empty')
const resultPreviewError = ref('')
const autoDownload = ref(false)
const preferenceMessage = ref('')
const desktopRuntime = ref(false)
const limits = ref({ maxFileSize: 50 * 1024 * 1024, maxFilesPerTask: 100, maxTaskUploadBytes: 250 * 1024 * 1024 })
let pollTimer
let historyTimer
let healthTimer
let pollGeneration = 0
let pollFailures = 0
let uploadRequest = null
let watermarkPdfDocument = null
let watermarkPdfLoadingTask = null
let watermarkPdfRenderTask = null
let watermarkPreviewGeneration = 0
let watermarkRenderGeneration = 0
let watermarkResizeTimer
let resultPreviewGeneration = 0
let fileIdentitySequence = 0
const fileIdentities = new WeakMap()
const autoDownloadedTaskIds = new Set()

const selectedRoute = computed(() => conversions.value.find(route => route.id === selectedRouteId.value) || conversions.value[0])
const routeRuntimeWarning = computed(() => {
  const route = selectedRoute.value
  if (route?.qualityLevel !== 'beta' || diagnostics.value?.ocr?.available !== false) return ''
  const usesOptionalOcr = (route.limitations || []).some(item => /OCR/i.test(item))
  if (!usesOptionalOcr) return ''
  return `当前 OCR 未启用：请仅使用文字型 ${route.sourceLabel}；扫描页或含扫描页的文档会严格失败。`
})
const isPdfMergeRoute = computed(() => selectedRoute.value?.targetFormat === 'pdf-merge')
const isSinglePdfTool = computed(() => ['pdf-split', 'pdf-watermark', 'pdf-compress'].includes(selectedRoute.value?.targetFormat))
const isPdfCompressRoute = computed(() => selectedRoute.value?.targetFormat === 'pdf-compress')
const isPdfWatermarkRoute = computed(() => selectedRoute.value?.targetFormat === 'pdf-watermark')
const isPdfSplitRoute = computed(() => selectedRoute.value?.targetFormat === 'pdf-split')
const isPdfInputRoute = computed(() => selectedRoute.value?.sourceFormat === 'pdf')
const isImageToPdfRoute = computed(() => ['png', 'jpg'].includes(selectedRoute.value?.sourceFormat)
  && selectedRoute.value?.targetFormat === 'pdf')
const hasToolOptions = computed(() => isPdfCompressRoute.value || isPdfWatermarkRoute.value || isPdfSplitRoute.value)
const watermarkPagesValid = computed(() => validWatermarkPages(watermarkPages.value))
const watermarkPreviewFile = computed(() => isPdfWatermarkRoute.value ? files.value[0] || null : null)
const watermarkPreviewPending = computed(() => Boolean(watermarkPreviewFile.value)
  && watermarkPreviewCheckedFile.value !== watermarkPreviewFile.value)
const watermarkAppliesToPreviewPage = computed(() => watermarkPagesValid.value
  && pageMatchesRange(watermarkPreviewPage.value, watermarkPages.value))
const watermarkPreviewOverlayStyle = computed(() => {
  const scale = watermarkPreviewScale.value || 1
  const shortestPageEdge = Math.min(
    watermarkPreviewWidth.value / scale || 0,
    watermarkPreviewHeight.value / scale || 0
  )
  const baseSize = Math.max(18, Math.min(76, shortestPageEdge / 9)) * scale
  const characters = Array.from(watermarkText.value)
  const estimatedUnits = characters.reduce((total, character) => total
    + (/\s/.test(character) ? 0.34 : (/^[\x00-\xff]$/.test(character) ? 0.6 : 1)), 0)
  const maxTextWidth = watermarkPreviewWidth.value * (watermarkTiled.value ? 0.24 : 0.7)
  const fittedSize = estimatedUnits > 0 ? Math.min(baseSize, maxTextWidth / estimatedUnits) : baseSize
  const fontSize = Math.max(11 * scale, fittedSize)
  const padding = Math.max(24, shortestPageEdge * 0.06) * scale
  const textWidth = estimatedUnits * fontSize
  const angleRadians = Math.abs(watermarkAngle.value) * Math.PI / 180
  const rotatedHalfWidth = Math.abs(Math.cos(angleRadians)) * textWidth / 2
    + Math.abs(Math.sin(angleRadians)) * fontSize / 2
  const rotatedHalfHeight = Math.abs(Math.sin(angleRadians)) * textWidth / 2
    + Math.abs(Math.cos(angleRadians)) * fontSize / 2
  const leftAnchor = Math.min(watermarkPreviewWidth.value / 2, padding + rotatedHalfWidth)
  const rightAnchor = Math.max(watermarkPreviewWidth.value / 2,
    watermarkPreviewWidth.value - padding - rotatedHalfWidth)
  const topAnchor = Math.min(watermarkPreviewHeight.value / 2, padding + rotatedHalfHeight)
  const bottomAnchor = Math.max(watermarkPreviewHeight.value / 2,
    watermarkPreviewHeight.value - padding - rotatedHalfHeight)
  const [anchorX, anchorY] = ({
    'top-left': [leftAnchor, topAnchor],
    'top-right': [rightAnchor, topAnchor],
    'bottom-left': [leftAnchor, bottomAnchor],
    'bottom-right': [rightAnchor, bottomAnchor]
  })[watermarkPosition.value] || [watermarkPreviewWidth.value / 2, watermarkPreviewHeight.value / 2]
  return {
    color: watermarkColor.value,
    '--watermark-opacity': watermarkOpacity.value,
    // PDF coordinates grow upward while CSS coordinates grow downward.
    '--watermark-angle': `${-watermarkAngle.value}deg`,
    '--watermark-font-size': `${fontSize}px`,
    '--watermark-anchor-x': `${anchorX}px`,
    '--watermark-anchor-y': `${anchorY}px`
  }
})
const watermarkPreviewStatus = computed(() => {
  if (watermarkPreviewState.value === 'empty') return '添加 PDF 后查看应用页面'
  if (watermarkPreviewState.value === 'loading') return '正在读取 PDF 页数'
  if (watermarkPreviewState.value === 'rendering') return '正在更新当前页预览'
  if (watermarkPreviewState.value === 'error') return watermarkPreviewBlocksSubmit.value
    ? '该文件无法处理，请更换 PDF'
    : '预览不可用，可提交后查看服务端诊断'
  if (!watermarkPagesValid.value) return '页码范围有误，预览暂不叠加水印'
  if (watermarkRangeState.value.matches === false) return `应用范围未匹配这份 PDF（共 ${watermarkPreviewPageCount.value} 页）`
  if (!watermarkText.value.trim()) return '请输入水印文字以查看效果'
  if (watermarkAppliesToPreviewPage.value) return `第 ${watermarkPreviewPage.value} 页会添加水印`
  return `第 ${watermarkPreviewPage.value} 页不在应用范围内`
})
const showPdfSourcePreview = computed(() => isPdfInputRoute.value && !isPdfWatermarkRoute.value && files.value.length > 0)
const pdfSourcePreviewFile = computed(() => {
  if (!showPdfSourcePreview.value) return null
  const index = Math.min(Math.max(0, pdfSourcePreviewFileIndex.value), files.value.length - 1)
  return files.value[index] || null
})
const pdfSourcePreviewPending = computed(() => Boolean(pdfSourcePreviewFile.value)
  && pdfSourcePreviewCheckedFile.value !== pdfSourcePreviewFile.value)
const resultPreviewEligible = computed(() => task.value?.status === 'SUCCESS'
  && task.value?.downloadReady
  && /\.(pdf|png|jpe?g|txt|csv)$/i.test(task.value?.downloadName || ''))
const resultPreviewTitle = computed(() => batchSettingsDirty.value ? '上次转换结果预览' : '转换结果预览')
const watermarkRangeState = computed(() => {
  if (!watermarkPagesValid.value) return { matches: false, overflow: false }
  const pageCount = watermarkPreviewPageCount.value
  if (!pageCount) return { matches: null, overflow: false }
  const normalized = watermarkPages.value.replace(/\s+/g, '').toLowerCase()
  if (normalized === 'all') return { matches: true, overflow: false }
  const ranges = normalized.split(',').map(part => {
    const [start, end = start] = part.split('-').map(Number)
    return { start, end }
  })
  return {
    matches: ranges.some(range => range.start <= pageCount),
    overflow: ranges.some(range => range.end > pageCount)
  }
})
const splitPagesValid = computed(() => validWatermarkPages(splitPages.value))
const splitRangeState = computed(() => pageRangeState(splitPages.value, pdfSourcePreviewPageCount.value))
const splitSelectedPages = computed(() => {
  if (!splitPagesValid.value || !pdfSourcePreviewPageCount.value || splitRangeState.value.overflow) return []
  return Array.from({ length: pdfSourcePreviewPageCount.value }, (_, index) => index + 1)
    .filter(page => pageMatchesRange(page, splitPages.value))
})
const toolOptionsValid = computed(() => (!isPdfWatermarkRoute.value
  || (watermarkText.value.trim().length > 0
    && watermarkPagesValid.value
    && watermarkRangeState.value.matches !== false
    && !watermarkPreviewPending.value
    && !watermarkPreviewBlocksSubmit.value))
  && (!isPdfSplitRoute.value || (splitPagesValid.value
    && splitRangeState.value.matches !== false
    && !splitRangeState.value.overflow))
  && (!(isSinglePdfTool.value && !isPdfWatermarkRoute.value && pdfSourcePreviewFile.value)
    || (!pdfSourcePreviewPending.value && !pdfSourcePreviewBlocksSubmit.value)))
const routeFileLimit = computed(() => isSinglePdfTool.value ? 1 : limits.value.maxFilesPerTask)
const canSubmit = computed(() => files.value.length >= (isPdfMergeRoute.value ? 2 : 1)
  && !busy.value && toolOptionsValid.value && selectedRoute.value?.status === 'available')
const availableRoutes = computed(() => conversions.value.filter(route => route.status === 'available'))
const availableSourceCount = computed(() => new Set(availableRoutes.value.map(route => route.sourceFormat)).size)
const stableRoutes = computed(() => conversions.value.filter(route => route.status === 'available' && route.qualityLevel === 'stable'))
const betaRoutes = computed(() => conversions.value.filter(route => route.status === 'available' && route.qualityLevel === 'beta'))
const experimentalRoutes = computed(() => conversions.value.filter(route => route.status === 'available' && route.qualityLevel === 'experimental'))
const pdfToolRoutes = computed(() => pdfToolRouteIds.map(id => conversions.value.find(route => route.id === id)).filter(Boolean))
const quickRoutes = computed(() => popularRouteIds.map(id => conversions.value.find(route => route.id === id)).filter(route => route?.status === 'available').slice(0, 6))
const successfulTasks = computed(() => recentTasks.value.filter(item => item.status === 'SUCCESS').length)
const serviceHealthy = computed(() => Boolean(diagnostics.value))
const selectedBytes = computed(() => files.value.reduce((total, file) => total + file.size, 0))
const acceptExtension = computed(() => selectedRoute.value?.inputExtension || '.ofd')
const acceptExtensions = computed(() => {
  if (selectedRoute.value?.sourceFormat === 'jpg') return ['.jpg', '.jpeg']
  if (selectedRoute.value?.sourceFormat === 'uof') return ['.uof', '.uot']
  return [acceptExtension.value]
})
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
const routeQualityFilters = {
  stable: 'stable', '稳定': 'stable',
  beta: 'beta', '测试版': 'beta',
  experimental: 'experimental', '实验': 'experimental', '试验': 'experimental'
}
const routeStatusFilters = {
  available: 'available', '可用': 'available',
  unavailable: 'unavailable', '不可用': 'unavailable', '缺少依赖': 'unavailable',
  planned: 'planned', '规划中': 'planned'
}
const sourceOrder = ['pdf', 'ofd', 'docx', 'txt', 'xlsx', 'csv', 'png', 'jpg', 'pptx', 'wps', 'et', 'dps', 'uof']
const sourceOptions = computed(() => {
  const sources = new Map()
  for (const route of conversions.value) {
    if (!sources.has(route.sourceFormat)) {
      sources.set(route.sourceFormat, { id: route.sourceFormat, label: route.sourceLabel, count: 0, availableCount: 0 })
    }
    const source = sources.get(route.sourceFormat)
    source.count++
    if (route.status === 'available') source.availableCount++
  }
  const options = Array.from(sources.values()).sort((a, b) => {
    const left = sourceOrder.indexOf(a.id)
    const right = sourceOrder.indexOf(b.id)
    return (left < 0 ? 999 : left) - (right < 0 ? 999 : right)
  })
  const popular = popularRouteIds.map(id => conversions.value.find(route => route.id === id)).filter(Boolean)
  const pdfTools = pdfToolRouteIds.map(id => conversions.value.find(route => route.id === id)).filter(Boolean)
  const beta = conversions.value.filter(route => route.qualityLevel === 'beta')
  return [
    { id: 'popular', label: '常用转换', count: popular.length, availableCount: popular.filter(route => route.status === 'available').length },
    { id: 'pdf-tools', label: 'PDF 工具', count: pdfTools.length, availableCount: pdfTools.filter(route => route.status === 'available').length },
    { id: 'beta', label: 'Beta 路线', count: beta.length, availableCount: beta.filter(route => route.status === 'available').length },
    ...options
  ]
})
const quickSourceOptions = computed(() => sourceOptions.value.filter(source => ['popular', 'pdf-tools', 'beta'].includes(source.id)))
const formatSourceOptions = computed(() => sourceOptions.value.filter(source => !['popular', 'pdf-tools', 'beta'].includes(source.id)))
const pickerRoutes = computed(() => {
  const keywords = routeSearch.value.trim().toLowerCase().split(/\s+/).filter(Boolean)
  if (keywords.length) return conversions.value.map(route => {
    const aliases = routeSearchAliases[route.targetFormat] || ''
    const qualityAliases = ({
      stable: 'stable 稳定',
      beta: 'beta 测试版',
      experimental: 'experimental 实验 试验',
      planned: 'planned 规划中'
    })[route.qualityLevel] || ''
    const statusAliases = route.status === 'unavailable'
      ? 'unavailable 不可用 缺少依赖'
      : (route.status === 'planned' ? 'planned 规划中' : 'available 可用')
    const requirements = (route.requires || []).join(' ')
    const strategy = strategyLabel(route)
    const text = `${route.id} ${route.sourceFormat} ${route.targetFormat} ${route.sourceLabel} ${route.targetLabel} ${route.description} ${aliases} ${qualityAliases} ${statusAliases} ${requirements} ${strategy}`.toLowerCase()
    if (!keywords.every(keyword => {
      if (routeQualityFilters[keyword]) return route.qualityLevel === routeQualityFilters[keyword]
      if (routeStatusFilters[keyword]) return route.status === routeStatusFilters[keyword]
      return text.includes(keyword)
    })) return null
    const labels = `${route.sourceLabel} ${route.targetLabel}`.toLowerCase()
    const formats = `${route.sourceFormat} ${route.targetFormat}`.toLowerCase()
    const score = keywords.reduce((total, keyword) => total
      + (formats.includes(keyword) ? 6 : 0)
      + (labels.includes(keyword) ? 4 : 0)
      + (routeQualityFilters[keyword] === route.qualityLevel || routeStatusFilters[keyword] === route.status ? 3 : 0)
      + (`${aliases} ${requirements} ${strategy}`.toLowerCase().includes(keyword) ? 2 : 0), 0)
    return { route, score }
  }).filter(Boolean).sort((left, right) => right.score - left.score).map(item => item.route)
  if (pickerSource.value === 'popular') {
    return popularRouteIds.map(id => conversions.value.find(route => route.id === id)).filter(Boolean)
  }
  if (pickerSource.value === 'pdf-tools') {
    return pdfToolRouteIds.map(id => conversions.value.find(route => route.id === id)).filter(Boolean)
  }
  if (pickerSource.value === 'beta') {
    return conversions.value.filter(route => route.qualityLevel === 'beta')
  }
  return conversions.value.filter(route => route.sourceFormat === pickerSource.value)
})
const pickerTitle = computed(() => {
  if (routeSearch.value.trim()) return `搜索结果 · ${pickerRoutes.value.length}`
  if (pickerSource.value === 'popular') return '常用转换'
  if (pickerSource.value === 'pdf-tools') return 'PDF 实用工具'
  if (pickerSource.value === 'beta') return `Beta 路线 · ${pickerRoutes.value.length}`
  const source = sourceOptions.value.find(option => option.id === pickerSource.value)
  return `${source?.label || '当前格式'} 可以转换为`
})
const pickerHint = computed(() => pickerRoutes.value.some(route => route.status === 'available')
  ? (pickerSource.value === 'beta' && !routeSearch.value.trim()
      ? '建议选择后先查看适用边界，再用代表性文件验证'
      : '选择一个目标格式即可')
  : '当前结果均不可执行，请检查运行依赖')

const viewTitle = computed(() => ({
  overview: ['概览', '掌握本地转换服务和最近任务'],
  convert: ['转换工作台', '选择路线并处理你的文件'],
  pdf: ['PDF 工具', '合并、拆分、压缩与文字水印'],
  history: ['任务记录', '恢复、重下或重试转换服务中的任务'],
  settings: ['运行设置', '调整使用偏好并查看引擎诊断状态']
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

function pageMatchesRange(pageNumber, value) {
  const normalized = String(value || '').replace(/\s+/g, '').toLowerCase()
  if (normalized === 'all') return true
  if (!validWatermarkPages(normalized)) return false
  return normalized.split(',').some(part => {
    const [start, end = start] = part.split('-').map(Number)
    return pageNumber >= start && pageNumber <= end
  })
}

function pageRangeState(value, pageCount) {
  if (!validWatermarkPages(value)) return { matches: false, overflow: false }
  if (!pageCount) return { matches: null, overflow: false }
  const normalized = String(value || '').replace(/\s+/g, '').toLowerCase()
  if (normalized === 'all') return { matches: true, overflow: false }
  const ranges = normalized.split(',').map(part => {
    const [start, end = start] = part.split('-').map(Number)
    return { start, end }
  })
  return {
    matches: ranges.some(range => range.start <= pageCount),
    overflow: ranges.some(range => range.end > pageCount)
  }
}

function currentWatermarkSettings() {
  return {
    text: watermarkText.value.trim(),
    opacity: Number(watermarkOpacity.value),
    angle: Number(watermarkAngle.value),
    position: watermarkPosition.value,
    tiled: Boolean(watermarkTiled.value),
    pages: watermarkPages.value.replace(/\s+/g, '').toLowerCase(),
    color: watermarkColor.value.toLowerCase()
  }
}

function fileIdentity(file) {
  if (!fileIdentities.has(file)) fileIdentities.set(file, ++fileIdentitySequence)
  return fileIdentities.get(file)
}

function currentRouteOptions() {
  if (isPdfWatermarkRoute.value) return currentWatermarkSettings()
  if (isPdfCompressRoute.value) return { compressionMode: compressionMode.value }
  if (isPdfSplitRoute.value) return { splitPages: splitPages.value.replace(/\s+/g, '').toLowerCase() }
  return {}
}

function currentBatchFingerprint() {
  return JSON.stringify({
    routeId: selectedRouteId.value,
    files: files.value.map(file => fileIdentity(file)),
    options: currentRouteOptions()
  })
}

const batchSettingsDirty = computed(() => ['SUCCESS', 'FAILED', 'CANCELLED'].includes(task.value?.status)
  && submittedBatchFingerprint.value
  && currentBatchFingerprint() !== submittedBatchFingerprint.value)
const dirtySettingsLabel = computed(() => {
  if (isPdfWatermarkRoute.value) return '水印设置'
  if (isPdfCompressRoute.value) return '压缩设置'
  if (isPdfSplitRoute.value) return '拆分页码'
  return '文件或顺序'
})

function readablePdfPreviewError(error) {
  return pdfPreviewError(error)
}

function resetPdfSourcePreviewState() {
  pdfSourcePreviewState.value = 'empty'
  pdfSourcePreviewError.value = ''
  pdfSourcePreviewBlocksSubmit.value = false
  pdfSourcePreviewCheckedFile.value = null
  pdfSourcePreviewPage.value = 1
  pdfSourcePreviewPageCount.value = 0
  pdfSourcePreviewFileIndex.value = 0
}

function handlePdfSourcePreviewState(snapshot) {
  if (snapshot.source !== pdfSourcePreviewFile.value) return
  pdfSourcePreviewState.value = snapshot.state
  pdfSourcePreviewError.value = snapshot.error || ''
  pdfSourcePreviewPage.value = snapshot.page || 1
  pdfSourcePreviewPageCount.value = snapshot.pageCount || 0
  if (['ready', 'error'].includes(snapshot.state)) {
    pdfSourcePreviewCheckedFile.value = snapshot.source
    pdfSourcePreviewBlocksSubmit.value = Boolean(snapshot.blocking)
  }
}

function selectPdfSourcePreviewFile(index) {
  if (index < 0 || index >= files.value.length || busy.value) return
  pdfSourcePreviewFileIndex.value = index
}

function pdfSourceStatus(state, page, pageCount, error) {
  if (state === 'error') return error || '当前文件无法预览'
  if (state !== 'ready') return '正在准备源文件预览'
  if (isPdfMergeRoute.value) return `合并顺序第 ${pdfSourcePreviewFileIndex.value + 1} 个文件 · 第 ${page} / ${pageCount} 页`
  if (isPdfSplitRoute.value) return pageMatchesRange(page, splitPages.value)
    ? `第 ${page} 页会包含在拆分结果中`
    : `第 ${page} 页不会包含在拆分结果中`
  if (isPdfCompressRoute.value) return `源文件第 ${page} / ${pageCount} 页；完成后将展示真实压缩结果`
  return `源文件第 ${page} / ${pageCount} 页`
}

function disposeWatermarkPreviewResources() {
  watermarkRenderGeneration++
  if (watermarkPdfRenderTask) {
    try { watermarkPdfRenderTask.cancel() } catch (_) { /* already finished */ }
    watermarkPdfRenderTask = null
  }
  if (watermarkPdfLoadingTask) {
    const loadingTask = watermarkPdfLoadingTask
    watermarkPdfLoadingTask = null
    Promise.resolve(loadingTask.destroy()).catch(() => {})
  }
  if (watermarkPdfDocument) {
    const document = watermarkPdfDocument
    watermarkPdfDocument = null
    Promise.resolve(document.destroy()).catch(() => {})
  }
}

function resetWatermarkPreview() {
  watermarkPreviewGeneration++
  disposeWatermarkPreviewResources()
  watermarkPreviewPage.value = 1
  watermarkPreviewPageCount.value = 0
  watermarkPreviewWidth.value = 0
  watermarkPreviewHeight.value = 0
  watermarkPreviewScale.value = 1
  watermarkPreviewError.value = ''
  watermarkPreviewBlocksSubmit.value = false
  watermarkPreviewCheckedFile.value = null
  watermarkPreviewState.value = 'empty'
  const canvas = watermarkPreviewCanvas.value
  if (canvas) {
    canvas.width = 0
    canvas.height = 0
    canvas.removeAttribute('style')
  }
}

async function loadWatermarkPreview(file) {
  const generation = ++watermarkPreviewGeneration
  disposeWatermarkPreviewResources()
  watermarkPreviewPage.value = 1
  watermarkPreviewPageCount.value = 0
  watermarkPreviewError.value = ''
  watermarkPreviewBlocksSubmit.value = false
  watermarkPreviewCheckedFile.value = null
  if (!file) {
    watermarkPreviewState.value = 'empty'
    return
  }

  watermarkPreviewState.value = 'loading'
  try {
    const data = new Uint8Array(await file.arrayBuffer())
    if (generation !== watermarkPreviewGeneration) return
    const { getDocument } = await loadPdfJs()
    if (generation !== watermarkPreviewGeneration) return
    const loadingTask = getDocument({ data, isEvalSupported: false })
    watermarkPdfLoadingTask = loadingTask
    const document = await loadingTask.promise
    if (generation !== watermarkPreviewGeneration) {
      await document.destroy().catch(() => {})
      return
    }
    watermarkPdfLoadingTask = null
    watermarkPdfDocument = document
    watermarkPreviewPageCount.value = document.numPages
    watermarkPreviewCheckedFile.value = file
    await renderWatermarkPreviewPage()
  } catch (error) {
    if (generation !== watermarkPreviewGeneration || error?.name === 'RenderingCancelledException') return
    watermarkPdfLoadingTask = null
    watermarkPreviewState.value = 'error'
    watermarkPreviewBlocksSubmit.value = blocksPdfSubmission(error)
    watermarkPreviewCheckedFile.value = file
    watermarkPreviewError.value = readablePdfPreviewError(error)
  }
}

async function renderWatermarkPreviewPage() {
  if (!watermarkPdfDocument) return
  const document = watermarkPdfDocument
  const generation = ++watermarkRenderGeneration
  if (watermarkPdfRenderTask) {
    try { watermarkPdfRenderTask.cancel() } catch (_) { /* already finished */ }
    watermarkPdfRenderTask = null
  }
  watermarkPreviewPage.value = Math.min(Math.max(1, watermarkPreviewPage.value), document.numPages)
  watermarkPreviewState.value = 'rendering'
  watermarkPreviewError.value = ''
  await nextTick()

  let page
  try {
    page = await document.getPage(watermarkPreviewPage.value)
    if (generation !== watermarkRenderGeneration || document !== watermarkPdfDocument) return
    const canvas = watermarkPreviewCanvas.value
    if (!canvas) return
    const baseViewport = page.getViewport({ scale: 1 })
    const stage = watermarkPreviewStage.value
    const availableWidth = Math.max(180, Math.min(840, (stage?.clientWidth || 560) - 32))
    const availableHeight = Math.max(240, Math.min(640, (stage?.clientHeight || 470) - 32))
    const scale = Math.min(1.8,
      availableWidth / baseViewport.width,
      availableHeight / baseViewport.height)
    if (!Number.isFinite(scale) || scale <= 0) throw new Error('Invalid PDF page viewport')
    const viewport = page.getViewport({ scale })
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 2)
    canvas.width = Math.max(1, Math.floor(viewport.width * pixelRatio))
    canvas.height = Math.max(1, Math.floor(viewport.height * pixelRatio))
    canvas.style.width = `${Math.floor(viewport.width)}px`
    canvas.style.height = `${Math.floor(viewport.height)}px`
    watermarkPreviewWidth.value = viewport.width
    watermarkPreviewHeight.value = viewport.height
    watermarkPreviewScale.value = scale
    const context = canvas.getContext('2d', { alpha: false })
    watermarkPdfRenderTask = page.render({
      canvasContext: context,
      viewport,
      transform: pixelRatio === 1 ? null : [pixelRatio, 0, 0, pixelRatio, 0, 0]
    })
    await watermarkPdfRenderTask.promise
    if (generation !== watermarkRenderGeneration || document !== watermarkPdfDocument) return
    watermarkPdfRenderTask = null
    watermarkPreviewState.value = 'ready'
  } catch (error) {
    if (generation !== watermarkRenderGeneration || error?.name === 'RenderingCancelledException') return
    watermarkPdfRenderTask = null
    watermarkPreviewState.value = 'error'
    watermarkPreviewError.value = readablePdfPreviewError(error)
  } finally {
    page?.cleanup()
  }
}

function changeWatermarkPreviewPage(offset) {
  const nextPage = watermarkPreviewPage.value + offset
  if (!watermarkPdfDocument || nextPage < 1 || nextPage > watermarkPreviewPageCount.value) return
  watermarkPreviewPage.value = nextPage
  void renderWatermarkPreviewPage()
}

function scheduleWatermarkPreviewResize() {
  if (!watermarkPdfDocument || !isPdfWatermarkRoute.value) return
  clearTimeout(watermarkResizeTimer)
  watermarkResizeTimer = setTimeout(() => void renderWatermarkPreviewPage(), 120)
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

function routePickerMeta(route) {
  const values = [strategyLabel(route)].filter(Boolean)
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
  if (view === 'history') void loadTaskHistory()
  await nextTick()
  if (appScrollRef.value) appScrollRef.value.scrollTop = 0
}

async function startNewConversion() {
  if (busy.value) {
    await navigate('convert')
    message.value = task.value ? '请先取消当前任务，再开始新的转换' : '请先取消正在上传的文件'
    return
  }
  startNewBatch()
  await navigate('convert')
  await toggleRoutePicker()
}

async function showAllRoutes() {
  await navigate('convert')
  if (busy.value) return
  pickerSource.value = 'popular'
  routeSearch.value = ''
  await toggleRoutePicker()
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

function cacheRecentTasks() {
  try { localStorage.setItem('format-converter-recent-tasks', JSON.stringify(recentTasks.value.slice(0, 50))) } catch (_) { /* session still works */ }
}

function routeForSnapshot(snapshot) {
  return conversions.value.find(route => route.sourceFormat === snapshot?.sourceFormat
    && route.targetFormat === snapshot?.targetFormat)
}

function historyEntry(snapshot, existing = null) {
  const route = routeForSnapshot(snapshot)
  const resultCount = Array.isArray(snapshot?.files) ? snapshot.files.length : 0
  return {
    taskId: snapshot.taskId,
    status: snapshot.status,
    stage: snapshot.stage || null,
    progress: Number.isFinite(snapshot.progress) ? snapshot.progress : 0,
    sourceFormat: snapshot.sourceFormat || existing?.sourceFormat || route?.sourceFormat || '',
    targetFormat: snapshot.targetFormat || existing?.targetFormat || route?.targetFormat || '',
    sourceLabel: route?.sourceLabel || existing?.sourceLabel || snapshot.sourceFormat?.toUpperCase() || '文件',
    targetLabel: route?.targetLabel || existing?.targetLabel || snapshot.targetFormat?.toUpperCase() || '输出文件',
    fileCount: resultCount || existing?.fileCount || (snapshot.taskId === task.value?.taskId ? files.value.length : 0),
    updatedAt: snapshot.updatedAt || existing?.updatedAt || new Date().toISOString(),
    expiresAt: snapshot.expiresAt || existing?.expiresAt || null,
    downloadReady: Boolean(snapshot.downloadReady),
    downloadName: snapshot.downloadName || existing?.downloadName || '',
    errorMessage: snapshot.errorMessage || existing?.errorMessage || '',
    warnings: Array.isArray(snapshot.warnings) ? snapshot.warnings : [],
    files: Array.isArray(snapshot.files) ? snapshot.files : []
  }
}

function loadRecentTasks() {
  try {
    const stored = JSON.parse(localStorage.getItem('format-converter-recent-tasks') || '[]')
    if (Array.isArray(stored)) {
      recentTasks.value = stored.filter(item => item
        && typeof item.taskId === 'string' && item.taskId.length > 0
        && ['WAITING', 'CONVERTING', 'SUCCESS', 'FAILED', 'CANCELLED'].includes(item.status))
        .map(item => historyEntry(item)).slice(0, 50)
    }
  } catch (_) { recentTasks.value = [] }
}

function recordRecentTask(snapshot) {
  if (!snapshot?.taskId || !['WAITING', 'CONVERTING', 'SUCCESS', 'FAILED', 'CANCELLED'].includes(snapshot.status)) return
  const existing = recentTasks.value.find(item => item.taskId === snapshot.taskId)
  const entry = historyEntry(snapshot, existing)
  recentTasks.value = [entry, ...recentTasks.value.filter(item => item.taskId !== entry.taskId)]
    .sort((left, right) => new Date(right.updatedAt || 0) - new Date(left.updatedAt || 0)).slice(0, 50)
  cacheRecentTasks()
}

async function loadTaskHistory({ quiet = false } = {}) {
  if (!quiet) historyLoading.value = true
  try {
    const response = await fetch('/api/tasks?limit=50', { cache: 'no-store' })
    if (!response.ok) throw responseError(response, `任务记录加载失败（${response.status}）`)
    const snapshots = await response.json()
    if (!Array.isArray(snapshots)) throw new Error('任务记录格式无效')
    const cached = new Map(recentTasks.value.map(item => [item.taskId, item]))
    recentTasks.value = snapshots.map(snapshot => historyEntry(snapshot, cached.get(snapshot.taskId)))
    cacheRecentTasks()
    if (!quiet) historyMessage.value = ''
    clearTimeout(historyTimer)
    if (recentTasks.value.some(item => ['WAITING', 'CONVERTING'].includes(item.status))) {
      historyTimer = setTimeout(() => loadTaskHistory({ quiet: true }), 1800)
    }
  } catch (error) {
    if (!quiet) historyMessage.value = error.message || '任务记录加载失败'
  } finally {
    historyLoading.value = false
  }
}

async function clearRecentTasks() {
  const terminal = recentTasks.value.filter(item => ['SUCCESS', 'FAILED', 'CANCELLED'].includes(item.status))
  if (!terminal.length) {
    historyMessage.value = '当前没有可清理的已结束任务'
    return
  }
  if (!window.confirm(`确定删除 ${terminal.length} 条已结束任务及其本地结果文件吗？此操作不可撤销。`)) return
  historyLoading.value = true
  historyMessage.value = ''
  const deleted = []
  for (const item of terminal) {
    try {
      const response = await fetch(`/api/tasks/${item.taskId}`, { method: 'DELETE' })
      if (response.ok || response.status === 404) deleted.push(item.taskId)
    } catch (_) { /* retain failed entries */ }
  }
  recentTasks.value = recentTasks.value.filter(item => !deleted.includes(item.taskId))
  cacheRecentTasks()
  if (task.value && deleted.includes(task.value.taskId)) startNewBatch()
  historyMessage.value = deleted.length === terminal.length
    ? `已删除 ${deleted.length} 条任务及本地结果`
    : `已删除 ${deleted.length} 条，另有 ${terminal.length - deleted.length} 条未能删除`
  historyLoading.value = false
}

async function deleteHistoryTask(item) {
  if (!window.confirm(`确定删除任务 ${item.taskId.slice(0, 8)} 及其本地结果吗？`)) return
  historyMessage.value = ''
  try {
    const response = await fetch(`/api/tasks/${item.taskId}`, { method: 'DELETE' })
    if (!response.ok && response.status !== 404) throw responseError(response, `删除失败（${response.status}）`)
    recentTasks.value = recentTasks.value.filter(candidate => candidate.taskId !== item.taskId)
    cacheRecentTasks()
    if (task.value?.taskId === item.taskId) startNewBatch()
    historyMessage.value = '任务及本地结果已删除'
  } catch (error) {
    historyMessage.value = error.message || '删除任务失败'
  }
}

function applyTaskSnapshot(snapshot, changeView = true) {
  const route = routeForSnapshot(snapshot)
  if (route) selectedRouteId.value = route.id
  pollGeneration++
  clearTimeout(pollTimer)
  files.value = []
  submittedBatchFingerprint.value = ''
  resetPdfSourcePreviewState()
  task.value = snapshot
  busy.value = ['WAITING', 'CONVERTING'].includes(snapshot.status)
  message.value = ''
  recordRecentTask(snapshot)
  if (changeView) navigate('convert')
  if (busy.value) poll()
}

async function openHistoryTask(item) {
  historyMessage.value = ''
  try {
    const response = await fetch(`/api/tasks/${item.taskId}`, { cache: 'no-store' })
    if (!response.ok) throw responseError(response, `任务详情加载失败（${response.status}）`)
    applyTaskSnapshot(await response.json())
  } catch (error) {
    historyMessage.value = error.message || '任务详情加载失败'
    if (error.status === 404) {
      recentTasks.value = recentTasks.value.filter(candidate => candidate.taskId !== item.taskId)
      cacheRecentTasks()
    }
  }
}

async function retryHistoryTask(item) {
  historyMessage.value = ''
  try {
    const response = await fetch(`/api/tasks/${item.taskId}/retry`, { method: 'POST' })
    let body = {}
    try { body = await response.json() } catch (_) { /* ignore */ }
    if (!response.ok) throw responseError(response, body.message || `重试失败（${response.status}）`)
    applyTaskSnapshot(body)
  } catch (error) {
    historyMessage.value = error.message || '重试任务失败'
  }
}

function loadPreferences() {
  try {
    const stored = JSON.parse(localStorage.getItem('format-converter-preferences') || '{}')
    autoDownload.value = stored.autoDownload === true
    if (['lossless', 'balanced', 'strong'].includes(stored.compressionMode)) compressionMode.value = stored.compressionMode
  } catch (_) { /* use safe defaults */ }
}

function savePreferences() {
  try {
    localStorage.setItem('format-converter-preferences', JSON.stringify({
      autoDownload: autoDownload.value,
      compressionMode: compressionMode.value
    }))
    preferenceMessage.value = '偏好已保存到当前设备'
  } catch (_) {
    preferenceMessage.value = '当前环境无法保存偏好'
  }
}

function onDocumentClick(event) {
  if (!routePickerRef.value?.contains(event.target)) closeRoutePicker()
}

function onRoutePickerKeydown(event) {
  if (event.key === 'Escape') {
    closeRoutePicker(true)
    return
  }
  if (event.key === 'Enter' && document.activeElement?.classList?.contains('route-search')) {
    const firstAvailable = pickerRoutes.value.find(route => route.status === 'available')
    if (firstAvailable) {
      event.preventDefault()
      selectRoute(firstAvailable)
    }
    return
  }
  if (event.key === 'Enter' && document.activeElement?.classList?.contains('route-option')) {
    const route = conversions.value.find(candidate => candidate.id === document.activeElement.dataset.routeId)
    if (route?.status === 'available') {
      event.preventDefault()
      selectRoute(route)
    }
    return
  }
  if (!['ArrowDown', 'ArrowUp'].includes(event.key)) return
  const options = Array.from(routeMenuRef.value?.querySelectorAll('.route-option:not(:disabled)') || [])
  if (!options.length) return
  event.preventDefault()
  const current = options.indexOf(document.activeElement)
  const next = event.key === 'ArrowDown'
    ? (current < 0 ? 0 : (current + 1) % options.length)
    : (current < 0 ? options.length - 1 : (current - 1 + options.length) % options.length)
  options[next].focus()
}

async function loadCapabilities() {
  try {
    const response = await fetch('/api/tasks/capabilities', { cache: 'no-store' })
    if (!response.ok) {
      capabilityMessage.value = response.status === 401
        ? '转换能力接口需要访问令牌，请从桌面应用或正确配置的本地入口打开。'
        : `转换能力加载失败（${response.status}），已保留上次成功加载的路线。`
      return
    }
    const routes = await response.json()
    if (Array.isArray(routes) && routes.length) {
      const previousRoute = selectedRoute.value
      const hasActiveBatch = busy.value || files.value.length > 0 || Boolean(task.value)
      const currentStillListed = routes.some(route => route.id === selectedRouteId.value)
      const nextRoutes = hasActiveBatch && previousRoute && !currentStillListed
        ? [...routes, {
            ...previousRoute,
            status: 'unavailable',
            limitations: [...(previousRoute.limitations || []), '当前转换服务刷新后不再提供这条路线']
          }]
        : routes
      capabilityMessage.value = ''
      conversions.value = nextRoutes
      if (!hasActiveBatch && !nextRoutes.some(route => route.id === selectedRouteId.value)) {
        selectedRouteId.value = nextRoutes[0].id
      }
      if (!hasActiveBatch && selectedRoute.value?.status !== 'available' && availableRoutes.value.length) {
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
  if (uploadRequest) uploadRequest.abort()
  files.value = []
  task.value = null
  uploadProgress.value = 0
  busy.value = false
  message.value = ''
  submittedBatchFingerprint.value = ''
  resetPdfSourcePreviewState()
  clearResultPreview()
}

function accept(selected) {
  const wasEmpty = files.value.length === 0
  const extensions = acceptExtensions.value.map(extension => extension.toLowerCase())
  const selectedFiles = Array.from(selected || [])
  const matching = selectedFiles.filter(file => extensions.some(extension => file.name.toLowerCase().endsWith(extension)))
  const incoming = matching.filter(file => file.size <= limits.value.maxFileSize)
  if (incoming.length && task.value && !busy.value) startNewBatch()
  let capacityRejected = 0
  let quotaRejected = 0
  let selectedBytes = files.value.reduce((total, file) => total + file.size, 0)
  const replacingSingleFile = isSinglePdfTool.value && incoming.length > 0
  if (replacingSingleFile) {
    files.value.splice(0, files.value.length)
    selectedBytes = 0
    const replacement = incoming[0]
    if (replacement.size <= limits.value.maxTaskUploadBytes) {
      files.value.push(replacement)
      selectedBytes = replacement.size
    } else {
      quotaRejected++
    }
    capacityRejected += Math.max(0, incoming.length - 1)
  } else {
    for (const file of incoming) {
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
    }
  }
  const notices = []
  if (matching.length !== selectedFiles.length) notices.push(`非 ${acceptExtensions.value.join(' / ').toUpperCase()} 文件`)
  if (incoming.length !== matching.length) notices.push(`超过 ${formatBytes(limits.value.maxFileSize)} 的文件`)
  if (capacityRejected) notices.push(`超出 ${routeFileLimit.value} 个文件上限的部分`)
  if (quotaRejected) notices.push(`超出 ${formatBytes(limits.value.maxTaskUploadBytes)} 总量上限的部分`)
  message.value = notices.length ? `已忽略${notices.join('、')}` : ''
  if ((wasEmpty || replacingSingleFile) && files.value.length && (isPdfInputRoute.value || isImageToPdfRoute.value)) {
    nextTick(() => (isPdfWatermarkRoute.value ? watermarkPreviewRef.value : pdfSourcePreviewRef.value)?.scrollIntoView({
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
      block: 'center'
    }))
  }
}

function drop(event) {
  dragging.value = false
  accept(event.dataTransfer.files)
}

function remove(index) {
  if (busy.value) return
  const selectedPreviewFile = pdfSourcePreviewFile.value
  files.value.splice(index, 1)
  if (selectedPreviewFile && files.value.includes(selectedPreviewFile)) {
    pdfSourcePreviewFileIndex.value = files.value.indexOf(selectedPreviewFile)
  } else {
    pdfSourcePreviewFileIndex.value = Math.min(pdfSourcePreviewFileIndex.value, Math.max(0, files.value.length - 1))
  }
}
function moveFile(index, offset) {
  if (busy.value) return
  const target = index + offset
  if (target < 0 || target >= files.value.length) return
  const selectedPreviewFile = pdfSourcePreviewFile.value
  const [file] = files.value.splice(index, 1)
  files.value.splice(target, 0, file)
  if (selectedPreviewFile) pdfSourcePreviewFileIndex.value = files.value.indexOf(selectedPreviewFile)
}
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
  return ({ WAITING: '等待中', CONVERTING: '转换中', SUCCESS: '已完成', FAILED: '失败', CANCELLED: '已取消' })[status] || status
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
  submittedBatchFingerprint.value = currentBatchFingerprint()
  const data = new FormData()
  files.value.forEach(file => data.append('files', file))
  data.append('targetFormat', selectedRoute.value.targetFormat)
  if (isPdfCompressRoute.value) data.append('compressionMode', compressionMode.value)
  if (isPdfSplitRoute.value) data.append('splitPages', splitPages.value)
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
    recordRecentTask(created)
    poll()
  } catch (error) {
    message.value = error.message
    busy.value = false
  }
}

async function regenerateCurrentBatch() {
  if (!batchSettingsDirty.value || !canSubmit.value) return
  message.value = `正在按当前${dirtySettingsLabel.value}重新生成，之前的结果仍可在任务记录中下载`
  await submit()
}

function upload(data) {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest()
    uploadRequest = request
    const release = () => { if (uploadRequest === request) uploadRequest = null }
    request.open('POST', '/api/tasks')
    request.upload.onprogress = event => { if (event.lengthComputable) uploadProgress.value = Math.round(event.loaded / event.total * 100) }
    request.onload = () => {
      release()
      let body = {}
      try { body = JSON.parse(request.responseText) } catch (_) { /* ignore */ }
      if (request.status >= 200 && request.status < 300) resolve(body)
      else if (request.status === 401) reject(new Error('任务 API 需要有效的访问令牌'))
      else reject(new Error(body.message || `上传失败（${request.status}）`))
    }
    request.onerror = () => { release(); reject(new Error('无法连接转换服务')) }
    request.onabort = () => { release(); reject(new Error('上传已取消')) }
    request.send(data)
  })
}

function cancelUpload() {
  if (!uploadRequest) return
  uploadRequest.abort()
  uploadRequest = null
  busy.value = false
  uploadProgress.value = 0
  message.value = '上传已取消，已选择的文件仍保留在列表中'
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
    recordRecentTask(snapshot)
    if (task.value.status === 'WAITING' || task.value.status === 'CONVERTING') pollTimer = setTimeout(poll, 800)
    else {
      busy.value = false
      if (task.value.status === 'SUCCESS' && autoDownload.value && !autoDownloadedTaskIds.has(task.value.taskId)) {
        autoDownloadedTaskIds.add(task.value.taskId)
        void downloadTask(task.value, { silent: true })
      }
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

function clearResultPreview() {
  resultPreviewGeneration++
  if (resultPreviewImageUrl.value) URL.revokeObjectURL(resultPreviewImageUrl.value)
  resultPreviewBlob.value = null
  resultPreviewKind.value = ''
  resultPreviewText.value = ''
  resultPreviewImageUrl.value = ''
  resultPreviewState.value = 'empty'
  resultPreviewError.value = ''
}

async function loadResultPreview(snapshot) {
  const generation = ++resultPreviewGeneration
  if (resultPreviewImageUrl.value) URL.revokeObjectURL(resultPreviewImageUrl.value)
  resultPreviewBlob.value = null
  resultPreviewKind.value = ''
  resultPreviewText.value = ''
  resultPreviewImageUrl.value = ''
  resultPreviewError.value = ''
  const extension = (snapshot?.downloadName || '').toLowerCase().match(/\.([a-z0-9]+)$/)?.[1] || ''
  if (!snapshot || snapshot.status !== 'SUCCESS' || !snapshot.downloadReady || !['pdf', 'png', 'jpg', 'jpeg', 'txt', 'csv'].includes(extension)) {
    resultPreviewState.value = 'empty'
    return
  }

  resultPreviewState.value = 'loading'
  try {
    const response = await fetch(`/api/tasks/${snapshot.taskId}/download`, { cache: 'no-store' })
    if (!response.ok) throw responseError(response, `结果预览加载失败（${response.status}）`)
    const contentType = (response.headers.get('content-type') || '').split(';')[0].trim().toLowerCase()
    const declaredSize = Number(response.headers.get('content-length') || 0)
    const previewLimit = extension === 'pdf' ? 32 * 1024 * 1024
      : (['png', 'jpg', 'jpeg'].includes(extension) ? 24 * 1024 * 1024 : 2 * 1024 * 1024)
    if (declaredSize > previewLimit) {
      resultPreviewState.value = 'too-large'
      resultPreviewError.value = `结果超过 ${formatBytes(previewLimit)}，为避免占用过多内存，请直接下载查看`
      return
    }
    if (extension === 'pdf') {
      if (contentType !== 'application/pdf') throw new Error('当前结果不是可预览的 PDF')
      const blob = await response.blob()
      if (generation !== resultPreviewGeneration) return
      if (blob.size > previewLimit) throw new Error(`结果超过 ${formatBytes(previewLimit)}，请直接下载查看`)
      resultPreviewBlob.value = blob
      resultPreviewKind.value = 'pdf'
    } else if (['png', 'jpg', 'jpeg'].includes(extension)) {
      if (!contentType.startsWith('image/')) throw new Error('当前结果不是可预览的图片')
      const blob = await response.blob()
      if (generation !== resultPreviewGeneration) return
      if (blob.size > previewLimit) throw new Error(`结果超过 ${formatBytes(previewLimit)}，请直接下载查看`)
      resultPreviewImageUrl.value = URL.createObjectURL(blob)
      resultPreviewKind.value = 'image'
    } else {
      if (!(contentType.startsWith('text/') || ['application/csv', 'application/vnd.ms-excel'].includes(contentType))) {
        throw new Error('当前结果不是可预览的文本')
      }
      const text = await response.text()
      if (generation !== resultPreviewGeneration) return
      const displayLimit = 200_000
      resultPreviewText.value = text.length > displayLimit
        ? `${text.slice(0, displayLimit)}\n\n… 预览已截断，请下载查看完整文件 …`
        : text
      resultPreviewKind.value = extension === 'csv' ? 'csv' : 'text'
    }
    resultPreviewState.value = 'ready'
  } catch (error) {
    if (generation !== resultPreviewGeneration) return
    resultPreviewState.value = 'error'
    resultPreviewError.value = error.message || '结果预览加载失败，可直接下载查看'
  }
}

async function downloadTask(item, { silent = false } = {}) {
  if (!item?.taskId || downloadingTaskId.value) return
  downloadingTaskId.value = item.taskId
  try {
    const downloadUrl = `/api/tasks/${item.taskId}/download`
    const response = await fetch(downloadUrl, { method: 'HEAD', cache: 'no-store' })
    if (!response.ok) throw responseError(response, `下载失败（${response.status}）`)
    const anchor = document.createElement('a')
    anchor.href = downloadUrl
    anchor.download = item.downloadName || 'converted-file'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    if (!silent) {
      const text = `已开始下载 ${anchor.download}`
      if (activeView.value === 'history') historyMessage.value = text
      else message.value = text
    }
  } catch (error) {
    if (activeView.value === 'history') historyMessage.value = error.message || '下载失败'
    else message.value = error.message || '下载失败'
  } finally {
    downloadingTaskId.value = ''
  }
}

async function download() { await downloadTask(task.value) }

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
    recordRecentTask(task.value)
    poll()
  } catch (error) {
    message.value = error.message
    busy.value = false
  }
}

async function reset() {
  startNewBatch()
}

async function refreshRuntime() {
  diagnosticMessage.value = '正在刷新运行状态…'
  await Promise.all([loadLimits(), loadCapabilities(), loadTaskHistory({ quiet: true })])
  diagnosticMessage.value = diagnosticsFailed.value ? '运行状态刷新失败' : '运行状态已刷新'
}

async function copyDiagnostics() {
  diagnosticMessage.value = ''
  try {
    const response = await fetch('/api/diagnostics', { cache: 'no-store' })
    if (!response.ok) throw new Error(`诊断信息获取失败（${response.status}）`)
    const diagnostics = await response.json()
    const text = JSON.stringify(diagnostics, null, 2)
    if (window.formatConverterDesktop?.copyText) {
      await window.formatConverterDesktop.copyText(text)
    } else if (navigator.clipboard?.writeText) {
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

watch(watermarkPreviewFile, file => void loadWatermarkPreview(file), { flush: 'post' })
watch(pdfSourcePreviewFile, file => {
  pdfSourcePreviewState.value = file ? 'loading' : 'empty'
  pdfSourcePreviewError.value = ''
  pdfSourcePreviewBlocksSubmit.value = false
  pdfSourcePreviewPage.value = 1
  pdfSourcePreviewPageCount.value = 0
  if (!file) pdfSourcePreviewCheckedFile.value = null
}, { flush: 'sync' })
watch(() => [task.value?.taskId, task.value?.status, task.value?.downloadReady, task.value?.downloadName], () => {
  void loadResultPreview(task.value)
}, { immediate: true })

onMounted(() => {
  desktopRuntime.value = Boolean(window.formatConverterDesktop)
  loadPreferences()
  loadRecentTasks()
  loadCapabilities().finally(() => loadTaskHistory())
  loadLimits()
  healthTimer = setInterval(loadLimits, 30000)
  document.addEventListener('click', onDocumentClick)
  window.addEventListener('resize', positionRouteMenu)
  window.addEventListener('resize', scheduleWatermarkPreviewResize)
})
onBeforeUnmount(() => {
  clearTimeout(pollTimer)
  clearTimeout(historyTimer)
  clearInterval(healthTimer)
  clearTimeout(watermarkResizeTimer)
  if (uploadRequest) uploadRequest.abort()
  resetWatermarkPreview()
  clearResultPreview()
  document.removeEventListener('click', onDocumentClick)
  window.removeEventListener('resize', positionRouteMenu)
  window.removeEventListener('resize', scheduleWatermarkPreviewResize)
})
</script>

<template>
  <div class="desktop-app">
    <aside class="app-sidebar">
      <button class="app-brand" type="button" aria-label="返回概览" @click="navigate('overview')">
        <span class="app-logo" aria-hidden="true"><b>F</b><i></i></span>
        <span><strong>Fuyue Convert</strong><small>{{ desktopRuntime ? 'DESKTOP APP' : 'LOCAL WEB' }}</small></span>
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
        <span><strong>{{ serviceHealthy ? '转换服务在线' : '正在连接服务' }}</strong><small>不转交第三方云服务</small></span>
      </div>
    </aside>

    <main class="app-shell">
      <header class="app-header">
        <div class="page-heading"><span>{{ viewTitle[0] }}</span><small>{{ viewTitle[1] }}</small></div>
        <div class="header-actions">
          <span class="local-chip"><i></i> {{ desktopRuntime ? 'LOCAL DESKTOP' : 'CURRENT SERVICE' }}</span>
          <button type="button" class="header-cta" @click.stop="startNewConversion"><b>＋</b> 新建转换</button>
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
              <p><span></span> FORMAT WORKSPACE / {{ diagnostics?.version || '0.1.4' }}</p>
              <h1>欢迎回来，开始处理文档。</h1>
              <small>{{ desktopRuntime ? '转换、整理和导出都在本机完成。' : '文件只发送到当前转换服务，不会转交第三方云端。' }}你可以从常用路线开始，也可以进入完整工作台。</small>
              <button type="button" @click.stop="startNewConversion">开始新任务 <span>→</span></button>
            </div>
            <div class="banner-visual" aria-hidden="true">
              <span class="doc-card one">PDF</span><span class="doc-card two">DOCX</span><span class="doc-card three">OFD</span>
              <i class="orbit one"></i><i class="orbit two"></i>
            </div>
          </article>

          <div class="metric-grid" aria-label="运行概览">
            <article><span class="metric-icon blue">↗</span><div><small>可用路线</small><strong>{{ availableRoutes.length }}</strong><em>覆盖 {{ availableSourceCount }} 种可用输入格式</em></div></article>
            <article><span class="metric-icon violet">✓</span><div><small>稳定路线</small><strong>{{ stableRoutes.length }}</strong><em>{{ betaRoutes.length }} 条 Beta · {{ experimentalRoutes.length }} 条实验路线</em></div></article>
            <article><span class="metric-icon cyan">▣</span><div><small>已完成任务</small><strong>{{ successfulTasks }}</strong><em>结果在到期前可重新下载</em></div></article>
            <article><span class="metric-icon green">●</span><div><small>服务状态</small><strong>{{ serviceHealthy ? '正常' : '连接中' }}</strong><em>独立 Worker {{ diagnostics?.limits?.workerEnabled ? '已启用' : '检测中' }}</em></div></article>
          </div>

          <div class="dashboard-grid">
            <section class="dash-panel quick-panel">
              <div class="panel-title"><div><small>QUICK ACTIONS</small><h2>常用转换</h2></div><button type="button" @click.stop="showAllRoutes">查看全部</button></div>
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
                <button type="button" class="recent-open" @click="openHistoryTask(item)">查看</button>
              </div>
            </div>
            <div v-else class="empty-state compact"><span>⌁</span><p><strong>还没有转换记录</strong><small>完成第一项任务后会在这里显示。</small></p><button type="button" @click.stop="startNewConversion">开始转换</button></div>
          </section>
        </section>

        <section v-show="activeView === 'pdf'" class="content-page pdf-page">
          <div class="section-intro"><span>PDF LAB</span><h2>一组专注、可靠的 PDF 工具。</h2><p>{{ desktopRuntime ? '所有修改在本机完成。' : '文件仅由当前转换服务处理。' }}选择一个工具即可进入工作台。</p></div>
          <div class="tool-card-grid">
            <button v-for="(route, index) in pdfToolRoutes" :key="route.id" type="button" :disabled="route.status !== 'available'" @click="openRoute(route)">
              <span class="tool-number">0{{ index + 1 }}</span><span class="tool-symbol">{{ ['↘', '⊕', '✂', 'W'][index] }}</span>
              <div><small>{{ routeBadge(route) }} · {{ strategyLabel(route) }}</small><h3>{{ route.targetLabel }}</h3><p>{{ route.description }}</p></div><i>进入工具 →</i>
            </button>
          </div>
          <aside class="privacy-banner"><span>◆</span><div><strong>保护原始文档</strong><small>压缩与水印会拒绝修改数字签名 PDF；其他工具会明确展示适用边界。</small></div></aside>
        </section>

        <section v-show="activeView === 'history'" class="content-page history-page">
          <div class="content-toolbar">
            <div><span>RECOVERABLE ACTIVITY</span><h2>最近任务</h2><p>任务和结果由当前转换服务保留至到期时间，可查看详情、重新下载或重试。</p></div>
            <div class="toolbar-actions"><button type="button" :disabled="historyLoading" @click="loadTaskHistory">{{ historyLoading ? '刷新中…' : '刷新' }}</button><button v-if="recentTasks.length" type="button" class="danger" @click="clearRecentTasks">清理已结束任务</button></div>
          </div>
          <p v-if="historyMessage" class="history-message" role="status">{{ historyMessage }}</p>
          <div v-if="recentTasks.length" class="recent-list full">
            <div v-for="item in recentTasks" :key="item.taskId">
              <span class="history-icon">{{ item.sourceLabel?.slice(0, 3) }}</span>
              <p><strong>{{ item.sourceLabel }} → {{ item.targetLabel }}</strong><small>任务 {{ item.taskId.slice(0, 8) }} · {{ item.fileCount || '—' }} 个文件<span v-if="item.errorMessage"> · {{ item.errorMessage }}</span></small></p>
              <time>{{ formatTaskTime(item.updatedAt) }}</time><em :class="item.status.toLowerCase()">{{ recentStatusLabel(item.status) }}</em>
              <span class="history-actions">
                <button type="button" @click="openHistoryTask(item)">查看</button>
                <button v-if="item.downloadReady" type="button" :disabled="downloadingTaskId === item.taskId" @click="downloadTask(item)">{{ downloadingTaskId === item.taskId ? '下载中' : '下载' }}</button>
                <button v-if="['FAILED', 'CANCELLED'].includes(item.status)" type="button" @click="retryHistoryTask(item)">重试</button>
                <button v-if="['SUCCESS', 'FAILED', 'CANCELLED'].includes(item.status)" type="button" class="danger" @click="deleteHistoryTask(item)">删除</button>
              </span>
            </div>
          </div>
          <div v-else-if="!historyLoading" class="empty-state large"><span>⌁</span><p><strong>暂无任务记录</strong><small>开始转换后，可在这里恢复任务和下载结果。</small></p><button type="button" @click.stop="startNewConversion">创建任务</button></div>
        </section>

        <section v-show="activeView === 'settings'" class="content-page settings-page">
          <div class="settings-grid">
            <section class="settings-card"><div class="settings-head"><span>01</span><div><strong>应用信息</strong><small>当前运行版本与平台</small></div></div><dl><div><dt>版本</dt><dd>{{ diagnostics?.version || '0.1.4' }}</dd></div><div><dt>系统</dt><dd>{{ diagnostics?.runtime?.os || '检测中' }} · {{ diagnostics?.runtime?.arch || '' }}</dd></div><div><dt>处理器</dt><dd>{{ diagnostics?.runtime?.availableProcessors || '—' }} 核心</dd></div></dl></section>
            <section class="settings-card"><div class="settings-head"><span>02</span><div><strong>转换引擎</strong><small>本机依赖可用状态</small></div></div><dl><div><dt>Office</dt><dd :class="{ good: diagnostics?.office?.available }">{{ diagnostics?.office?.message || '检测中' }}</dd></div><div><dt>OCR</dt><dd :class="{ good: diagnostics?.ocr?.available }">{{ diagnostics?.ocr?.message || '检测中' }}</dd></div><div><dt>Worker</dt><dd :class="{ good: diagnostics?.limits?.workerEnabled }">{{ diagnostics?.limits?.workerEnabled ? '独立进程已启用' : '未启用' }}</dd></div></dl></section>
            <section class="settings-card"><div class="settings-head"><span>03</span><div><strong>资源限制</strong><small>保护本机运行稳定</small></div></div><dl><div><dt>单文件</dt><dd>{{ formatBytes(limits.maxFileSize) }}</dd></div><div><dt>单任务</dt><dd>{{ limits.maxFilesPerTask }} 个文件</dd></div><div><dt>并发任务</dt><dd>{{ diagnostics?.limits?.concurrency || '—' }}</dd></div></dl></section>
            <section class="settings-card preference-card"><div class="settings-head"><span>04</span><div><strong>使用偏好</strong><small>保存在当前设备</small></div></div><label class="setting-toggle"><span><strong>完成后自动下载</strong><small>转换成功后立即保存结果</small></span><input v-model="autoDownload" type="checkbox" @change="savePreferences" /><i></i></label><label class="setting-select"><span>默认 PDF 压缩等级</span><select v-model="compressionMode" @change="savePreferences"><option value="lossless">无损优化</option><option value="balanced">均衡压缩</option><option value="strong">强力压缩</option></select></label><small v-if="preferenceMessage" class="setting-message">{{ preferenceMessage }}</small></section>
            <section class="settings-card action-card"><div class="settings-head"><span>05</span><div><strong>诊断信息</strong><small>刷新或复制脱敏运行状态</small></div></div><p>遇到转换问题时，可刷新引擎状态，或复制诊断信息随问题反馈提交。</p><div class="settings-actions"><button type="button" @click="refreshRuntime">刷新状态</button><button type="button" @click="copyDiagnostics">复制诊断</button></div><small v-if="diagnosticMessage">{{ diagnosticMessage }}</small></section>
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
                  placeholder="搜索 PDF、压缩、Beta、实验、不可用..."
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
                      <span>{{ source.id === 'popular' ? '★' : (source.id === 'beta' ? 'β' : '◆') }}</span>
                      <strong>{{ source.label }}</strong>
                      <small :title="`${source.availableCount}/${source.count} 条可用`">{{ source.availableCount === source.count ? source.count : `${source.availableCount}/${source.count}` }}</small>
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
                      <small :title="`${source.availableCount}/${source.count} 条可用`">{{ source.availableCount === source.count ? source.count : `${source.availableCount}/${source.count}` }}</small>
                    </button>
                  </div>
                </aside>
                <section class="target-panel">
                  <div class="target-heading">
                    <div><strong>{{ pickerTitle }}</strong><small>{{ pickerHint }}</small></div>
                    <button v-if="routeSearch" type="button" @click="routeSearch = ''">清除搜索</button>
                  </div>
                  <div class="route-list" role="listbox" aria-labelledby="route-label">
                    <p v-if="!pickerRoutes.length" class="route-empty"><b>没有找到匹配路线</b><span>试试搜索格式名或“合并”“压缩”等功能</span></p>
                    <button
                      v-for="route in pickerRoutes"
                      :key="route.id"
                      type="button"
                      class="route-option"
                      :data-route-id="route.id"
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
                        <em><span v-if="routePickerMeta(route)">{{ routePickerMeta(route) }}</span><span class="route-mobile-quality">{{ routePickerMeta(route) ? ' · ' : '' }}{{ routeBadge(route) }}</span></em>
                      </span>
                      <span class="route-badge" :class="route.status === 'available' ? route.qualityLevel : route.status">{{ routeBadge(route) }}</span>
                    </button>
                  </div>
                </section>
              </div>
            </div>
          </div>
          <div class="route-detail">
            <span class="route-description">{{ selectedRoute.description }}{{ routeAvailability(selectedRoute) }}</span>
            <span v-if="routeMeta(selectedRoute)" class="route-meta">{{ routeMeta(selectedRoute) }}</span>
            <span v-if="routeRuntimeWarning" class="field-warning route-runtime-warning" role="status">{{ routeRuntimeWarning }}</span>
            <details v-if="selectedRoute.limitations?.length" class="route-limitations" :open="selectedRoute.qualityLevel === 'beta'">
              <summary>{{ selectedRoute.qualityLevel === 'beta' ? 'Beta 适用边界' : '查看适用边界' }} · {{ selectedRoute.limitations.length }} 项</summary>
              <ul><li v-for="item in selectedRoute.limitations" :key="item">{{ item }}</li></ul>
            </details>
          </div>
        </div>
      </div>

      <div v-if="hasToolOptions" class="tool-options-section">
        <div class="field-heading">
          <span class="field-number">02</span>
          <div><label>{{ isPdfCompressRoute ? '压缩设置' : (isPdfSplitRoute ? '拆分范围' : '水印设置') }}</label><small>根据使用场景调整处理参数</small></div>
        </div>

        <div v-if="isPdfCompressRoute" class="compression-options" role="radiogroup" aria-label="PDF 压缩等级">
          <label :class="{ selected: compressionMode === 'lossless' }">
            <input v-model="compressionMode" type="radio" value="lossless" :disabled="busy" @change="savePreferences" />
            <span class="option-check"></span>
            <strong>无损优化</strong>
            <small>不改变图片质量，清理并压缩 PDF 结构</small>
            <em>画质优先</em>
          </label>
          <label :class="{ selected: compressionMode === 'balanced' }">
            <input v-model="compressionMode" type="radio" value="balanced" :disabled="busy" @change="savePreferences" />
            <span class="option-check"></span>
            <strong>均衡压缩</strong>
            <small>适度优化图片，兼顾清晰度和文件体积</small>
            <em>推荐</em>
          </label>
          <label :class="{ selected: compressionMode === 'strong' }">
            <input v-model="compressionMode" type="radio" value="strong" :disabled="busy" @change="savePreferences" />
            <span class="option-check"></span>
            <strong>强力压缩</strong>
            <small>显著降低图片分辨率，适合在线传输</small>
            <em>体积优先</em>
          </label>
          <p class="option-notice"><span>i</span> 若处理后的文件没有变小，系统会自动保留原文件。</p>
        </div>

        <div v-else-if="isPdfWatermarkRoute" class="watermark-workbench">
          <div class="watermark-options">
            <label class="wide-field">
              <span>水印文字</span>
              <input v-model="watermarkText" type="text" maxlength="80" placeholder="例如：机密资料" :disabled="busy" />
            </label>
            <label>
              <span>应用页面</span>
              <input v-model="watermarkPages" type="text" placeholder="all 或 1,3-5" :class="{ invalid: !watermarkPagesValid || watermarkRangeState.matches === false }" :disabled="busy" />
              <small v-if="!watermarkPagesValid" class="field-error">请输入 all、1 或 1,3-5</small>
              <small v-else-if="watermarkRangeState.matches === false" class="field-error">未匹配这份 PDF（共 {{ watermarkPreviewPageCount }} 页）</small>
              <small v-else-if="watermarkRangeState.overflow" class="field-warning">超出 {{ watermarkPreviewPageCount }} 页的部分将忽略</small>
            </label>
            <label>
              <span>位置</span>
              <select v-model="watermarkPosition" :disabled="busy || watermarkTiled">
                <option value="center">页面居中</option>
                <option value="top-left">左上角</option>
                <option value="top-right">右上角</option>
                <option value="bottom-left">左下角</option>
                <option value="bottom-right">右下角</option>
              </select>
            </label>
            <label>
              <span>颜色</span>
              <span class="color-field"><input v-model="watermarkColor" type="color" aria-label="选择水印颜色" :disabled="busy" /><b>{{ watermarkColor.toUpperCase() }}</b></span>
            </label>
            <label class="range-field">
              <span>不透明度 <b>{{ Math.round(watermarkOpacity * 100) }}%</b></span>
              <input v-model.number="watermarkOpacity" type="range" min="0.05" max="0.85" step="0.01" aria-label="水印不透明度" :disabled="busy" />
            </label>
            <label class="range-field">
              <span>旋转角度 <b>{{ watermarkAngle }}°</b></span>
              <input v-model.number="watermarkAngle" type="range" min="-180" max="180" step="1" aria-label="水印旋转角度" :disabled="busy" />
            </label>
            <label class="toggle-field">
              <input v-model="watermarkTiled" type="checkbox" :disabled="busy" />
              <span class="toggle"></span>
              <span><strong>平铺水印</strong><small>在整页重复显示水印</small></span>
            </label>
          </div>

          <section ref="watermarkPreviewRef" class="watermark-preview" aria-labelledby="watermark-preview-title">
            <header class="watermark-preview-head">
              <div><strong id="watermark-preview-title">效果预览</strong><small>仅在浏览器本地读取，不会提前上传</small></div>
              <span>本地预览</span>
            </header>
            <div ref="watermarkPreviewStage" class="watermark-preview-stage" :class="`is-${watermarkPreviewState}`">
              <div
                v-show="['rendering', 'ready'].includes(watermarkPreviewState)"
                class="watermark-preview-page"
                role="img"
                :aria-label="`PDF 第 ${watermarkPreviewPage} 页水印效果预览`"
              >
                <canvas ref="watermarkPreviewCanvas" aria-hidden="true"></canvas>
                <div
                  v-if="watermarkPreviewState === 'ready' && watermarkAppliesToPreviewPage && watermarkText.trim()"
                  class="watermark-preview-overlay"
                  :class="[watermarkTiled ? 'tiled' : 'single', watermarkPosition]"
                  :style="watermarkPreviewOverlayStyle"
                  aria-hidden="true"
                >
                  <template v-if="watermarkTiled">
                    <span v-for="index in 12" :key="index">{{ watermarkText.trim() }}</span>
                  </template>
                  <span v-else>{{ watermarkText.trim() }}</span>
                </div>
              </div>
              <div v-if="watermarkPreviewState === 'empty'" class="watermark-preview-placeholder">
                <span aria-hidden="true">PDF</span>
                <strong>添加 PDF 后即可预览</strong>
                <small>将显示第一页，并随设置实时更新</small>
              </div>
              <div v-else-if="['loading', 'rendering'].includes(watermarkPreviewState)" class="watermark-preview-placeholder" role="status" aria-live="polite">
                <i class="watermark-preview-spinner" aria-hidden="true"></i>
                <strong>{{ watermarkPreviewState === 'loading' ? '正在读取 PDF' : '正在渲染页面' }}</strong>
                <small>文件仅在当前设备内存中处理</small>
              </div>
              <div v-else-if="watermarkPreviewState === 'error'" class="watermark-preview-placeholder error" role="alert">
                <span aria-hidden="true">!</span>
                <strong>暂时无法预览</strong>
                <small>{{ watermarkPreviewError }}</small>
              </div>
            </div>
            <footer class="watermark-preview-footer">
              <div class="watermark-page-controls" aria-label="预览页码">
                <button type="button" :disabled="watermarkPreviewPage <= 1 || watermarkPreviewState !== 'ready'" aria-label="预览上一页" @click="changeWatermarkPreviewPage(-1)">←</button>
                <strong>{{ watermarkPreviewPageCount ? `${watermarkPreviewPage} / ${watermarkPreviewPageCount}` : '— / —' }}</strong>
                <button type="button" :disabled="watermarkPreviewPage >= watermarkPreviewPageCount || watermarkPreviewState !== 'ready'" aria-label="预览下一页" @click="changeWatermarkPreviewPage(1)">→</button>
              </div>
              <p :class="{ matched: watermarkPreviewState === 'ready' && watermarkAppliesToPreviewPage && watermarkPagesValid && watermarkText.trim() }" aria-live="polite">
                <span aria-hidden="true">{{ watermarkPreviewState === 'ready' && watermarkAppliesToPreviewPage && watermarkPagesValid && watermarkText.trim() ? '✓' : '○' }}</span>{{ watermarkPreviewStatus }}
              </p>
            </footer>
            <small class="watermark-preview-note">预览用于确认文字、位置与样式，最终效果以导出文件为准。</small>
          </section>
        </div>

        <div v-else class="split-options">
          <label>
            <span>要拆分的页面</span>
            <input v-model="splitPages" type="text" placeholder="all 或 1,3-5" :class="{ invalid: !splitPagesValid || splitRangeState.matches === false || splitRangeState.overflow }" :disabled="busy" />
            <small v-if="!splitPagesValid" class="field-error">请输入 all、1 或 1,3-5</small>
            <small v-else-if="splitRangeState.matches === false" class="field-error">未匹配这份 PDF（共 {{ pdfSourcePreviewPageCount }} 页）</small>
            <small v-else-if="splitRangeState.overflow" class="field-error">页码超出这份 PDF 的 {{ pdfSourcePreviewPageCount }} 页，请调整范围</small>
            <small v-else>输入 all 拆分全部页面；也可组合单页和连续区间。</small>
          </label>
          <div class="split-examples"><span>示例</span><button type="button" :disabled="busy" @click="splitPages = 'all'">全部页面</button><button type="button" :disabled="busy" @click="splitPages = '1'">仅第 1 页</button><button type="button" :disabled="busy" @click="splitPages = '1,3-5'">第 1、3–5 页</button><p v-if="splitSelectedPages.length">将按升序生成 {{ splitSelectedPages.length }} 个单页 PDF：{{ splitSelectedPages.slice(0, 12).join('、') }}{{ splitSelectedPages.length > 12 ? '…' : '' }}</p></div>
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
            {{ isSinglePdfTool && files.length ? '更换文件' : (files.length ? '继续添加' : '选择文件') }}
            <input type="file" :accept="acceptType" :multiple="!isSinglePdfTool" :disabled="busy || selectedRoute.status !== 'available'" @change="accept($event.target.files); $event.target.value = ''" />
          </label>
        </div>
      </div>

      <div v-if="files.length" class="file-panel">
        <div class="panel-heading">
          <div><strong>待转换文件</strong><small>将按下方顺序处理</small></div>
          <span>{{ files.length }} 个 · {{ formatBytes(selectedBytes) }}</span>
        </div>
        <ul>
          <li v-for="(file, index) in files" :key="`${file.name}:${file.size}:${file.lastModified}:${index}`">
            <span class="mini-icon">{{ selectedRoute.sourceFormat.slice(0, 3).toUpperCase() }}</span>
            <span class="file-name">{{ file.name }}</span>
            <span class="file-size">{{ formatBytes(file.size) }}</span>
            <span v-if="files.length > 1" class="file-order-actions"><button type="button" :disabled="busy || index === 0" :aria-label="`上移 ${file.name}`" title="上移" @click="moveFile(index, -1)">↑</button><button type="button" :disabled="busy || index === files.length - 1" :aria-label="`下移 ${file.name}`" title="下移" @click="moveFile(index, 1)">↓</button></span>
            <button class="remove" :disabled="busy" title="移除" @click="remove(index)">×</button>
          </li>
        </ul>
      </div>

      <div v-if="showPdfSourcePreview" ref="pdfSourcePreviewRef" class="source-preview-panel">
        <div v-if="isPdfMergeRoute && files.length > 1" class="pdf-preview-file-tabs" aria-label="选择要预览的合并文件">
          <button
            v-for="(file, index) in files"
            :key="fileIdentity(file)"
            type="button"
            :class="{ active: index === pdfSourcePreviewFileIndex }"
            :aria-current="index === pdfSourcePreviewFileIndex ? 'true' : undefined"
            :title="file.name"
            :disabled="busy"
            @click="selectPdfSourcePreviewFile(index)"
          ><b>{{ index + 1 }}</b><span>{{ file.name }}</span></button>
        </div>
        <PdfPreview
          :id="`source-${selectedRouteId}`"
          :source="pdfSourcePreviewFile"
          :title="isPdfMergeRoute ? '合并源文件预览' : (isPdfSplitRoute ? '拆分页预览' : '源文件预览')"
          :subtitle="pdfSourcePreviewFile?.name || '仅在浏览器本地读取，不会提前上传'"
          :badge="isPdfMergeRoute ? `第 ${pdfSourcePreviewFileIndex + 1} / ${files.length} 个` : '本地预览'"
          :note="(isPdfMergeRoute || isPdfSplitRoute) ? '预览用于确认页面、范围与顺序；重写 PDF 后不会保留数字签名的有效性。' : '这里展示源文件；转换完成后如结果为 PDF，会继续展示真实结果预览。'"
          @state-change="handlePdfSourcePreviewState"
        >
          <template #status="{ state, page, pageCount, error }">
            <p :class="{ matched: state === 'ready' && (!isPdfSplitRoute || pageMatchesRange(page, splitPages)) }" aria-live="polite">
              <span aria-hidden="true">{{ state === 'ready' ? (isPdfSplitRoute && !pageMatchesRange(page, splitPages) ? '○' : '✓') : '○' }}</span>{{ pdfSourceStatus(state, page, pageCount, error) }}
            </p>
          </template>
        </PdfPreview>
      </div>

      <div v-if="isImageToPdfRoute && files.length" ref="pdfSourcePreviewRef" class="source-preview-panel">
        <ImageCollectionPreview :files="files" />
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

        <div v-if="task?.errorMessage" class="task-error" role="alert"><strong>{{ task.errorCode || 'TASK_FAILED' }}</strong><p>{{ task.errorMessage }}</p></div>
      </div>

      <div v-if="resultPreviewEligible" class="result-preview-panel">
        <PdfPreview
          v-if="resultPreviewState === 'ready' && resultPreviewKind === 'pdf' && resultPreviewBlob"
          :id="`result-${task.taskId}`"
          :source="resultPreviewBlob"
          :title="resultPreviewTitle"
          subtitle="由转换服务真实生成，可在下载前逐页检查"
          badge="实际结果"
          note="这里渲染的是当前任务的真实 PDF 结果，与下载文件一致。"
        />
        <section v-else-if="resultPreviewState === 'ready' && resultPreviewKind === 'image'" class="direct-result-preview" aria-labelledby="direct-result-title">
          <header><div><strong id="direct-result-title">{{ resultPreviewTitle }}</strong><small>由转换服务真实生成，可在下载前检查</small></div><span>实际图片</span></header>
          <div class="direct-result-image"><img :src="resultPreviewImageUrl" :alt="task.downloadName" /></div>
          <footer>这里展示的是当前任务的真实图片结果，与下载文件一致。</footer>
        </section>
        <section v-else-if="resultPreviewState === 'ready' && ['text', 'csv'].includes(resultPreviewKind)" class="direct-result-preview text-result-preview" aria-labelledby="text-result-title">
          <header><div><strong id="text-result-title">{{ resultPreviewTitle }}</strong><small>纯文本安全展示，不执行 HTML、链接或公式</small></div><span>{{ resultPreviewKind === 'csv' ? '实际 CSV' : '实际文本' }}</span></header>
          <pre>{{ resultPreviewText }}</pre>
          <footer>这里展示的是当前任务的真实文本结果；超长内容会截断预览，但下载文件保持完整。</footer>
        </section>
        <section v-else class="result-preview-message" :class="{ error: ['error', 'too-large'].includes(resultPreviewState) }" aria-live="polite">
          <span aria-hidden="true">{{ resultPreviewState === 'loading' ? '…' : '!' }}</span>
          <p><strong>{{ resultPreviewState === 'loading' ? '正在准备真实结果预览' : '暂不内联预览结果' }}</strong><small>{{ resultPreviewError || '转换完成后会自动加载可预览的 PDF 结果' }}</small></p>
        </section>
      </div>

      <p v-if="message" class="message">{{ message }}</p>

      <div v-if="batchSettingsDirty" class="watermark-dirty-notice" role="alert">
        <span aria-hidden="true">↻</span>
        <p>
          <strong>{{ dirtySettingsLabel }}已修改，需要重新生成</strong>
          <small v-if="task?.status === 'SUCCESS'">当前预览和下载仍是上一次生成的结果。重新生成后才会应用现在的文件与设置。</small>
          <small v-else>“重试上次任务”仍会使用提交时的旧设置；请按当前设置重新生成。</small>
        </p>
      </div>

      <div class="actions">
        <button v-if="!task" class="primary" :disabled="!canSubmit" @click="submit">开始转换 <span aria-hidden="true">→</span></button>
        <button v-if="batchSettingsDirty" class="primary" :disabled="!canSubmit" @click="regenerateCurrentBatch">按当前设置重新生成 <span aria-hidden="true">↻</span></button>
        <button v-if="task?.downloadReady" :class="batchSettingsDirty ? 'secondary' : 'primary'" :disabled="downloadingTaskId === task.taskId" @click="download">{{ downloadingTaskId === task.taskId ? '正在下载…' : (batchSettingsDirty ? '下载上次结果' : `下载 ${task.downloadName}`) }} <span aria-hidden="true">↓</span></button>
        <button v-if="busy && !task" class="secondary" @click="cancelUpload">取消上传</button>
        <button v-if="task && ['WAITING', 'CONVERTING'].includes(task.status)" class="secondary" @click="cancelTask">取消任务</button>
        <button v-if="task && ['FAILED', 'CANCELLED'].includes(task.status)" class="secondary" @click="retryTask">{{ batchSettingsDirty ? '重试上次任务' : '重试' }}</button>
        <button v-if="task" class="secondary" @click="reset">转换其他文件</button>
      </div>
        </section>
      </div>

      <footer class="app-statusbar">
        <span><i :class="{ online: serviceHealthy }"></i>{{ serviceHealthy ? '转换服务已连接' : '正在连接转换服务' }}</span>
        <span>{{ desktopRuntime ? '文档仅在本机处理' : '不转交第三方云服务' }}</span>
        <span>v{{ diagnostics?.version || '0.1.4' }}</span>
      </footer>
    </main>
  </div>
</template>
