<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'

const apiBase = '/api/ocr'
const selectedFile = ref(null)
const previewUrl = ref('')
const selectedEngine = ref('paddleocr-cpu')
const submitting = ref(false)
const cancelling = ref(false)
const currentTask = ref(null)
const tasks = ref([])
const notifications = ref([])
const queue = reactive({ queueSize: 0, activeWorkers: 0 })
const toast = ref('')
let eventSource
let toastTimer
let refreshTimer

const engineOptions = [
  { value: 'paddleocr-cpu', label: 'PaddleOCR-CPU', description: '' },
  { value: 'qwen-vl', label: 'Qwen-VL', description: '' },
  { value: 'glm-ocr', label: 'GLM-OCR', description: '' },
  { value: 'paddleocr-vl', label: 'PaddleOCR-VL', description: '' },
]

const engineLabelMap = Object.fromEntries(engineOptions.map((item) => [item.value, item.label]))

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

function engineLabel(engineType) {
  return engineLabelMap[engineType] || engineType || '-'
}

function setToast(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toast.value = ''
  }, 2800)
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
  selectedFile.value = file || null
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
  }
  previewUrl.value = file ? URL.createObjectURL(file) : ''
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
    setToast(mode === 'sync' ? `${engineLabel(selectedEngine.value)} 同步识别已完成` : `${engineLabel(selectedEngine.value)} 异步识别任务已提交`)
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
  clearInterval(refreshTimer)
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
  }
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
                  <dl>
                    <div><dt>任务号</dt><dd>{{ currentTask.taskNo || '-' }}</dd></div>
                    <div><dt>识别引擎</dt><dd>{{ engineLabel(currentTask.engineType) }}</dd></div>
                    <div><dt>模式</dt><dd>{{ currentTask.mode }}</dd></div>
                    <div><dt>状态</dt><dd>{{ currentTask.status }}</dd></div>
                    <div><dt>图片名称</dt><dd>{{ currentTask.imageName }}</dd></div>
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
            <li v-for="item in notifications" :key="`${item.taskNo}-${item.createdAt}-${item.eventType}`" class="notification-item">
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
