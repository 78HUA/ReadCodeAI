<script setup>
// 证据 Drawer：点开一条证据，看**磁盘上此刻的真实内容**（不是索引里的副本）。
//
// 这一步是产品可信度的来源：答案里的每条证据都是「文件 + 起止行」，
// 点开必须能看见那几行现在到底是什么；如果文件在索引之后被改过，这里会直接提示行号可能漂移。
import { ref, watch } from 'vue'
import { api } from '../api.js'

const props = defineProps({
  repoId: Number,
  file: String,
  startLine: Number,
  endLine: Number,
  title: String,
  why: String
})
const emit = defineEmits(['close'])

const content = ref(null)
const error = ref('')
const loading = ref(false)
const related = ref(null)
const symbolId = ref(null)

async function load() {
  if (!props.file) return
  loading.value = true
  error.value = ''
  content.value = null
  try {
    content.value = await api.fileContent(props.repoId, props.file, props.startLine, props.endLine)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function loadRelated(id) {
  symbolId.value = id
  related.value = null
  try {
    const [symbol, callers, callees, implementations] = await Promise.all([
      api.symbol(id), api.callers(id), api.callees(id), api.implementations(id)
    ])
    related.value = { symbol, callers, callees, implementations }
  } catch (e) {
    error.value = e.message
  }
}

watch(() => [props.file, props.startLine, props.endLine, props.repoId], load, { immediate: true })
defineExpose({ loadRelated })
</script>

<template>
  <div class="drawer-backdrop" @click.self="emit('close')">
    <div class="drawer">
      <div class="row" style="justify-content: space-between; align-items: flex-start">
        <div>
          <h3>{{ title || '证据' }}</h3>
          <div class="mono small muted">{{ file }}:{{ startLine }}-{{ endLine }}</div>
          <div v-if="why" class="small muted" style="margin-top: 4px">{{ why }}</div>
        </div>
        <button class="ghost" @click="emit('close')">关闭</button>
      </div>

      <div v-if="error" class="error" style="margin-top: 14px">{{ error }}</div>
      <div v-else-if="loading" class="muted small" style="margin-top: 14px"><span class="spinner"></span>读取磁盘上的文件…</div>

      <template v-else-if="content">
        <div class="row" style="margin-top: 14px; gap: 8px">
          <span class="badge ok">来自磁盘：{{ content.totalLines }} 行</span>
          <span v-if="content.changedSinceIndex" class="badge warn">⚠ 该文件在索引之后被改过，行号可能已漂移</span>
          <span v-else class="badge info">内容与索引时一致</span>
        </div>
        <pre class="code" style="margin-top: 10px"><span
          v-for="line in content.lines"
          :key="line.number"
          :class="{ hl: startLine && line.number >= startLine && line.number <= endLine }"><span class="ln">{{ line.number }}</span>{{ line.text }}
</span></pre>

        <h3>这个符号的调用关系（来自调用图）</h3>
        <div class="row">
          <span class="muted small">点一下符号即可查看它的调用者与被调用者</span>
        </div>
        <div v-if="related" style="margin-top: 10px">
          <div class="small"><strong>{{ related.symbol.qualifiedName }}</strong> · {{ related.symbol.kind }} · {{ related.symbol.filePath }}:{{ related.symbol.startLine }}-{{ related.symbol.endLine }}</div>
          <h3>谁调用了它（{{ related.callers.length }}）</h3>
          <ul class="small mono" style="margin: 4px 0 0; padding-left: 18px">
            <li v-for="call in related.callers.slice(0, 20)" :key="call.callSiteLocation()">
              {{ call.symbolQualifiedName }} @ {{ call.callSiteFile }}:{{ call.callLine }}
            </li>
            <li v-if="!related.callers.length" class="muted">（没有任何调用点）</li>
          </ul>
          <h3>它调用了谁（已解析 {{ related.callees.filter(c => c.resolved).length }} 个）</h3>
          <ul class="small mono" style="margin: 4px 0 0; padding-left: 18px">
            <li v-for="call in related.callees.filter(c => c.resolved).slice(0, 20)" :key="call.symbolQualifiedName + call.callLine">
              {{ call.symbolQualifiedName }} @ {{ call.callSiteFile }}:{{ call.callLine }}
            </li>
            <li v-if="!related.callees.filter(c => c.resolved).length" class="muted">（没有解析出仓库内调用）</li>
          </ul>
          <p v-if="related.callees.some(c => !c.resolved)" class="small muted">
            另有 {{ related.callees.filter(c => !c.resolved).length }} 处未解析（外部依赖或静态分析盲区，不代表没有调用）。
          </p>
        </div>
      </template>
    </div>
  </div>
</template>
