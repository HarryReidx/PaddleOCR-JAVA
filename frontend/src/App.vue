<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'

const apiBase = '/api/ocr'
const selectedFile = ref(null)
const originalFile = ref(null)
const previewUrl = ref('')
const selectedEngine = ref('paddleocr-cpu')
const activeImageEffect = ref('original')
const submitting = ref(false)
const cancelling = ref(false)
const applyingEffect = ref(false)
const currentTask = ref(null)
const tasks = ref([])
const notifications = ref([])
const queue = reactive({ queueSize: 0, activeWorkers: 0 })
const toast = ref('')
let eventSource
let toastTimer
let refreshTimer
let effectTimer
let effectSequence = 0

const engineOptions = [
  { value: 'paddleocr-cpu', label: 'PaddleOCR-CPU', description: '' },
  { value: 'qwen-vl', label: 'Qwen-VL', description: '' },
  { value: 'glm-ocr', label: 'GLM-OCR', description: '' },
  { value: 'paddleocr-vl', label: 'PaddleOCR-VL', description: '' },
]

const imageEffectOptions = [
  { value: 'original', label: '原图', adjustable: false, sliderLabel: '' },
  { value: 'blur', label: '模糊', adjustable: true, sliderLabel: '模糊度' },
  { value: 'skew', label: '倾斜', adjustable: true, sliderLabel: '倾斜度' },
  { value: 'distort', label: '失真', adjustable: true, sliderLabel: '失真度' },
  { value: 'occlude', label: '遮挡', adjustable: true, sliderLabel: '遮挡度' },
  { value: 'stain', label: '水渍', adjustable: true, sliderLabel: '水渍度' },
  { value: 'wrinkle', label: '褶皱', adjustable: true, sliderLabel: '褶皱度' },
]

const defaultEffectIntensity = {
  blur: 36,
  skew: 30,
  distort: 42,
  occlude: 30,
  stain: 46,
  wrinkle: 34,
}

const effectIntensity = reactive({ ...defaultEffectIntensity })
const engineLabelMap = Object.fromEntries(engineOptions.map((item) => [item.value, item.label]))
const imageEffectLabelMap = Object.fromEntries(imageEffectOptions.map((item) => [item.value, item.label]))

const stats = computed(() => ({
  total: tasks.value.length,
  completed: tasks.value.filter((item) => item.status === 'COMPLETED').length,
  queued: tasks.value.filter((item) => ['QUEUED', 'PROCESSING', 'CANCEL_REQUESTED'].includes(item.status)).length,
}))

const prettyJsonOutput = computed(() => {
  if (!currentTask.value?.jsonOutput) {
    return ''
  }
  try {
    return JSON.stringify(JSON.parse(currentTask.value.jsonOutput), null, 2)
  } catch {
    return currentTask.value.jsonOutput
  }
})

const parsedFieldEntries = computed(() => Object.entries(currentTask.value?.parsedFields || {}))
const showSyncResultLoading = computed(() => submitting.value && currentTask.value?.mode !== 'ASYNC')
const currentEffectOption = computed(() => imageEffectOptions.find((item) => item.value === activeImageEffect.value) || imageEffectOptions[0])
const currentEffectPercent = computed(() => effectIntensity[activeImageEffect.value] ?? 0)
const previewStatusLabel = computed(() => {
  if (applyingEffect.value) {
    return `正在生成${imageEffectLabel(activeImageEffect.value)}`
  }
  return `当前效果：${imageEffectLabel(activeImageEffect.value)}`
})

function engineLabel(engineType) {
  return engineLabelMap[engineType] || engineType || '-'
}

function imageEffectLabel(effectType) {
  return imageEffectLabelMap[effectType] || effectType || '-'
}

function setToast(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toast.value = ''
  }, 2800)
}

function revokePreviewUrl() {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
}

function updatePreview(file) {
  revokePreviewUrl()
  previewUrl.value = file ? URL.createObjectURL(file) : ''
}

function buildDerivedName(fileName, effect) {
  const dotIndex = fileName.lastIndexOf('.')
  if (dotIndex < 0) {
    return `${fileName}-${effect}`
  }
  return `${fileName.slice(0, dotIndex)}-${effect}${fileName.slice(dotIndex)}`
}

async function copyText(content, label) {
  if (!content) {
    setToast(`${label}暂无可复制内容`)
    return
  }
  try {
    await navigator.clipboard.writeText(content)
    setToast(`${label}已复制`)
  } catch {
    setToast(`${label}复制失败`)
  }
}

function loadImageElement(file) {
  return new Promise((resolve, reject) => {
    const fileUrl = URL.createObjectURL(file)
    const image = new Image()
    image.onload = () => {
      URL.revokeObjectURL(fileUrl)
      resolve(image)
    }
    image.onerror = () => {
      URL.revokeObjectURL(fileUrl)
      reject(new Error('图片加载失败'))
    }
    image.src = fileUrl
  })
}

function createCanvasContext(width, height) {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) {
    throw new Error('浏览器不支持 Canvas')
  }
  context.fillStyle = '#ffffff'
  context.fillRect(0, 0, width, height)
  return { canvas, context }
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}

function intensityRatio(effect) {
  return clamp((effectIntensity[effect] ?? 0) / 100, 0, 1)
}

function addWaterStains(context, width, height, ratio) {
  const stainCount = 2 + Math.round(ratio * 4)
  const baseAlpha = 0.12 + ratio * 0.18
  for (let index = 0; index < stainCount; index += 1) {
    const cx = width * (0.18 + ((index * 23) % 57) / 100)
    const cy = height * (0.16 + ((index * 19) % 49) / 100)
    const radius = Math.max(32, Math.min(width, height) * (0.08 + ratio * 0.12))
    const gradient = context.createRadialGradient(cx, cy, radius * 0.1, cx, cy, radius)
    gradient.addColorStop(0, `rgba(175, 187, 201, ${baseAlpha})`)
    gradient.addColorStop(0.55, `rgba(201, 212, 224, ${baseAlpha * 0.72})`)
    gradient.addColorStop(1, 'rgba(255,255,255,0)')
    context.fillStyle = gradient
    context.beginPath()
    context.arc(cx, cy, radius, 0, Math.PI * 2)
    context.fill()
  }

  context.strokeStyle = `rgba(149, 165, 185, ${0.12 + ratio * 0.12})`
  context.lineWidth = 2 + ratio * 4
  context.lineCap = 'round'
  for (let index = 0; index < stainCount; index += 1) {
    context.beginPath()
    const startX = width * (0.22 + index * 0.12)
    const startY = height * (0.12 + (index % 3) * 0.18)
    context.moveTo(startX, startY)
    context.bezierCurveTo(
      startX + 12,
      startY + 20,
      startX - 8,
      startY + 55,
      startX + 10,
      startY + 90,
    )
    context.stroke()
  }
}

function addWrinkles(context, width, height, ratio) {
  const foldCount = 2 + Math.round(ratio * 4)
  for (let index = 0; index < foldCount; index += 1) {
    const x = width * (0.16 + index * (0.62 / Math.max(1, foldCount - 1)))
    const foldWidth = 12 + ratio * 18
    const gradient = context.createLinearGradient(x - foldWidth, 0, x + foldWidth, 0)
    gradient.addColorStop(0, 'rgba(255,255,255,0)')
    gradient.addColorStop(0.35, `rgba(90, 105, 128, ${0.10 + ratio * 0.14})`)
    gradient.addColorStop(0.5, `rgba(255,255,255, ${0.10 + ratio * 0.16})`)
    gradient.addColorStop(0.65, `rgba(90, 105, 128, ${0.08 + ratio * 0.12})`)
    gradient.addColorStop(1, 'rgba(255,255,255,0)')
    context.fillStyle = gradient
    context.fillRect(x - foldWidth, 0, foldWidth * 2, height)
  }
}

async function buildEffectFile(file, effect) {
  const image = await loadImageElement(file)
  const mimeType = file.type && file.type.startsWith('image/') ? file.type : 'image/png'
  const ratio = intensityRatio(effect)
  let canvas
  let context

  switch (effect) {
    case 'blur': {
      ;({ canvas, context } = createCanvasContext(image.width, image.height))
      context.filter = `blur(${1 + ratio * 10}px)`
      context.drawImage(image, 0, 0, image.width, image.height)
      context.filter = 'none'
      break
    }
    case 'skew': {
      const angle = (ratio * 18 - 9) * (Math.PI / 180)
      const padding = Math.ceil(Math.max(image.width, image.height) * 0.14)
      ;({ canvas, context } = createCanvasContext(image.width + padding * 2, image.height + padding * 2))
      context.translate(canvas.width / 2, canvas.height / 2)
      context.rotate(angle)
      context.drawImage(image, -image.width / 2, -image.height / 2, image.width, image.height)
      context.setTransform(1, 0, 0, 1, 0, 0)
      break
    }
    case 'distort': {
      ;({ canvas, context } = createCanvasContext(image.width, image.height))
      const amplitude = 4 + ratio * 22
      const frequency = 14 + (1 - ratio) * 18
      for (let y = 0; y < image.height; y += 2) {
        const offset = Math.round(Math.sin(y / frequency) * amplitude)
        context.drawImage(image, 0, y, image.width, 2, offset, y, image.width, 2)
      }
      break
    }
    case 'occlude': {
      ;({ canvas, context } = createCanvasContext(image.width, image.height))
      context.drawImage(image, 0, 0, image.width, image.height)
      const blockCount = 1 + Math.round(ratio * 2)
      for (let index = 0; index < blockCount; index += 1) {
        const coverWidth = Math.max(48, Math.round(image.width * (0.12 + ratio * 0.22)))
        const coverHeight = Math.max(32, Math.round(image.height * (0.08 + ratio * 0.14)))
        const x = image.width - coverWidth - 18 - index * Math.round(coverWidth * 0.35)
        const y = 18 + index * Math.round(coverHeight * 0.55)
        context.fillStyle = `rgba(24, 32, 44, ${0.62 + ratio * 0.26})`
        context.fillRect(x, y, coverWidth, coverHeight)
      }
      break
    }
    case 'stain': {
      ;({ canvas, context } = createCanvasContext(image.width, image.height))
      context.drawImage(image, 0, 0, image.width, image.height)
      addWaterStains(context, image.width, image.height, ratio)
      break
    }
    case 'wrinkle': {
      ;({ canvas, context } = createCanvasContext(image.width, image.height))
      context.drawImage(image, 0, 0, image.width, image.height)
      addWrinkles(context, image.width, image.height, ratio)
      break
    }
    default:
      return file
  }

  const blob = await new Promise((resolve) => canvas.toBlob(resolve, mimeType, 0.95))
  if (!blob) {
    throw new Error('图片处理失败')
  }
  return new File([blob], buildDerivedName(file.name, effect), {
    type: mimeType,
    lastModified: Date.now(),
  })
}

async function applyImageEffect(effect, options = {}) {
  if (!originalFile.value) {
    setToast('请先选择图片')
    return
  }

  clearTimeout(effectTimer)
  const { silent = false } = options

  if (effect === 'original') {
    selectedFile.value = originalFile.value
    activeImageEffect.value = effect
    updatePreview(originalFile.value)
    if (!silent) {
      setToast('已恢复原图')
    }
    return
  }

  const requestId = ++effectSequence
  applyingEffect.value = true
  activeImageEffect.value = effect

  try {
    const processedFile = await buildEffectFile(originalFile.value, effect)
    if (requestId !== effectSequence) {
      return
    }
    selectedFile.value = processedFile
    updatePreview(processedFile)
    if (!silent) {
      setToast(`已应用${imageEffectLabel(effect)}`)
    }
  } catch (error) {
    if (requestId === effectSequence) {
      setToast(`图片处理失败：${error.message}`)
    }
  } finally {
    if (requestId === effectSequence) {
      applyingEffect.value = false
    }
  }
}

function scheduleActiveEffectRebuild() {
  if (activeImageEffect.value === 'original' || !originalFile.value) {
    return
  }
  clearTimeout(effectTimer)
  effectTimer = setTimeout(() => {
    applyImageEffect(activeImageEffect.value, { silent: true })
  }, 120)
}

function resetActiveEffect() {
  if (activeImageEffect.value === 'original') {
    return
  }
  effectIntensity[activeImageEffect.value] = defaultEffectIntensity[activeImageEffect.value]
  scheduleActiveEffectRebuild()
}

function canCancel(task) {
  return !!task && ['QUEUED', 'PROCESSING', 'CANCEL_REQUESTED'].includes(task.status)
}

function shouldAutoRefresh(task) {
  return !!task && ['QUEUED', 'PROCESSING', 'CANCEL_REQUESTED'].includes(task.status)
}

function syncRefreshTimer() {
  clearInterval(refreshTimer)
  if (!shouldAutoRefresh(currentTask.value)) {
    return
  }
  refreshTimer = setInterval(async () => {
    await refreshAll()
  }, 2000)
}

function handleFileChange(event) {
  const [file] = event.target.files || []
  originalFile.value = file || null
  selectedFile.value = file || null
  activeImageEffect.value = 'original'
  updatePreview(file || null)
}

async function upload(mode) {
  if (!selectedFile.value) {
    setToast('请先选择需要识别的图片')
    return
  }

  if (mode === 'sync') {
    currentTask.value = {
      mode: 'SYNC',
      engineType: selectedEngine.value,
      status: 'PROCESSING',
      imageName: selectedFile.value.name,
      taskNo: '',
      parsedFields: {},
      jsonOutput: '',
      ocrText: '',
    }
  }

  submitting.value = true
  try {
    const formData = new FormData()
    formData.append('file', selectedFile.value)
    formData.append('engineType', selectedEngine.value)
    const response = await fetch(`${apiBase}/${mode}`, {
      method: 'POST',
      body: formData,
    })
    if (!response.ok) {
      throw new Error(await response.text())
    }
    const data = await response.json()
    currentTask.value = data
    setToast(
      mode === 'sync'
        ? `${engineLabel(selectedEngine.value)} 同步识别已完成`
        : `${engineLabel(selectedEngine.value)} 异步识别任务已提交`
    )
    await refreshAll()
    syncRefreshTimer()
  } catch (error) {
    setToast(`识别失败：${error.message}`)
  } finally {
    submitting.value = false
  }
}

async function cancelCurrentTask() {
  if (!currentTask.value || !canCancel(currentTask.value)) {
    return
  }
  cancelling.value = true
  try {
    const response = await fetch(`${apiBase}/tasks/${currentTask.value.taskNo}/cancel`, {
      method: 'POST',
    })
    if (!response.ok) {
      throw new Error(await response.text())
    }
    currentTask.value = await response.json()
    setToast(currentTask.value.status === 'CANCELLED' ? '任务已停止' : '已提交停止请求')
    await refreshAll()
    syncRefreshTimer()
  } catch (error) {
    setToast(`停止任务失败：${error.message}`)
  } finally {
    cancelling.value = false
  }
}

async function loadTask(taskNo) {
  const response = await fetch(`${apiBase}/tasks/${taskNo}`)
  if (!response.ok) {
    throw new Error(await response.text())
  }
  currentTask.value = await response.json()
  syncRefreshTimer()
}

async function refreshTasks() {
  const response = await fetch(`${apiBase}/tasks?limit=20`)
  tasks.value = response.ok ? await response.json() : []
}

async function refreshNotifications() {
  const response = await fetch(`${apiBase}/notifications?limit=20`)
  notifications.value = response.ok ? await response.json() : []
}

async function refreshQueue() {
  const response = await fetch(`${apiBase}/queue`)
  if (response.ok) {
    const data = await response.json()
    queue.queueSize = data.queueSize
    queue.activeWorkers = data.activeWorkers
  }
}

async function refreshAll() {
  await Promise.all([refreshTasks(), refreshNotifications(), refreshQueue()])
  if (currentTask.value?.taskNo) {
    try {
      const response = await fetch(`${apiBase}/tasks/${currentTask.value.taskNo}`)
      if (response.ok) {
        currentTask.value = await response.json()
      }
    } catch (error) {
      console.error(error)
    }
  }
  syncRefreshTimer()
}

function connectSse() {
  eventSource = new EventSource(`${apiBase}/notifications/stream`)
  eventSource.onmessage = async () => {
    await refreshAll()
  }
  eventSource.addEventListener('TASK_COMPLETED', async (event) => {
    const payload = JSON.parse(event.data)
    setToast(`任务 ${payload.taskNo.slice(0, 8)} 已完成`)
    await refreshAll()
  })
  eventSource.addEventListener('TASK_FAILED', async (event) => {
    const payload = JSON.parse(event.data)
    setToast(`任务失败：${payload.errorMessage || payload.message || '未知错误'}`)
    await refreshAll()
  })
  eventSource.addEventListener('TASK_CANCELLED', async (event) => {
    const payload = JSON.parse(event.data)
    setToast(`任务 ${payload.taskNo.slice(0, 8)} 已停止`)
    await refreshAll()
  })
  eventSource.addEventListener('TASK_REQUEUED', async () => {
    await refreshAll()
  })
}

onMounted(async () => {
  await refreshAll()
  connectSse()
})

onBeforeUnmount(() => {
  clearTimeout(toastTimer)
  clearTimeout(effectTimer)
  clearInterval(refreshTimer)
  revokePreviewUrl()
  eventSource?.close()
})
</script>

<template>
  <div class="shell">
    <header class="hero-panel">
      <div>
        <p class="eyebrow">Tsingyun OCR</p>
        <h1>清云OCR控制台</h1>
        <p class="lede">
          支持 PaddleOCR-CPU、Qwen-VL、GLM-OCR、PaddleOCR-VL 多种识别方式对比，统一接入同步识别、异步队列、实时通知、任务取消与 JSON 输出。
        </p>
      </div>
      <div class="stats-grid">
        <article>
          <strong>{{ stats.total }}</strong>
          <span>任务总数</span>
        </article>
        <article>
          <strong>{{ stats.completed }}</strong>
          <span>成功完成</span>
        </article>
        <article>
          <strong>{{ queue.queueSize }}</strong>
          <span>队列积压</span>
        </article>
        <article>
          <strong>{{ queue.activeWorkers }}</strong>
          <span>工作线程</span>
        </article>
      </div>
    </header>

    <main class="workspace two-column-layout">
      <div class="column-stack left-column">
        <section class="panel uploader-panel">
          <div class="panel-heading uploader-heading">
            <h2>上传识别</h2>
            <span v-if="toast" class="toast">{{ toast }}</span>
          </div>

          <div class="engine-toolbar">
            <label class="engine-field">
              <span>识别引擎</span>
              <select v-model="selectedEngine" class="engine-select">
                <option v-for="item in engineOptions" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
            </label>
            <div class="engine-hint">
              <strong>{{ engineLabel(selectedEngine) }}</strong>
              <span>{{ engineOptions.find((item) => item.value === selectedEngine)?.description }}</span>
            </div>
          </div>

          <label class="dropzone">
            <input type="file" accept="image/*" @change="handleFileChange" />
            <div>
              <p class="drop-title">点击或拖拽图片到这里开始识别</p>
              <p class="drop-subtitle">支持 JPG / PNG，单文件大小不超过 20MB</p>
            </div>
          </label>

          <div v-if="previewUrl" class="preview-box">
            <div class="preview-toolbar">
              <div class="preview-status-group">
                <div class="preview-effect-chip">{{ previewStatusLabel }}</div>
                <div v-if="currentEffectOption.adjustable" class="preview-effect-meta">
                  {{ currentEffectOption.sliderLabel }} {{ currentEffectPercent }}%
                </div>
              </div>
              <div class="preview-tools">
                <button
                  v-for="item in imageEffectOptions"
                  :key="item.value"
                  class="preview-tool"
                  :class="{ active: activeImageEffect === item.value }"
                  :disabled="submitting || applyingEffect"
                  @click="applyImageEffect(item.value)"
                >
                  {{ item.label }}
                </button>
              </div>
            </div>

            <div v-if="currentEffectOption.adjustable" class="preview-control-panel">
              <label class="preview-slider-field">
                <span>{{ currentEffectOption.sliderLabel }}</span>
                <input
                  v-model.number="effectIntensity[activeImageEffect]"
                  type="range"
                  min="0"
                  max="100"
                  step="1"
                  :disabled="submitting || applyingEffect"
                  @input="scheduleActiveEffectRebuild"
                />
              </label>
              <strong>{{ currentEffectPercent }}%</strong>
              <button
                class="preview-reset-button"
                :disabled="submitting || applyingEffect"
                @click="resetActiveEffect"
              >
                重置强度
              </button>
            </div>

            <img :src="previewUrl" alt="上传预览" />
          </div>

          <div class="actions">
            <button class="primary" :disabled="submitting" @click="upload('sync')">同步识别</button>
            <button class="secondary" :disabled="submitting" @click="upload('async')">异步入队</button>
          </div>
        </section>

        <section class="panel task-panel limited-panel">
          <div class="panel-heading">
            <h2>任务队列</h2>
            <button class="text-button" @click="refreshAll">刷新</button>
          </div>
          <div class="task-list scroll-panel queue-scroll">
            <button
              v-for="task in tasks"
              :key="task.taskNo"
              class="task-card"
              :class="task.status.toLowerCase()"
              @click="loadTask(task.taskNo)"
            >
              <div>
                <strong>{{ task.imageName }}</strong>
                <span>{{ task.taskNo.slice(0, 12) }}</span>
                <small class="engine-chip">{{ engineLabel(task.engineType) }}</small>
              </div>
              <div>
                <em>{{ task.mode }}</em>
                <b>{{ task.status }}</b>
              </div>
            </button>
            <p v-if="!tasks.length" class="empty">当前还没有任务记录</p>
          </div>
        </section>
      </div>

      <div class="column-stack right-column">
        <section class="panel result-panel limited-panel result-panel-shell">
          <div class="panel-heading">
            <h2>识别结果</h2>
            <div class="result-actions">
              <span>{{ currentTask ? engineLabel(currentTask.engineType) : '请选择任务查看详情' }}</span>
              <button
                v-if="canCancel(currentTask)"
                class="danger-button"
                :disabled="cancelling"
                @click="cancelCurrentTask"
              >
                {{ currentTask?.status === 'PROCESSING' ? '停止任务' : '取消排队' }}
              </button>
            </div>
          </div>

          <div v-if="showSyncResultLoading" class="result-loading-overlay">
            <div class="result-loading-card">
              <span class="loading-spinner"></span>
              <strong>{{ engineLabel(selectedEngine) }} 同步识别中</strong>
              <p>正在解析图片内容，请稍候...</p>
            </div>
          </div>

          <div v-if="currentTask && !showSyncResultLoading" class="result-scroll scroll-panel result-scroll-panel">
            <div class="result-grid ordered-result-grid">
              <article class="result-card fixed-result-card">
                <div class="result-card-head">
                  <h3>任务信息</h3>
                </div>
                <div class="result-card-body info-card-body">
                  <dl class="info-list">
                    <div><dt>任务号</dt><dd class="info-value">{{ currentTask.taskNo || '-' }}</dd></div>
                    <div><dt>识别引擎</dt><dd class="info-value">{{ engineLabel(currentTask.engineType) }}</dd></div>
                    <div><dt>模式</dt><dd class="info-value">{{ currentTask.mode }}</dd></div>
                    <div><dt>状态</dt><dd class="info-value">{{ currentTask.status }}</dd></div>
                    <div><dt>图片名称</dt><dd class="info-value">{{ currentTask.imageName }}</dd></div>
                  </dl>
                </div>
              </article>
              <article class="result-card fixed-result-card">
                <div class="result-card-head">
                  <h3>OCR 原始文本</h3>
                  <button class="copy-button" @click="copyText(currentTask.ocrText || currentTask.errorMessage || '', 'OCR 原始文本')">复制</button>
                </div>
                <div class="result-card-body text-card-body">
                  <pre>{{ currentTask.ocrText || currentTask.errorMessage || '暂无内容' }}</pre>
                </div>
              </article>
              <article class="result-card fixed-result-card">
                <div class="result-card-head">
                  <h3>字段提取</h3>
                </div>
                <div class="result-card-body table-card-body">
                  <table v-if="parsedFieldEntries.length" class="field-table">
                    <thead>
                      <tr>
                        <th>字段</th>
                        <th>值</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="([key, value]) in parsedFieldEntries" :key="key">
                        <td>{{ key }}</td>
                        <td>{{ value || '-' }}</td>
                      </tr>
                    </tbody>
                  </table>
                  <p v-else class="empty-inline">暂无字段结果</p>
                </div>
              </article>
              <article class="result-card fixed-result-card">
                <div class="result-card-head">
                  <h3>JSON 输出</h3>
                  <button class="copy-button" @click="copyText(prettyJsonOutput, 'JSON 输出')">复制</button>
                </div>
                <div class="result-card-body text-card-body">
                  <pre>{{ prettyJsonOutput || '暂无内容' }}</pre>
                </div>
              </article>
            </div>
          </div>
          <p v-else-if="!showSyncResultLoading" class="empty">上传图片后会自动展示最新识别结果，也可以点击左侧任务查看历史记录。</p>
        </section>

        <section class="panel notification-panel limited-panel">
          <div class="panel-heading">
            <h2>实时通知</h2>
            <span>SSE 实时推送</span>
          </div>
          <ul class="notification-list scroll-panel notice-scroll">
            <li
              v-for="item in notifications"
              :key="`${item.taskNo}-${item.createdAt}-${item.eventType}`"
              class="notification-item"
            >
              <strong>{{ item.eventType }}</strong>
              <p>{{ item.message }}</p>
              <span>{{ item.taskNo.slice(0, 12) }}</span>
            </li>
            <li v-if="!notifications.length" class="empty-inline">暂无通知</li>
          </ul>
        </section>
      </div>
    </main>

    <div class="watermark">
      <span class="watermark-label">Powered By</span>
      <span class="watermark-brand">清云智通·武汉研发中心</span>
    </div>
  </div>
</template>