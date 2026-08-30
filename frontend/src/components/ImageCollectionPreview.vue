<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps({
  files: { type: Array, default: () => [] },
  title: { type: String, default: '图片顺序预览' },
  maxVisible: { type: Number, default: 24 }
})

const items = ref([])
const dimensions = ref(new Map())
const failed = ref(new Set())
const objectUrls = new Map()
const hiddenCount = computed(() => Math.max(0, props.files.length - props.maxVisible))

function syncFiles(files) {
  const visible = files.slice(0, props.maxVisible)
  const retained = new Set(visible)
  for (const [file, url] of objectUrls) {
    if (!retained.has(file)) {
      URL.revokeObjectURL(url)
      objectUrls.delete(file)
    }
  }
  items.value = visible.map((file, index) => {
    if (!objectUrls.has(file)) objectUrls.set(file, URL.createObjectURL(file))
    return { file, index, url: objectUrls.get(file) }
  })
  dimensions.value = new Map([...dimensions.value].filter(([file]) => retained.has(file)))
  failed.value = new Set([...failed.value].filter(file => retained.has(file)))
}

function markLoaded(file, event) {
  const image = event.currentTarget
  const next = new Map(dimensions.value)
  next.set(file, `${image.naturalWidth} × ${image.naturalHeight}`)
  dimensions.value = next
}

function markFailed(file) {
  const next = new Set(failed.value)
  next.add(file)
  failed.value = next
}

watch(() => props.files.slice(), syncFiles, { immediate: true })

onBeforeUnmount(() => {
  for (const url of objectUrls.values()) URL.revokeObjectURL(url)
  objectUrls.clear()
})
</script>

<template>
  <section class="image-collection-preview" aria-labelledby="image-preview-title">
    <header>
      <div><strong id="image-preview-title">{{ title }}</strong><small>页码与上传顺序一致，可在文件列表中上下调整</small></div>
      <span>源图预览</span>
    </header>
    <div class="image-preview-grid">
      <article v-for="item in items" :key="item.url" :class="{ error: failed.has(item.file) }">
        <div class="image-preview-frame">
          <img v-if="!failed.has(item.file)" :src="item.url" :alt="`第 ${item.index + 1} 页：${item.file.name}`" loading="lazy" @load="markLoaded(item.file, $event)" @error="markFailed(item.file)" />
          <span v-else aria-hidden="true">!</span>
        </div>
        <p><b>{{ item.index + 1 }}</b><span :title="item.file.name">{{ item.file.name }}</span></p>
        <small>{{ failed.has(item.file) ? '图片无法在浏览器中读取' : (dimensions.get(item.file) || '正在读取尺寸') }}</small>
      </article>
    </div>
    <p v-if="hiddenCount" class="image-preview-more">另有 {{ hiddenCount }} 张图片未展开，仍会按文件列表顺序生成 PDF。</p>
    <footer>这里展示源图片和顺序；PDF 物理页面尺寸仍按图片 DPI 与 EXIF 方向生成，最终以导出文件为准。</footer>
  </section>
</template>
