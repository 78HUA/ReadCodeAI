<script setup>
// ① 添加仓库：三个入口（GitHub 链接 / 服务器本地路径 / 上传压缩包）+ 已索引列表。
//
// 索引是**同步**的（小仓库几秒、中等的几十秒），所以按钮上要明确告诉用户在等什么 ——
// 这一步不能装作"秒回"，否则用户会在等待里以为界面卡死了。
import { ref } from 'vue'
import { api, formatNumber, formatPercent, formatMs, rate } from '../api.js'

const props = defineProps({ repos: Array, currentRepoId: Number })
const emit = defineEmits(['refresh', 'select'])

const mode = ref('git')
const gitUrl = ref('')
const localPath = ref('')
const archive = ref(null)
const busy = ref(false)
const message = ref('')
const error = ref('')

async function submit() {
  error.value = ''
  message.value = ''
  busy.value = true
  try {
    let summary
    if (mode.value === 'git') {
      if (!gitUrl.value.trim()) throw new Error('请填一个 GitHub 链接')
      message.value = '正在从 GitHub 拉取源码包并索引…（公开仓库不需要 token）'
      summary = await api.indexRemote(gitUrl.value.trim())
    } else if (mode.value === 'path') {
      if (!localPath.value.trim()) throw new Error('请填服务器上的仓库路径')
      message.value = '正在扫描本地路径并索引…'
      summary = await api.indexLocal(localPath.value.trim())
    } else {
      if (!archive.value) throw new Error('请选择一个 .zip 压缩包')
      message.value = '正在上传并解压索引…'
      summary = await api.uploadArchive(archive.value)
    }
    message.value = `完成：${formatNumber(summary.fileCount)} 个文件 · ${formatNumber(summary.symbolCount)} 个符号 · `
      + `解析成功率 ${formatPercent(rate(summary.parsedOkCount, summary.fileCount))} · 耗时 ${formatMs(summary.totalMillis)}`
    gitUrl.value = ''
    localPath.value = ''
    archive.value = null
    emit('refresh')
  } catch (e) {
    error.value = e.message
    message.value = ''
  } finally {
    busy.value = false
  }
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
            <button class="link" @click="emit('select', repo.id, 'summary')">看概览</button>
            <button class="link" @click="emit('select', repo.id, 'ask')">去提问</button>
            <button class="link danger" @click="remove(repo)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-else class="muted">还没有索引任何仓库。</p>
  </div>
</template>
