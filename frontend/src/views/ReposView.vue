<script setup>
// ① 添加仓库：三个入口（GitHub 链接 / 服务器本地路径 / 上传压缩包）+ 已索引列表。
//
// 索引是**同步**的（小仓库几秒、中等的几十秒），所以按钮上要明确告诉用户在等什么 ——
// 这一步不能装作"秒回"，否则用户会在等待里以为界面卡死了。
import { ref } from 'vue'
import { api, formatNumber, formatPercent, formatMs, rate } from '../api.js'
import { onUnmounted } from 'vue'

const props = defineProps({ repos: Array, currentRepoId: Number })
const emit = defineEmits(['refresh', 'select'])

const mode = ref('git')
const gitUrl = ref('')
const localPath = ref('')
const archive = ref(null)
const busy = ref(false)
const message = ref('')
const error = ref('')
const progress = ref(null)      // {stage, done, total, percent, message}
let timer = null

const STAGE_LABEL = {
  QUEUED: '排队中', FETCHING: '拉取源码包', EXTRACTING: '解压压缩包',
  SCANNING: '扫描源文件', PARSING: '解析源码', STORING: '写入索引',
  DONE: '完成', FAILED: '失败'
}

function describe(job) {
  const stage = STAGE_LABEL[job.stage] || job.stage || job.status
  if (job.total > 0) {
    return `${stage} ${job.done}/${job.total}（${job.percent}%）`
  }
  return `${stage}${job.message ? '：' + job.message : ''}`
}

/**
 * 提交索引任务 —— **接口立刻返回，进度靠轮询**。
 * 这是异步化的意义所在：索引是分钟级的长任务，同步做会把请求挂住、界面只能干等。
 */
async function submit() {
  error.value = ''
  message.value = ''
  progress.value = null
  busy.value = true
  try {
    let job
    if (mode.value === 'git') {
      if (!gitUrl.value.trim()) throw new Error('请填一个 GitHub 链接')
      job = await api.indexRemote(gitUrl.value.trim())
    } else if (mode.value === 'path') {
      if (!localPath.value.trim()) throw new Error('请填服务器上的仓库路径')
      job = await api.indexLocal(localPath.value.trim())
    } else {
      if (!archive.value) throw new Error('请选择一个 .zip 压缩包')
      job = await api.uploadArchive(archive.value)
    }
    message.value = `已提交索引任务 #${job.id}（接口立刻返回，后台在跑）`
    gitUrl.value = ''
    localPath.value = ''
    archive.value = null
    poll(job.id)
  } catch (e) {
    error.value = e.message
    message.value = ''
    busy.value = false
  }
}

function poll(jobId) {
  clearInterval(timer)
  timer = setInterval(async () => {
    try {
      const job = await api.indexJob(jobId)
      progress.value = job
      if (job.status === 'READY' || job.status === 'FAILED') {
        clearInterval(timer)
        busy.value = false
        if (job.status === 'READY') {
          message.value = `完成：${job.message}`
        } else {
          error.value = `索引失败：${job.message}`
          message.value = ''
        }
        emit('refresh')
      }
    } catch (e) {
      clearInterval(timer)
      busy.value = false
      error.value = e.message
    }
  }, 800)
}

async function remove(repo) {
  if (!confirm(`删除「${repo.name}」的索引？（只删索引，磁盘上的代码不动）`)) return
  try {
    await api.deleteRepo(repo.id)
    emit('refresh')
  } catch (e) {
    error.value = e.message
  }
}

function onFile(event) {
  archive.value = event.target.files[0] || null
}

onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="panel">
    <h2>添加仓库</h2>
    <p class="hint">
      三个入口任选：贴 GitHub 链接（公开仓库免 token）、填服务器上的路径（零上传成本）、
      或者直接传一个 zip（部署在远端、拿不到服务器文件系统时用）。
    </p>

    <div class="tabs">
      <button :class="{ active: mode === 'git' }" @click="mode = 'git'">GitHub 链接</button>
      <button :class="{ active: mode === 'path' }" @click="mode = 'path'">服务器本地路径</button>
      <button :class="{ active: mode === 'zip' }" @click="mode = 'zip'">上传压缩包</button>
    </div>

    <div class="row">
      <template v-if="mode === 'git'">
        <input type="text" style="min-width: 460px" v-model="gitUrl"
               placeholder="https://github.com/owner/repo" @keyup.enter="submit" />
      </template>
      <template v-else-if="mode === 'path'">
        <input type="text" style="min-width: 460px" v-model="localPath"
               placeholder="E:\\GitHub\\my-project（服务器上的绝对路径）" @keyup.enter="submit" />
      </template>
      <template v-else>
        <input type="file" accept=".zip" @change="onFile" />
      </template>
      <button class="primary" :disabled="busy" @click="submit">
        <span v-if="busy" class="spinner"></span>{{ busy ? '索引中…' : '开始索引' }}
      </button>
    </div>

    <p v-if="message" class="notice info" style="margin-top: 12px">{{ message }}</p>
    <p v-if="progress" class="notice" style="margin-top: 12px">
      <span class="spinner" v-if="progress.status === 'RUNNING' || progress.status === 'QUEUED'"></span>
      {{ describe(progress) }}
      <span class="small muted">（任务 #{{ progress.id }} · 状态 {{ progress.status }}）</span>
    </p>
    <p v-if="error" class="error" style="margin-top: 12px">{{ error }}</p>
  </div>

  <div class="panel">
    <h2>已索引仓库</h2>
    <p class="hint">列表里的解析成功率与调用解析率是索引质量的直接指标 —— 它们偏低时，下面所有结论的覆盖面都会打折（未解析的调用不会凭空补上，只会如实计为缺口）。</p>
    <table v-if="repos.length">
      <thead>
        <tr>
          <th>仓库</th><th>来源</th>
          <th class="num">文件</th><th class="num">行数</th><th class="num">符号</th>
          <th class="num">解析成功率</th><th class="num">调用解析率</th>
          <th>状态</th><th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="repo in repos" :key="repo.id">
          <td>
            <strong>{{ repo.name }}</strong>
            <div class="small muted mono">{{ repo.commitHash ? repo.commitHash.slice(0, 10) : '（无提交号）' }}</div>
          </td>
          <td class="mono small">{{ repo.rootPath }}</td>
          <td class="num">{{ formatNumber(repo.fileCount) }}</td>
          <td class="num">{{ formatNumber(repo.totalLoc) }}</td>
          <td class="num">{{ formatNumber(repo.symbolCount) }}</td>
          <td class="num">{{ formatPercent(rate(repo.parsedOkCount, repo.fileCount)) }}</td>
          <td class="num">{{ formatPercent(rate(repo.callResolvedCount, repo.callEdgeCount)) }}</td>
          <td>
            <span class="badge" :class="repo.status === 'READY' ? 'ok' : 'warn'">{{ repo.status }}</span>
          </td>
          <td class="row" style="gap: 2px">
            <button class="link" :disabled="repo.status !== 'READY'" @click="emit('select', repo.id, 'summary')">看概览</button>
            <button class="link" :disabled="repo.status !== 'READY'" @click="emit('select', repo.id, 'ask')">去提问</button>
            <button class="link danger" @click="remove(repo)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-else class="muted">还没有索引任何仓库。</p>
  </div>
</template>
