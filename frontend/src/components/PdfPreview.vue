<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { blocksPdfSubmission, loadPdfJs, pdfPreviewError } from '../pdfPreviewRuntime.js'

const props = defineProps({
  source: { type: Object, default: null },
  title: { type: String, default: 'PDF 预览' },
  subtitle: { type: String, default: '仅在浏览器本地读取，不会提前上传' },
  badge: { type: String, default: '本地预览' },
  emptyTitle: { type: String, default: '添加 PDF 后即可预览' },
  emptyHint: { type: String, default: '可逐页查看文件内容' },
  note: { type: String, default: '预览用于确认页面与顺序，最终效果以导出文件为准。' },
  ariaPrefix: { type: String, default: 'PDF' }
})

const emit = defineEmits(['state-change', 'page-change'])
const canvasRef = ref(null)
const stageRef = ref(null)
const state = ref('empty')
const errorMessage = ref('')
const page = ref(1)
const pageCount = ref(0)
let pdfDocument
let loadingTask
let renderTask
let loadGeneration = 0
let renderGeneration = 0
let resizeObserver
let resizeTimer

function snapshot(extra = {}) {
  return {
    source: props.source,
    state: state.value,
    page: page.value,
    pageCount: pageCount.value,
    error: errorMessage.value,
    blocking: false,
    ...extra
  }
}

function publish(extra = {}) {
  emit('state-change', snapshot(extra))
}

function dispose() {
  renderGeneration++
  if (renderTask) {
    try { renderTask.cancel() } catch (_) { /* already complete */ }
    renderTask = null
  }
  if (loadingTask) {
    const task = loadingTask
    loadingTask = null
    Promise.resolve(task.destroy()).catch(() => {})
  }
  if (pdfDocument) {
    const document = pdfDocument
    pdfDocument = null
    Promise.resolve(document.destroy()).catch(() => {})
  }
}

function resetCanvas() {
  const canvas = canvasRef.value
  if (!canvas) return
  canvas.width = 0
  canvas.height = 0
  canvas.removeAttribute('style')
}

async function load(source) {
  const generation = ++loadGeneration
  dispose()
  page.value = 1
  pageCount.value = 0
  errorMessage.value = ''
  resetCanvas()
  if (!source) {
    state.value = 'empty'
    publish()
    return
  }

  state.value = 'loading'
  publish()
  try {
    const data = new Uint8Array(await source.arrayBuffer())
    if (generation !== loadGeneration) return
    const { getDocument } = await loadPdfJs()
    if (generation !== loadGeneration) return
    loadingTask = getDocument({ data, isEvalSupported: false })
    const document = await loadingTask.promise
    if (generation !== loadGeneration) {
      await document.destroy().catch(() => {})
      return
    }
    loadingTask = null
    pdfDocument = document
    pageCount.value = document.numPages
    await renderPage()
  } catch (error) {
    if (generation !== loadGeneration || error?.name === 'RenderingCancelledException') return
    loadingTask = null
    state.value = 'error'
    errorMessage.value = pdfPreviewError(error)
    publish({ blocking: blocksPdfSubmission(error) })
  }
}

async function renderPage() {
  if (!pdfDocument) return
  const document = pdfDocument
  const generation = ++renderGeneration
  if (renderTask) {
    try { renderTask.cancel() } catch (_) { /* already complete */ }
    renderTask = null
  }
  page.value = Math.min(Math.max(1, page.value), document.numPages)
  state.value = 'rendering'
  errorMessage.value = ''
  publish()
  await nextTick()

  let pdfPage
  try {
    pdfPage = await document.getPage(page.value)
    if (generation !== renderGeneration || document !== pdfDocument) return
    const canvas = canvasRef.value
    if (!canvas) return
    const baseViewport = pdfPage.getViewport({ scale: 1 })
    const stage = stageRef.value
    const availableWidth = Math.max(180, Math.min(840, (stage?.clientWidth || 560) - 32))
    const availableHeight = Math.max(240, Math.min(640, (stage?.clientHeight || 470) - 32))
    const scale = Math.min(6, availableWidth / baseViewport.width, availableHeight / baseViewport.height)
    if (!Number.isFinite(scale) || scale <= 0) throw new Error('Invalid PDF page viewport')
    const viewport = pdfPage.getViewport({ scale })
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 2)
    canvas.width = Math.max(1, Math.floor(viewport.width * pixelRatio))
    canvas.height = Math.max(1, Math.floor(viewport.height * pixelRatio))
    canvas.style.width = `${Math.floor(viewport.width)}px`
    canvas.style.height = `${Math.floor(viewport.height)}px`
    const context = canvas.getContext('2d', { alpha: false })
    renderTask = pdfPage.render({
      canvasContext: context,
      viewport,
      transform: pixelRatio === 1 ? null : [pixelRatio, 0, 0, pixelRatio, 0, 0]
    })
    await renderTask.promise
    if (generation !== renderGeneration || document !== pdfDocument) return
    renderTask = null
    state.value = 'ready'
    publish()
    emit('page-change', snapshot())
  } catch (error) {
    if (generation !== renderGeneration || error?.name === 'RenderingCancelledException') return
    renderTask = null
    state.value = 'error'
    errorMessage.value = pdfPreviewError(error)
    publish({ blocking: blocksPdfSubmission(error) })
  } finally {
    pdfPage?.cleanup()
  }
}

function changePage(offset) {
  const nextPage = page.value + offset
  if (!pdfDocument || nextPage < 1 || nextPage > pageCount.value) return
  page.value = nextPage
  void renderPage()
}

function scheduleResize() {
  if (!pdfDocument || !['ready', 'rendering'].includes(state.value)) return
  clearTimeout(resizeTimer)
  resizeTimer = setTimeout(() => void renderPage(), 120)
}

watch(() => props.source, source => void load(source), { immediate: true, flush: 'post' })

onMounted(() => {
  if ('ResizeObserver' in window) {
    resizeObserver = new ResizeObserver(scheduleResize)
    if (stageRef.value) resizeObserver.observe(stageRef.value)
  } else {
    window.addEventListener('resize', scheduleResize)
  }
})

onBeforeUnmount(() => {
  loadGeneration++
  clearTimeout(resizeTimer)
  resizeObserver?.disconnect()
  window.removeEventListener('resize', scheduleResize)
  dispose()
})
</script>

<template>
  <section class="watermark-preview pdf-document-preview" :aria-labelledby="`pdf-preview-${$attrs.id || 'title'}`">
    <header class="watermark-preview-head">
      <div><strong :id="`pdf-preview-${$attrs.id || 'title'}`">{{ title }}</strong><small>{{ subtitle }}</small></div>
      <span>{{ badge }}</span>
    </header>
    <div ref="stageRef" class="watermark-preview-stage" :class="`is-${state}`">
      <div v-show="['rendering', 'ready'].includes(state)" class="watermark-preview-page" role="img" :aria-label="`${ariaPrefix} 第 ${page} 页预览`">
        <canvas ref="canvasRef" aria-hidden="true"></canvas>
      </div>
      <div v-if="state === 'empty'" class="watermark-preview-placeholder">
        <span aria-hidden="true">PDF</span>
        <strong>{{ emptyTitle }}</strong>
        <small>{{ emptyHint }}</small>
      </div>
      <div v-else-if="['loading', 'rendering'].includes(state)" class="watermark-preview-placeholder" role="status" aria-live="polite">
        <i class="watermark-preview-spinner" aria-hidden="true"></i>
        <strong>{{ state === 'loading' ? '正在读取 PDF' : '正在渲染页面' }}</strong>
        <small>文件仅在当前设备内存中处理</small>
      </div>
      <div v-else-if="state === 'error'" class="watermark-preview-placeholder error" role="alert">
        <span aria-hidden="true">!</span>
        <strong>暂时无法预览</strong>
        <small>{{ errorMessage }}</small>
      </div>
    </div>
    <footer class="watermark-preview-footer">
      <div class="watermark-page-controls" aria-label="预览页码">
        <button type="button" :disabled="page <= 1 || state !== 'ready'" aria-label="预览上一页" @click="changePage(-1)">←</button>
        <strong>{{ pageCount ? `${page} / ${pageCount}` : '— / —' }}</strong>
        <button type="button" :disabled="page >= pageCount || state !== 'ready'" aria-label="预览下一页" @click="changePage(1)">→</button>
      </div>
      <slot name="status" :state="state" :page="page" :page-count="pageCount" :error="errorMessage">
        <p :class="{ matched: state === 'ready' }"><span aria-hidden="true">{{ state === 'ready' ? '✓' : '○' }}</span>{{ state === 'ready' ? `第 ${page} 页预览就绪` : '正在准备预览' }}</p>
      </slot>
    </footer>
    <small class="watermark-preview-note">{{ note }}</small>
  </section>
</template>
