<script setup>
// 顶层壳：四个页面 + 当前选中的仓库。
// 刻意不用 vue-router —— 页面之间只有"选中的仓库"这一个共享状态，
// 为它引一个路由库（再教一遍 URL 与状态同步）不划算。
import { ref, computed, onMounted } from 'vue'
import { api, rate } from './api.js'
import ReposView from './views/ReposView.vue'
import SummaryView from './views/SummaryView.vue'
import AskView from './views/AskView.vue'
import MetricsView from './views/MetricsView.vue'
import StatusView from './views/StatusView.vue'

const tabs = [
  { key: 'repos', label: '① 仓库' },
  { key: 'summary', label: '② 概览' },
  { key: 'ask', label: '③ 追问' },
  { key: 'metrics', label: '④ 指标' },
  { key: 'status', label: '⑤ 运行状态' }
]

const view = ref('repos')
const repos = ref([])
const currentRepoId = ref(null)
const error = ref('')

const currentRepo = computed(() => repos.value.find((r) => r.id === currentRepoId.value) || null)

async function loadRepos(preferId) {
  try {
    repos.value = await api.repos()
    const wanted = preferId ?? currentRepoId.value
    if (wanted && repos.value.some((r) => r.id === wanted)) {
      currentRepoId.value = wanted
    } else if (repos.value.length) {
      currentRepoId.value = repos.value[0].id
    } else {
      currentRepoId.value = null
    }
  } catch (e) {
    error.value = e.message
  }
}

function selectRepo(id, nextView) {
  currentRepoId.value = id
  if (nextView) view.value = nextView
}

onMounted(loadRepos)
</script>

<template>
  <div class="app">
    <div class="topbar">
      <div class="brand">ReadCodeAI<span>代码库理解 Agent · 每条结论都带可核对的证据</span></div>
      <div class="nav">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          :class="{ active: view === tab.key }"
          @click="view = tab.key"
        >{{ tab.label }}</button>
      </div>
    </div>

    <div v-if="error" class="error">{{ error }}</div>

    <div class="panel" v-if="view !== 'repos' && repos.length">
      <div class="row">
        <span class="muted small">当前仓库</span>
        <select :value="currentRepoId" @change="currentRepoId = Number($event.target.value)">
          <option v-for="repo in repos" :key="repo.id" :value="repo.id">
            {{ repo.name }}（{{ repo.symbolCount }} 符号 · 调用解析率 {{ ((rate(repo.callResolvedCount, repo.callEdgeCount) ?? 0) * 100).toFixed(0) }}%）
          </option>
        </select>
        <span class="badge" :class="currentRepo && currentRepo.status === 'READY' ? 'ok' : 'warn'">
          {{ currentRepo ? currentRepo.status : '' }}
        </span>
      </div>
    </div>

    <ReposView
      v-if="view === 'repos'"
      :repos="repos"
      :current-repo-id="currentRepoId"
      @refresh="loadRepos"
      @select="selectRepo"
    />
    <SummaryView v-else-if="view === 'summary' && currentRepoId" :repo-id="currentRepoId" />
    <AskView v-else-if="view === 'ask' && currentRepoId" :repo-id="currentRepoId" />
    <MetricsView v-else-if="view === 'metrics' && currentRepoId" :repo-id="currentRepoId" />
    <!-- 状态页不依赖"选中仓库"：它报的是服务本身的配置与降级情况，没索引仓库时也该能看 -->
    <StatusView v-else-if="view === 'status'" />
    <div v-else class="panel muted">还没有索引任何仓库 —— 先在上面「① 仓库」里添加一个。</div>
  </div>
</template>
