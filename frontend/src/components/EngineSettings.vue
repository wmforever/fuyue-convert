<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { engineStatusLabel } from '../engineSettingsClient.js'

const props = defineProps({ diagnostics: Object, busy: Boolean })
const bridge = window.formatConverterDesktop
const settings = ref({ ocrMode: 'auto', ocrBinary: '', tessdataDirectory: '', ocrLanguages: 'chi_sim+eng', officeMode: 'auto', officeBinary: '' })
const loaded = ref(null)
const processing = ref(false)
const message = ref('')
const checks = ref(null)
const savedFingerprint = ref('')
const supported = computed(() => typeof bridge?.getEngineSettings === 'function')
const dirty = computed(() => JSON.stringify(settings.value) !== savedFingerprint.value)
const locked = computed(() => processing.value || props.busy)

async function load() {
  if (!supported.value) return
  processing.value = true
  try {
    loaded.value = await bridge.getEngineSettings()
    settings.value = { ...loaded.value.settings }
    savedFingerprint.value = JSON.stringify(settings.value)
    message.value = loaded.value.loadError || ''
  } catch (error) { message.value = error.message || '引擎设置读取失败' }
  finally { processing.value = false }
}

async function choose(kind) {
  processing.value = true
  try {
    const result = await bridge.chooseEnginePath(kind)
    if (!result.cancelled) { settings.value[kind] = result.path; checks.value = null; message.value = '路径已选择，请检测并保存配置' }
  } catch (error) { message.value = error.message || '文件选择失败' }
  finally { processing.value = false }
}

async function probe() {
  processing.value = true; message.value = '正在运行引擎检测…'
  try {
    checks.value = await bridge.probeEngineSettings({ ...settings.value })
    message.value = '检测完成；OCR 会实际识别内置测试图片，不使用你的文档。'
  } catch (error) { message.value = error.message || '引擎检测失败' }
  finally { processing.value = false }
}

async function save() {
  processing.value = true; message.value = '正在检测并保存配置…'
  try {
    loaded.value = await bridge.saveEngineSettings({ ...settings.value })
    settings.value = { ...loaded.value.settings }
    checks.value = loaded.value.checks
    savedFingerprint.value = JSON.stringify(settings.value)
    message.value = loaded.value.pendingRestart ? '配置已保存，重启应用后生效。当前任务继续使用原配置。' : '配置已保存。'
  } catch (error) { message.value = error.message || '配置保存失败' }
  finally { processing.value = false }
}

async function restart() {
  processing.value = true
  try { await bridge.restartForEngines(); message.value = '正在重启应用…' }
  catch (error) { message.value = error.message || '暂不能重启' ; processing.value = false }
}

watch(() => JSON.stringify(settings.value), () => { checks.value = null })
onMounted(load)
</script>

<template>
  <section class="settings-card engine-config-card">
    <div class="settings-head"><span>06</span><div><strong>OCR 与 Office 配置</strong><small>离线引擎无需账号绑定或 API 密钥</small></div></div>
    <p>新版 Lite 和 Full 安装包均包含中英文 OCR，安装后即可使用；Full 还包含 Office 引擎。</p>
    <dl class="engine-current"><div><dt>当前 OCR</dt><dd>{{ engineStatusLabel(diagnostics?.ocr) }}<small v-if="diagnostics?.ocr?.available"> · {{ diagnostics.ocr.bundled ? '内置引擎' : '本机引擎' }} · {{ diagnostics.ocr.requestedLanguages }}</small></dd></div></dl>
    <template v-if="supported && loaded">
      <div class="engine-config-grid">
        <div>
          <label class="setting-select"><span>OCR 来源</span><select v-model="settings.ocrMode" :disabled="locked"><option value="auto">内置优先 / 自动检测（推荐）</option><option value="custom">指定本机 Tesseract</option><option value="disabled">停用 OCR</option></select></label>
          <small class="engine-help">{{ loaded.bundledOcr ? '已找到随安装包提供的 OCR 引擎和语言包。' : '当前资源中没有内置 OCR；可选择本机引擎，或重新安装包含 OCR 的新版本。' }}</small>
          <template v-if="settings.ocrMode === 'custom'">
            <label class="engine-path"><span>Tesseract 可执行文件</span><div><input :value="settings.ocrBinary" readonly placeholder="选择 tesseract / tesseract.exe" /><button type="button" :disabled="locked" @click="choose('ocrBinary')">选择文件</button></div></label>
            <label class="engine-path"><span>语言包文件夹（tessdata）</span><div><input :value="settings.tessdataDirectory" readonly placeholder="留空使用引擎默认语言包" /><button type="button" :disabled="locked" @click="choose('tessdataDirectory')">选择文件夹</button><button v-if="settings.tessdataDirectory" type="button" :disabled="locked" @click="settings.tessdataDirectory = ''">默认</button></div><small>通常包含 chi_sim.traineddata、eng.traineddata 和 configs/tsv；程序与语言包可分开放置。</small></label>
          </template>
          <label class="setting-select"><span>识别语言</span><select v-model="settings.ocrLanguages" :disabled="locked || settings.ocrMode === 'disabled'"><option value="chi_sim+eng">简体中文 + 英文（推荐）</option><option value="eng">英文</option><option value="chi_sim+chi_sim_vert+eng">简体中文 + 竖排中文 + 英文</option></select></label>
          <p v-if="checks?.ocr" class="engine-check" :class="{ good: checks.ocr.available }" role="status">{{ checks.ocr.message }}</p>
        </div>
        <div>
          <label class="setting-select"><span>Office 来源</span><select v-model="settings.officeMode" :disabled="locked"><option value="auto">内置优先 / 自动检测（推荐）</option><option value="custom">指定本机 LibreOffice</option><option value="disabled">停用 Office 引擎</option></select></label>
          <small class="engine-help">{{ loaded.bundledOffice ? '已找到安装包内置的 LibreOffice。' : '当前未内置 Office，可指定本机 LibreOffice；需要完整离线能力时使用 Full 版。' }}</small>
          <label v-if="settings.officeMode === 'custom'" class="engine-path"><span>LibreOffice 可执行文件</span><div><input :value="settings.officeBinary" readonly placeholder="选择 soffice / soffice.exe" /><button type="button" :disabled="locked" @click="choose('officeBinary')">选择文件</button></div><small>Windows 通常在 LibreOffice/program；macOS 位于 LibreOffice.app/Contents/MacOS。可在文件选择框按 ⌘⇧G 输入该目录。</small></label>
          <p v-if="checks?.office" class="engine-check" :class="{ good: checks.office.available }" role="status">{{ checks.office.message }}</p>
        </div>
      </div>
      <div class="settings-actions"><button type="button" :disabled="locked" @click="probe">检测配置</button><button type="button" :disabled="locked || !dirty || !loaded.managed" @click="save">检测并保存</button><button v-if="loaded.pendingRestart && loaded.managed" type="button" :disabled="locked || dirty" @click="restart">重启应用并生效</button></div>
      <small class="engine-help">{{ loaded.managed ? '配置保存在当前设备，重启后生效。存在转换、排队或保存任务时不能从此处重启。' : '当前连接的是外部服务，不能通过此处修改；请在服务所在设备配置并重启。' }}</small>
    </template>
    <p v-else-if="!supported" class="engine-help">当前界面由独立服务提供，不能选择浏览器之外的可执行文件。桌面新版提供本机引擎选择；服务部署可设置 FORMAT_CONVERTER_OCR_ENABLED=true、FORMAT_CONVERTER_TESSERACT_BINARY 和 FORMAT_CONVERTER_TESSDATA_DIR，重启服务后生效。</p>
    <p v-if="message" class="engine-check" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.engine-config-card { grid-column: 1 / -1; }
.engine-config-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 28px; margin: 20px 0; }
.engine-path { display: block; margin: 18px 0; }
.engine-path > span { display: block; margin-bottom: 8px; font-size: 13px; }
.engine-path > div { display: flex; gap: 8px; }
.engine-path input { min-width: 0; flex: 1; border: 1px solid #2c4770; border-radius: 7px; padding: 9px; background: #13223a; color: inherit; }
.engine-path button { flex-shrink: 0; border: 1px solid #2c4770; border-radius: 7px; padding: 0 9px; color: #9dbbf0; background: #13223a; }
.engine-help, .engine-path small { display: block; line-height: 1.7; opacity: .7; font-size: 12px; margin-top: 10px; overflow-wrap: anywhere; }
.engine-check { line-height: 1.7; font-size: 13px; overflow-wrap: anywhere; }
.engine-current { margin-top: 18px; }
@media (max-width: 1000px) { .engine-config-grid { grid-template-columns: 1fr; gap: 12px; } }
</style>
