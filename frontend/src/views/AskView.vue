<script setup>
// ③ 追问：问答 + 证据卡片 + 多跳轨迹。
//
// 界面上刻意分成三块（对应设计文档里"答案分三块展示"的要求）：
//   结论 —— 模型说了什么
//   证据 —— 每一条都能点开看磁盘上的真实代码
//   过程 —— 这次是怎么答出来的（走的哪条路、跳了几跳、花了多少 token）
// 第三块是"Agent"与"套壳问答"的区别所在：过程可见，才能判断该不该信。
import { ref, nextTick } from 'vue'
import { api, formatMs, formatNumber } from '../api.js'
import EvidenceDrawer from '../components/EvidenceDrawer.vue'

const props = defineProps({ repoId: Number })

const question = ref('')
const mode = ref('multi')
// 深链模式：只影响多跳那条路（额度由服务端配置给，这里只选要不要）
const deep = ref(false)
const busy = ref(false)
const error = ref('')
const thread = ref([])
const drawer = ref(null)
const bottom = ref(null)

const samples = [
  '这个项目是干什么的？',
  '谁调用了 deleteAddressBook 方法？',
  '沿调用链向上追：有哪些方法（直接或间接）最终会调用 fromJson？',
  '上传文件的接口在哪个类里？'
]

async function ask() {
  const text = question.value.trim()
  if (!text) return
  busy.value = true
  error.value = ''
  const entry = { question: text, mode: mode.value, deep: deep.value, answer: null, error: null, at: new Date() }
  thread.value.push(entry)
  question.value = ''
  await nextTick()
  bottom.value?.scrollIntoView({ behavior: 'smooth' })
  try {
    entry.answer = mode.value === 'multi'
      ? await api.ask(props.repoId, text, 'multi', deep.value)
      : await api.askSingleHop(props.repoId, text)
  } catch (e) {
    entry.error = e.message
  } finally {
    busy.value = false
    await nextTick()
    bottom.value?.scrollIntoView({ behavior: 'smooth' })
  }
}

// 徽章**以响应里的 mode 为准**，不是用户选的那个 —— 选中"多跳"但问题被判定成总结类时，
// 界面上必须显示"摘要"，否则使用者会以为它偷偷走了别的路（它确实走了）
function modeBadge(entry) {
  const actual = entry.answer && entry.answer.mode
  if (actual === 'SUMMARY') return { text: '摘要', cls: 'ok' }
  if (actual === 'SINGLE_HOP') return { text: '单跳', cls: 'plain' }
  if (actual === 'MULTI_HOP') return { text: '多跳', cls: 'info' }
  return entry.mode === 'multi' ? { text: '多跳', cls: 'info' } : { text: '单跳', cls: 'plain' }
}

function openEvidence(evidence) {
  drawer.value = {
    file: evidence.file,
    startLine: evidence.startLine,
    endLine: evidence.endLine,
    title: '证据',
    why: evidence.why
  }
}

// ③ 层核验的结果（这段代码**支持**这条结论吗）。
// 老接口的响应里没有这一项，所以给一个兜底 —— 界面不能因为少一个字段就报错。
function support(entry) {
  return (entry.answer && entry.answer.verification && entry.answer.verification.support) || {
    status: 'NOT_CHECKED',
    reason: '',
    promptTokens: 0,
    completionTokens: 0
  }
}

function answerOf(entry) {
  return entry.answer
}

function isRefusal(entry) {
  const a = answerOf(entry)
  return a && (a.refused === true)
}
</script>

<template>
  <div class="panel">
    <h2>追问</h2>
    <p class="hint">
      每个问题**独立作答**（不会把它自己上一轮的猜测当事实），所以追问记录是可以逐条核对的。
      「多跳」模式下模型会自己决定查谁、跳几跳、什么时候停，但被轮次/token 预算与环检测框住。
    </p>

    <div class="row" style="margin-bottom: 10px">
      <div class="tabs" style="margin: 0">
        <button :class="{ active: mode === 'multi' }" @click="mode = 'multi'">多跳（Agent）</button>
        <button :class="{ active: mode === 'single' }" @click="mode = 'single'">单跳（对照）</button>
      </div>
      <span class="small muted">
        {{ mode === 'multi' ? '模型自主决定跳向；确定性问题与总结类问题仍然不走多跳' : '一次检索 + 模型组织答案；链式问题只能答一层' }}
      </span>
      <label v-if="mode === 'multi'" class="small muted"
             style="display: flex; align-items: center; gap: 5px; cursor: pointer">
        <input type="checkbox" v-model="deep" style="width: auto; margin: 0" />
        深链模式（最多 14 轮，追更长的调用链；更慢）
      </label>
    </div>

    <textarea v-model="question" rows="2" placeholder="例如：这个参数是从哪传进来的？"
              @keydown.ctrl.enter="ask" @keydown.meta.enter="ask"></textarea>
    <div class="row" style="margin-top: 8px">
      <button class="primary" :disabled="busy || !question.trim()" @click="ask">
        <span v-if="busy" class="spinner"></span>{{ busy ? '正在查…' : '提问' }}
      </button>
      <span class="small muted">Ctrl/⌘ + Enter 提交</span>
      <span class="small muted">试试：</span>
      <button v-for="sample in samples" :key="sample" class="link" @click="question = sample">
        {{ sample.length > 22 ? sample.slice(0, 22) + '…' : sample }}
      </button>
    </div>
  </div>

  <div v-if="!thread.length" class="panel muted">
    还没有提问。答案会带【文件 + 行号】证据，点开就是磁盘上的真实代码；编造的行号过不了核验，会被挡下来。
  </div>

  <div v-for="(entry, index) in thread" :key="index" class="panel qa">
    <div class="q">Q{{ thread.length - index }}. {{ entry.question }}
      <span class="badge" :class="modeBadge(entry).cls">{{ modeBadge(entry).text }}</span>
      <span v-if="entry.deep && entry.answer && entry.answer.mode === 'MULTI_HOP'" class="badge warn">深链</span>
    </div>

    <div v-if="entry.error" class="error">{{ entry.error }}</div>
    <div v-else-if="!entry.answer" class="muted small"><span class="spinner"></span>正在检索并核验…</div>

    <template v-else>
      <p v-if="entry.answer.refused" class="notice">
        <strong>没有给出结论</strong>：{{ entry.answer.reason }}
      </p>
      <div v-else class="answer">
        <p style="margin: 0 0 6px; white-space: pre-wrap">{{ entry.answer.answer }}</p>
      </div>

      <p v-if="entry.answer.mode === 'SUMMARY'" class="notice info small">
        这次<strong>没有走检索</strong>：总结类问题由「结构化摘要」直接作答 ——
        规模、模块划分、入口与调用枢纽都由索引算出（下面的证据每条都能点开核对），
        「它做什么 / 主要功能」由模型依据这些材料组织（它提到的符号已回索引核对）。
      </p>
      <p v-if="entry.answer.stopReason === 'BUDGET_ROUNDS'" class="notice info small">
        轮次用尽就停下来了 —— 轨迹里查到的都交出来了，只是模型没来得及给结论。
        想追更长的链，勾上上面的<strong>深链模式</strong>（最多 14 轮）再问一次。
      </p>

      <!-- ③ 层核验：前两层只能证明"这几行真实存在"，证明不了"这几行说的就是结论说的那件事" -->
      <p v-if="support(entry).status === 'UNSUPPORTED'" class="notice">
        <strong>这些证据可能不支持这条结论</strong>（③ 层核验）：{{ support(entry).reason }}<br />
        <span class="small">
          证据通过了磁盘核验（文件、行号、片段都对得上），但核验判定它与结论不是同一件事 ——
          这类错位是程序化校验拦不住的那一类，请点开证据自己看一眼。
        </span>
      </p>
      <p v-else-if="support(entry).status === 'UNAVAILABLE'" class="notice info small">
        ③ 层核验没做成（{{ support(entry).reason }}）—— 这只说明**这次没核验**，不代表结论有问题。
      </p>
      <p v-else-if="support(entry).status === 'UNCERTAIN'" class="notice info small">
        ③ 层核验判定材料不足以判断结论是否被支持：{{ support(entry).reason }}
      </p>

      <template v-if="entry.answer.evidence && entry.answer.evidence.length">
        <h3>证据（{{ entry.answer.evidence.length }} 条，全部通过磁盘核验）</h3>
        <div v-for="(evidence, i) in entry.answer.evidence" :key="i" class="evidence">
          <div class="head">
            <span class="mono">{{ evidence.file }}:{{ evidence.startLine }}-{{ evidence.endLine }}</span>
            <span class="why">{{ evidence.why }}</span>
            <button class="link" @click="openEvidence(evidence)">看代码</button>
          </div>
        </div>
      </template>

      <h3>过程</h3>
      <p v-if="entry.answer.cached" class="notice info small" style="margin-bottom: 8px">
        这条答案**来自缓存**：同一个问题（同一份索引）之前问过，直接取上次的结果 ——
        本次没有调用模型、没有花时间与 token（当初生成花了
        {{ (entry.answer.promptTokens || 0) + (entry.answer.completionTokens || 0) }} token）。
      </p>
      <p class="meta small">
        <span v-if="entry.answer.cached" class="badge warn">缓存命中</span>
        <span class="badge" :class="entry.answer.answeredBy === 'STATIC' ? 'ok' : 'info'">
          {{ entry.answer.answeredBy === 'STATIC' ? '调用图/符号表直接算出（未经模型）' : '由模型组织' }}
        </span>
        <span class="badge plain">模式 {{ entry.answer.mode }}</span>
        <span class="badge plain">终止：{{ entry.answer.stopReason }}</span>
        <span v-if="support(entry).status === 'SUPPORTED'" class="badge ok">③ 层核验：证据支持结论</span>
        <span v-else-if="support(entry).status === 'UNSUPPORTED'" class="badge err">③ 层核验：证据不支持结论</span>
        <span v-else-if="support(entry).status === 'UNCERTAIN'" class="badge warn">③ 层核验：判不了</span>
        <span v-else-if="support(entry).status === 'UNAVAILABLE'" class="badge warn">③ 层核验未完成</span>
        <span v-if="entry.answer.rounds">轮次 {{ entry.answer.rounds }}</span>
        <span v-if="entry.answer.toolCalls">跳数 {{ entry.answer.toolCalls }}</span>
        <span v-if="entry.answer.repeatedCalls">重复调用被拦 {{ entry.answer.repeatedCalls }} 次</span>
        <span>token {{ formatNumber(entry.answer.promptTokens + entry.answer.completionTokens) }}</span>
        <span v-if="support(entry).promptTokens + support(entry).completionTokens">
          核验另花 {{ formatNumber(support(entry).promptTokens + support(entry).completionTokens) }} token
        </span>
        <span>耗时 {{ formatMs(entry.answer.latencyMs) }}</span>
        <span v-if="entry.answer.estimatedCost">估算成本 ¥{{ entry.answer.estimatedCost.toFixed(4) }}</span>
      </p>
      <p v-if="entry.answer.verification && entry.answer.verification.mismatch"
         class="small muted">
        首轮未通过核验 {{ entry.answer.verification.mismatch }} 条，定向修正 {{ entry.answer.verification.repairs.length }} 处
        （修正后重新核验才采纳）。
      </p>

      <div v-if="entry.answer.steps && entry.answer.steps.length" class="trail">
        <div v-for="step in entry.answer.steps" :key="step.hop" class="step">
          <div>
            <span class="mono tool">第 {{ step.hop }} 跳 · {{ step.tool }}({{ step.args }})</span>
            <span v-if="step.repeated" class="badge warn">重复调用，被环检测拦下</span>
            <span v-else class="badge plain">{{ step.subjects ? step.subjects.length : 0 }} 个结果 · {{ formatMs(step.latencyMs) }}</span>
          </div>
          <div v-if="step.thought" class="thought">思路：{{ step.thought }}</div>
          <details>
            <summary class="small muted" style="cursor: pointer">看模型看到的原始观察</summary>
            <pre>{{ step.observation }}</pre>
          </details>
        </div>
      </div>
    </template>
  </div>

  <div ref="bottom"></div>

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
