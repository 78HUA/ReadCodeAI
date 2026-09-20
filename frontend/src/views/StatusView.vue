<script setup>
// ⑤ 运行状态：把「现在是什么配置、哪些能力降级了」一眼说清。
//
// 为什么只读：凭据（API Key / 数据库口令）属于**部署者**，而这个服务没有登录体系 ——
// 一个能改配置的页面，等于把凭据暴露给任何能打开它的人。
// 所以这里只显示状态；要改就改环境变量再重启（README 的配置表写了每一项）。
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const status = ref(null)
const error = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    status.value = await api.status()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

function okCount() {
  return status.value ? status.value.components.filter((c) => c.ok).length : 0
}
</script>

<template>
  <div class="panel">
    <div class="row" style="align-items: center; gap: 10px">
      <h3 style="margin: 0">运行状态</h3>
      <span v-if="status" class="badge ok">{{ okCount() }} / {{ status.components.length }} 项可用</span>
      <button class="link" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
    </div>
    <p class="small muted" style="margin: 8px 0 0">
      只读：凭据与启动参数只从环境变量读。要改配置请改环境变量后重启（见 README「配置项」）。
      <strong>降级是设计的正常状态</strong>——没配模型就没有语义问答，没起 Redis 就没有缓存与锁，
      功能还在，只是少一块能力。
    </p>
  </div>

  <div v-if="error" class="error">{{ error }}</div>

  <template v-if="status">
    <div class="panel">
      <h3 style="margin-top: 0">运行环境</h3>
      <p class="meta small">
        <span>Java {{ status.jvm.javaVersion }}</span>
        <span>处理器 {{ status.jvm.processors }} 核</span>
        <span>堆上限 {{ status.jvm.maxHeapMb }} MB</span>
        <span>当前已用 {{ status.jvm.usedHeapMb }} MB</span>
      </p>
    </div>

    <div class="panel">
      <h3 style="margin-top: 0">能力清单（每项都能降级）</h3>
      <div v-for="(c, i) in status.components" :key="i" class="evidence">
        <div class="head">
          <span class="badge" :class="c.ok ? 'ok' : 'warn'">{{ c.ok ? '可用' : '降级 / 不可用' }}</span>
          <span style="font-weight: 600">{{ c.name }}</span>
        </div>
        <p class="small muted" style="margin: 6px 0 0; white-space: pre-wrap">{{ c.detail }}</p>
      </div>
    </div>
  </template>
</template>
