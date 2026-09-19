<script setup>
// ② 仓库概览：结构化摘要。
//
// 这一页的立场写在界面上：**上面的结构是算出来的，下面的语义是模型补的**。
// 每个模块、每个枢纽都带 symbolId，点一下就能看代码 —— 摘要里的名词不许是"听起来很对"的那种名词。
import { ref, watch, onMounted } from 'vue'
import { api, formatNumber, formatPercent } from '../api.js'
import EvidenceDrawer from '../components/EvidenceDrawer.vue'

const props = defineProps({ repoId: Number })

const summary = ref(null)
const loading = ref(false)
const error = ref('')
const drawer = ref(null)
const regenerating = ref(false)

async function load(refresh = false) {
  loading.value = true
  error.value = ''
  if (refresh) regenerating.value = true
  try {
    summary.value = await api.summary(props.repoId, true, refresh)
  } catch (e) {
    error.value = e.message
    if (refresh) summary.value = null
  } finally {
    loading.value = false
    regenerating.value = false
  }
}

function openSymbol(ref_, why) {
  if (!ref_) return
  drawer.value = {
    file: ref_.filePath,
    startLine: ref_.startLine,
    endLine: ref_.endLine,
    title: `${ref_.kind} ${ref_.qualifiedName}`,
    why: why,
    symbolId: ref_.symbolId
  }
}

function openLocation(file, startLine, endLine, title) {
  drawer.value = { file, startLine, endLine, title, why: '' }
}

watch(() => props.repoId, () => load(false), { immediate: true })
onMounted(() => load(false))
</script>

<template>
  <div v-if="loading" class="panel muted"><span class="spinner"></span>正在从索引里算摘要…</div>
  <div v-else-if="error" class="error">{{ error }}</div>

  <template v-else-if="summary">
    <div class="panel">
      <h2>{{ summary.repoName }} 的结构化摘要</h2>
      <p class="hint mono">{{ summary.rootPath }}
        <span v-if="summary.commitHash"> · 提交 {{ summary.commitHash.slice(0, 10) }}</span>
        <span v-if="summary.indexedAt"> · 索引于 {{ summary.indexedAt.replace('T', ' ') }}</span>
      </p>

      <div class="grid">
        <div class="stat"><div class="k">文件</div><div class="v">{{ formatNumber(summary.structure.scale.fileCount) }}</div>
          <div class="s">成功解析 {{ formatNumber(summary.structure.scale.parsedOkCount) }} 个</div></div>
        <div class="stat"><div class="k">代码行数</div><div class="v">{{ formatNumber(summary.structure.scale.totalLoc) }}</div></div>
        <div class="stat"><div class="k">符号</div><div class="v">{{ formatNumber(summary.structure.scale.totalSymbols) }}</div>
          <div class="s">类 {{ summary.structure.scale.classCount }} · 接口 {{ summary.structure.scale.interfaceCount }} · 方法 {{ formatNumber(summary.structure.scale.methodCount) }}</div></div>
        <div class="stat"><div class="k">调用边</div><div class="v">{{ formatNumber(summary.structure.scale.callEdges) }}</div>
          <div class="s">已解析 {{ formatNumber(summary.structure.scale.resolvedCallEdges) }}</div></div>
        <div class="stat"><div class="k">解析成功率</div><div class="v">{{ formatPercent(summary.structure.scale.parseSuccessRate) }}</div>
          <div class="s">{{ summary.structure.scale.parseSuccessRate < 1 ? '有文件没解析成功，它们不在下面的结论里' : '全部文件解析成功' }}</div></div>
        <div class="stat"><div class="k">调用解析率</div><div class="v">{{ formatPercent(summary.structure.scale.callResolveRate) }}</div>
          <div class="s">{{ summary.structure.scale.callResolveRate < 1 ? '未解析的调用（外部依赖/盲区）不计入调用图' : '全部调用都已解析' }}</div></div>
      </div>

      <h3>模块划分（划分前缀：{{ summary.structure.modulePrefix || '—' }}）</h3>
      <p class="hint">
        模块 = 公共包前缀之后的第一段包名。数字全部来自索引；点代表类可以看代码。
      </p>
      <table>
        <thead>
          <tr><th>模块</th><th class="num">文件</th><th class="num">行数</th><th class="num">符号</th><th>代表类</th></tr>
        </thead>
        <tbody>
          <tr v-for="module in summary.structure.modules" :key="module.name">
            <td><strong>{{ module.name }}</strong><div class="small muted mono">{{ module.packagePrefix }}</div></td>
            <td class="num">{{ module.fileCount }}</td>
            <td class="num">{{ formatNumber(module.totalLoc) }}</td>
            <td class="num">{{ formatNumber(module.symbolCount) }}</td>
            <td>
              <span v-for="type in module.keyTypes" :key="type.symbolId" class="chip"
                    style="margin: 0 4px 4px 0"
                    @click="openSymbol(type, `模块 ${module.name} 的代表类`)">{{ type.qualifiedName.split('.').pop() }}</span>
              <div class="small muted mono" v-if="module.samplePaths.length" style="margin-top: 2px">
                例如：{{ module.samplePaths[0] }}
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="panel">
      <h3 style="margin-top: 0">入口（含 static main 的类型）</h3>
      <p class="hint">
        静态能认出来的入口只有这一种。Servlet、Filter、Spring 组件这些要读注解或外部配置才知道是入口 ——
        所以这是「至少这些」，不是「只有这些」。
      </p>
      <div class="row" v-if="summary.structure.entryPoints.length">
        <span v-for="entry in summary.structure.entryPoints" :key="entry.symbolId" class="chip"
              @click="openSymbol(entry, '入口：含 static main')">{{ entry.qualifiedName }}</span>
      </div>
      <p v-else class="muted small">这个仓库里没有 static main 方法（可能是个库）。</p>

      <h3>调用枢纽（被调用最多的类）</h3>
      <table>
        <thead><tr><th>类型</th><th>位置</th><th class="num">被调用</th></tr></thead>
        <tbody>
          <tr v-for="hub in summary.structure.callHubs" :key="hub.symbol.symbolId">
            <td><span class="chip" @click="openSymbol(hub.symbol, '调用枢纽')">{{ hub.symbol.qualifiedName }}</span></td>
            <td class="mono small">{{ hub.symbol.filePath }}:{{ hub.symbol.startLine }}</td>
            <td class="num">{{ hub.count }}</td>
          </tr>
        </tbody>
      </table>

      <h3>被调用最多的方法</h3>
      <table>
        <thead><tr><th>方法</th><th>位置</th><th class="num">被调用</th></tr></thead>
        <tbody>
          <tr v-for="method in summary.structure.topMethods" :key="method.symbol.symbolId">
            <td><span class="chip" @click="openSymbol(method.symbol, '被调用最多的方法')">{{ method.symbol.qualifiedName }}</span></td>
            <td class="mono small">{{ method.symbol.filePath }}:{{ method.symbol.startLine }}</td>
            <td class="num">{{ method.count }}</td>
          </tr>
        </tbody>
      </table>

      <h3>实现关系（实现类最多的接口）</h3>
      <table v-if="summary.structure.implementations.length">
        <thead><tr><th>接口 / 父类</th><th>位置</th><th class="num">实现 / 子类</th></tr></thead>
        <tbody>
          <tr v-for="impl in summary.structure.implementations" :key="impl.symbol.symbolId">
            <td><span class="chip" @click="openSymbol(impl.symbol, '接口')">{{ impl.symbol.qualifiedName }}</span></td>
            <td class="mono small">{{ impl.symbol.filePath }}:{{ impl.symbol.startLine }}</td>
            <td class="num">{{ impl.count }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted small">没有解析出「接口 + 实现类」的组合（可能是外部接口，或调用图未解析）。</p>

      <h3>没有任何调用者的类</h3>
      <p class="hint">
        只陈述事实，不做判定：这类类可能是入口、可能是框架反射回调的、也可能是真的死代码 ——
        静态分析**分不出这三种**，所以别把它当死代码清单。
      </p>
      <div class="row" v-if="summary.structure.uncalledClasses.length">
        <span v-for="cls in summary.structure.uncalledClasses" :key="cls.symbolId" class="chip plain"
              style="cursor: pointer" @click="openSymbol(cls, '没有任何调用者')">{{ cls.qualifiedName }}</span>
      </div>
      <p v-else class="muted small">每个类的方法都至少被调用过一次。</p>
    </div>

    <div class="panel">
      <h3 style="margin-top: 0">每个模块大致负责什么（模型补的）</h3>
      <template v-if="summary.semantics.available">
        <div class="row" style="margin-bottom: 8px">
          <span v-if="summary.semantics.cached" class="badge info">
            这份说明来自缓存（生成于 {{ (summary.semantics.generatedAt || '').replace('T', ' ').slice(0, 19) }}，本次没有调用模型）
          </span>
          <span v-else class="badge ok">
            本次新生成 · 花了 {{ summary.semantics.totalTokens || (summary.semantics.promptTokens + summary.semantics.completionTokens) }} token
          </span>
          <button class="ghost" :disabled="regenerating" @click="load(true)">
            <span v-if="regenerating" class="spinner"></span>{{ regenerating ? '正在重新生成…' : '重新生成' }}
          </button>
        </div>
        <p class="hint">
          模型：<span class="mono">{{ summary.semantics.model }}</span> ·
          这句话里提到的符号名都拿去索引里精确查过：**索引里找不到的名字**和它自己写的数字会被标出来
          （找不到只说明"索引里没有这个名字"，可能是外部概念、也可能是它编的 —— 由你判断）。
        </p>
        <table>
          <thead><tr><th>模块</th><th>一句话</th><th>核对</th></tr></thead>
          <tbody>
            <tr v-for="note in summary.semantics.notes" :key="note.module">
              <td class="mono">{{ note.module }}</td>
              <td>{{ note.note }}</td>
              <td>
                <span v-if="note.verified" class="badge ok">名字都在索引里</span>
                <span v-else class="badge warn">
                  <template v-if="note.unverifiedSymbols.length">索引里没有：{{ note.unverifiedSymbols.join('、') }}</template>
                  <template v-if="note.unverifiedSymbols.length && note.numbersInNote.length"> · </template>
                  <template v-if="note.numbersInNote.length">它自己写的数字：{{ note.numbersInNote.join('、') }}</template>
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
      <p v-else class="notice">
        语义说明不可用：{{ summary.semantics.reason }}
        <br /><span class="small">结构部分不受影响 —— 它由索引算出，不需要模型。</span>
      </p>
    </div>

    <EvidenceDrawer
      v-if="drawer"
      :repo-id="repoId"
      :file="drawer.file"
      :start-line="drawer.startLine"
      :end-line="drawer.endLine"
      :title="drawer.title"
      :why="drawer.why"
      @close="drawer = null"
    />
  </template>
</template>
