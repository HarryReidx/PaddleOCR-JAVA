<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'

const apiBase = '/api/ocr'
const selectedFile = ref(null)
const previewUrl = ref('')
const submitting = ref(false)
const currentTask = ref(null)
const tasks = ref([])
const notifications = ref([])
const queue = reactive({ queueSize: 0, activeWorkers: 0 })
const toast = ref('')
let eventSource
let toastTimer

const stats = computed(() => ({
  total: tasks.value.length,
  completed: tasks.value.filter((item) => item.status === 'COMPLETED').length,
  queued: tasks.value.filter((item) => item.status === 'QUEUED' || item.status === 'PROCESSING').length,
}))

function setToast(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toast.value = ''
  }, 2800)
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
    setToast('请先选择图片文件')
    return
  }
  submitting.value = true
  try {
    const formData = new FormData()
    formData.append('file', selectedFile.value)
    const response = await fetch(`${apiBase}/${mode}`, {
      method: 'POST',
      body: formData,
    })
    if (!response.ok) {
      throw new Error(await response.text())
    }
    const data = await response.json()
    currentTask.value = data
    setToast(mode === 'sync' ? '同步识别完成' : '异步任务已提交')
    await refreshAll()
  } catch (error) {
    setToast(`提交失败：${error.message}`)
  } finally {
    submitting.value = false
  }
}

async function loadTask(taskNo) {
  const response = await fetch(`${apiBase}/tasks/${taskNo}`)
  if (!response.ok) {
    throw new Error(await response.text())
  }
  currentTask.value = await response.json()
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
}

function connectSse() {
  eventSource = new EventSource(`${apiBase}/notifications/stream`)
  eventSource.onmessage = async () => {
    await refreshAll()
  }
  eventSource.addEventListener('TASK_COMPLETED', async (event) => {
    const payload = JSON.parse(event.data)
    setToast(`任务 ${payload.taskNo.slice(0, 8)} 已完成`)
    if (currentTask.value?.taskNo === payload.taskNo) {
      await loadTask(payload.taskNo)
    }
  })
  eventSource.addEventListener('TASK_FAILED', async (event) => {
    const payload = JSON.parse(event.data)
    setToast(`任务失败：${payload.message}`)
    if (currentTask.value?.taskNo === payload.taskNo) {
      await loadTask(payload.taskNo)
    }
  })
}

onMounted(async () => {
  await refreshAll()
  connectSse()
})

onBeforeUnmount(() => {
  clearTimeout(toastTimer)
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
        <h1>清云证件识别联调控制台</h1>
        <p class="lede">
          支持身份证、驾驶证、营业执照、护照等证件图片上传，提供同步阻塞识别、异步队列处理和实时通知回流。
        </p>
      </div>
      <div class="stats-grid">
        <article>
          <strong>{{ stats.total }}</strong>
          <span>最近任务</span>
        </article>
        <article>
          <strong>{{ stats.completed }}</strong>
          <span>已完成</span>
        </article>
        <article>
          <strong>{{ queue.queueSize }}</strong>
          <span>队列长度</span>
        </article>
        <article>
          <strong>{{ queue.activeWorkers }}</strong>
          <span>运行线程</span>
        </article>
      </div>
    </header>

    <main class="workspace">
      <section class="panel uploader-panel">
        <div class="panel-heading">
          <h2>上传识别</h2>
          <span v-if="toast" class="toast">{{ toast }}</span>
        </div>
        <label class="dropzone">
          <input type="file" accept="image/*" @change="handleFileChange" />
          <div>
            <p class="drop-title">拖入证件图片或点击选择</p>
            <p class="drop-subtitle">推荐 JPG / PNG，文件大小 20MB 以内</p>
          </div>
        </label>
        <div class="preview-box" v-if="previewUrl">
          <img :src="previewUrl" alt="预览图片" />
        </div>
        <div class="actions">
          <button class="primary" :disabled="submitting" @click="upload('sync')">同步识别</button>
          <button class="secondary" :disabled="submitting" @click="upload('async')">异步入队</button>
        </div>
      </section>

      <section class="panel task-panel">
        <div class="panel-heading">
          <h2>任务队列</h2>
          <button class="text-button" @click="refreshAll">刷新</button>
        </div>
        <div class="task-list">
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
            </div>
            <div>
              <em>{{ task.mode }}</em>
              <b>{{ task.status }}</b>
            </div>
          </button>
          <p v-if="!tasks.length" class="empty">还没有识别任务。</p>
        </div>
      </section>

      <section class="panel result-panel">
        <div class="panel-heading">
          <h2>识别结果</h2>
          <span>{{ currentTask?.documentType || '等待任务' }}</span>
        </div>
        <div v-if="currentTask" class="result-grid">
          <article>
            <h3>任务信息</h3>
            <dl>
              <div><dt>任务号</dt><dd>{{ currentTask.taskNo }}</dd></div>
              <div><dt>模式</dt><dd>{{ currentTask.mode }}</dd></div>
              <div><dt>状态</dt><dd>{{ currentTask.status }}</dd></div>
              <div><dt>文件</dt><dd>{{ currentTask.imageName }}</dd></div>
            </dl>
          </article>
          <article>
            <h3>结构化字段</h3>
            <dl>
              <div v-for="(value, key) in currentTask.parsedFields || {}" :key="key">
                <dt>{{ key }}</dt>
                <dd>{{ value || '-' }}</dd>
              </div>
            </dl>
          </article>
          <article class="text-block full-width">
            <h3>OCR 原始文本</h3>
            <pre>{{ currentTask.ocrText || currentTask.errorMessage || '暂无结果' }}</pre>
          </article>
        </div>
        <p v-else class="empty">选择一条任务即可查看详细识别结果。</p>
      </section>

      <section class="panel notification-panel">
        <div class="panel-heading">
          <h2>实时通知</h2>
          <span>SSE 已接入</span>
        </div>
        <ul class="notification-list">
          <li v-for="item in notifications" :key="`${item.taskNo}-${item.createdAt}-${item.eventType}`">
            <strong>{{ item.eventType }}</strong>
            <p>{{ item.message }}</p>
            <span>{{ item.taskNo.slice(0, 12) }}</span>
          </li>
          <li v-if="!notifications.length" class="empty-inline">暂无通知。</li>
        </ul>
      </section>
    </main>

    <div class="watermark">
      <span class="watermark-label">Powered By</span>
      <span class="watermark-brand">清云智通·武汉研发中心</span>
    </div>
  </div>
</template>
