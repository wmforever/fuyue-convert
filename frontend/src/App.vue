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
  status: 'available'
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
let pollTimer

const canSubmit = computed(() => files.value.length > 0 && !busy.value && selectedRoute.value?.status === 'available')
const selectedRoute = computed(() => conversions.value.find(route => route.id === selectedRouteId.value) || conversions.value[0])
const availableRoutes = computed(() => conversions.value.filter(route => route.status === 'available'))
const acceptExtension = computed(() => selectedRoute.value?.inputExtension || '.ofd')
const acceptType = computed(() => `${acceptExtension.value},application/${selectedRoute.value?.sourceFormat || 'ofd'}`)
const statusLabel = computed(() => ({ WAITING: '等待转换', CONVERTING: '正在转换', SUCCESS: '转换完成', FAILED: '转换失败' })[task.value?.status] || '')
const progress = computed(() => task.value?.progress ?? uploadProgress.value)
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

function routeGroupLabel(route) {
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
    if (!response.ok) return
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
  const extension = acceptExtension.value.toLowerCase()
  const incoming = Array.from(selected || []).filter(file => file.name.toLowerCase().endsWith(extension))
  if (incoming.length && task.value && !busy.value) startNewBatch()
  const known = new Set(files.value.map(file => `${file.name}:${file.size}`))
  for (const file of incoming) if (!known.has(`${file.name}:${file.size}`)) files.value.push(file)
  if (incoming.length !== (selected?.length || 0)) message.value = `已忽略非 ${acceptExtension.value.toUpperCase()} 文件`
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

onMounted(() => {
  loadCapabilities()
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
                  :class="{ selected: route.id === selectedRouteId, planned: route.status !== 'available' }"
                  :disabled="route.status !== 'available'"
                  role="option"
                  :aria-selected="route.id === selectedRouteId"
                  @click="selectRoute(route)"
                >
                  <span class="route-main">
                    <strong>{{ formatRouteLabel(route) }}</strong>
                    <small>{{ route.description }}</small>
                  </span>
                  <span class="route-badge">{{ route.status === 'available' ? '可用' : '规划中' }}</span>
                </button>
              </section>
            </div>
          </div>
        </div>
        <span class="route-description">{{ selectedRoute.description }}{{ selectedRoute.status === 'available' ? '' : '（暂未开放）' }}</span>
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
        <p>支持单个或批量上传，单文件最大 50 MB</p>
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
            <p><strong>{{ file.fileName }}</strong><small>{{ file.success ? `已完整转换 ${file.pageCount ?? 0} 页，生成 ${selectedRoute.targetLabel}` : `${file.errorCode}：${file.errorMessage}` }}</small></p>
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
      <span>当前开放文档 / 表格 / PDF / 图片</span><span>国产格式已入矩阵</span><span>不经过外部服务</span>
    </footer>
  </main>
</template>
