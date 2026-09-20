<script setup>
// ④ 指标：**两块账，分开说**。
//
// 上面一块（运行统计）是真实问答的流水：每问一次记一行（路线、token、耗时、拒答、证据核验）。
// 下面一块（自动评估）是机器给自己打的卷：题从索引出、答案也从索引答。
//
// 这两块必须分开显示 —— 把"管线自洽的命中率"和"真实使用的成本账"混成一个数字，
// 就是把两件不同的事说成一件。
import { onMounted, ref, watch } from 'vue'
import { api, formatMs, formatNumber, formatPercent, rate } from '../api.js'

const props = defineProps({ repoId: Number })

const perType = ref(20)
const seed = ref(20260918)
const report = ref(null)
const busy = ref(false)
const error = ref('')

// 运行统计（一进页面就加载，不依赖"跑一次评估"）
const stats = ref(null)
const statsError = ref('')
const statsBusy = ref(false)

async function loadStats() {
  statsBusy.value = true
  statsError.value = ''
  try {
    stats.value = await api.metrics(props.repoId)
  } catch (e) {
    statsError.value = e.message
  } finally {
    statsBusy.value = false
  }
}

onMounted(loadStats)
watch(() => props.repoId, loadStats)

async function run() {
  busy.value = true
  error.value = ''
  try {
    report.value = await api.runEval(props.repoId, Number(seed.value), Number(perType.value))
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

const modeLabels = {
  STATIC: '静态（查表算出，不经模型）',
  SINGLE_HOP: '单跳（一次检索 + 模型）',
  MULTI_HOP: '多跳（模型自主跳转）'
}

const typeLabels = {
  LOCATE: '定位题（在哪定义）',
  CALLERS: '调用者题（谁调用了它）',
  CALLEES: '被调用题（它调用了谁）',
  STRUCTURE: '结构题（有哪些成员）',
  IMPLEMENTS: '实现题（有哪些实现类）'
}
</script>

<template>
  <div class="panel">
    <h2>运行统计</h2>
    <p class="hint">
      这一块与下面的自动评估<strong>不是一回事</strong>：自动评估是机器给自己打的卷（题目与判卷都由程序来）；
      这里的数字来自<strong>真实问答的流水</strong> —— 每问一次记一行：走了哪条路、花了多少 token、
      多久、有没有拒答、证据核验结果。没答上来的问题也照样记账。
    </p>
    <div v-if="statsError" class="error" style="margin-top: 12px">{{ statsError }}</div>
    <template v-if="stats">
      <div class="grid">
        <div class="stat"><div class="k">累计问答</div><div class="v">{{ formatNumber(stats.userQuestions) }}</div>
          <div class="s">其中 {{ stats.cacheHits }} 次命中缓存<template v-if="stats.evalQuestions"> ·
            另有评估跑题 {{ stats.evalQuestions }} 次（不计入）</template></div></div>
        <div class="stat"><div class="k">拒答率</div>
          <div class="v">{{ formatPercent(rate(stats.refusals, stats.userQuestions)) }}</div>
          <div class="s">{{ stats.refusals }} / {{ stats.userQuestions }}</div></div>
        <div class="stat"><div class="k">实际花费 token</div>
          <div class="v">{{ formatNumber(stats.promptTokens + stats.completionTokens + stats.supportTokens) }}</div>
          <div class="s">含 ③ 层核验 {{ formatNumber(stats.supportTokens) }}</div></div>
        <div class="stat"><div class="k">估算成本</div><div class="v">{{ stats.cost.toFixed(4) }}</div>
          <div class="s">按配置单价，免费档恒为 0</div></div>
        <div class="stat"><div class="k">缓存省下</div><div class="v">{{ formatNumber(stats.savedTokens) }}</div>
          <div class="s">token（没有再生成一遍）</div></div>
        <div class="stat"><div class="k">平均耗时</div><div class="v">{{ formatMs(stats.avgLatencyMs) }}</div>
          <div class="s">最慢 {{ formatMs(stats.maxLatencyMs) }}</div></div>
        <div class="stat"><div class="k">证据采纳</div><div class="v">{{ formatNumber(stats.evidenceVerified) }}</div>
          <div class="s">首轮被拦下 {{ stats.evidenceRejected }} 条</div></div>
      </div>
      <h3>按路线</h3>
      <table>
        <thead>
          <tr><th>路线</th><th class="num">次数</th><th class="num">拒答</th><th class="num">平均耗时</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in stats.byMode" :key="row.mode">
            <td>{{ modeLabels[row.mode] || row.mode }}</td>
            <td class="num">{{ row.count }}</td>
            <td class="num">{{ row.refusals }}</td>
            <td class="num">{{ formatMs(row.avgLatencyMs) }}</td>
          </tr>
          <tr v-if="!stats.byMode.length"><td colspan="4" class="small muted">还没有问答记录</td></tr>
        </tbody>
      </table>
      <p class="small muted">
        口径：统计范围是库里现存的流水（删仓库会连它的问答记录一起删），且<strong>只算用户提问</strong> ——
        评估集跑题单独计数；缓存命中那一次没有再花 token，所以单独算在「缓存省下」里；
        ③ 层核验的用量与生成分开记。
      </p>
    </template>
  </div>

  <div class="panel">
    <h2>自动评估</h2>
    <p class="hint">
      题目自动生成、答案由**静态分析**算出、判卷也是程序做的 —— 不花 token、几秒跑完、同一 seed 完全可复现。
    </p>
    <div class="row">
      <label class="small muted">每种题型
        <input type="number" min="1" max="200" style="width: 90px" v-model="perType" />
      </label>
      <label class="small muted">随机种子
        <input type="number" style="width: 130px" v-model="seed" />
      </label>
      <button class="primary" :disabled="busy" @click="run">
        <span v-if="busy" class="spinner"></span>{{ busy ? '出题并判卷…' : '跑一次评估' }}
      </button>
    </div>
    <div v-if="error" class="error" style="margin-top: 12px">{{ error }}</div>
  </div>

  <template v-if="report">
    <div class="panel">
      <div class="grid">
        <div class="stat"><div class="k">题目总数</div><div class="v">{{ report.total }}</div></div>
        <div class="stat"><div class="k">给出答案</div><div class="v">{{ report.answered }}</div></div>
        <div class="stat"><div class="k">拒答</div><div class="v">{{ report.refused }}</div></div>
        <div class="stat"><div class="k">失败</div><div class="v">{{ report.failed }}</div></div>
        <div class="stat"><div class="k">整体命中</div>
          <div class="v">{{ ((rate(report.hit, report.total) ?? 0) * 100).toFixed(1) }}%</div>
          <div class="s">{{ report.hit }} / {{ report.total }}</div></div>
        <div class="stat"><div class="k">LLM 调用</div><div class="v">0</div>
          <div class="s">这些问题不该叫模型</div></div>
      </div>
      <h3>分题型</h3>
      <table>
        <thead>
          <tr><th>题型</th><th class="num">题数</th><th class="num">命中</th><th class="num">命中率</th>
          <th class="num">平均 F1</th><th class="num">平均耗时</th></tr>
        </thead>
        <tbody>
          <tr v-for="(metrics, type) in report.byType" :key="type">
            <td>{{ typeLabels[type] || type }}</td>
            <td class="num">{{ metrics.total }}</td>
            <td class="num">{{ metrics.hit }}</td>
            <td class="num">{{ (metrics.hitRate * 100).toFixed(1) }}%</td>
            <td class="num">{{ metrics.avgF1.toFixed(3) }}</td>
            <td class="num">{{ Math.round(metrics.avgLatencyMs) }} ms</td>
          </tr>
        </tbody>
      </table>
      <p class="small muted">seed={{ report.seed }} · 生成器 {{ report.generatorVersion }} · repoId={{ report.repoId }}</p>
    </div>

    <div class="panel">
      <div class="notice">
        <strong>这个数字必须打星号</strong>：题从索引出、答案也从索引答 —— 同源，
        所以高命中率是**按构造**的，它衡量的是"确定性管线有没有丢信息、有没有走错路"，
        <strong>不是开放问答的准确率</strong>。
      </div>
      <p class="hint">
        开放问答的准确率由人工判定的抽查来量（见仓库里的验证记录），两边分开报；
        把两者混成一个"准确率"才是真正会骗人的做法。
        摘要类问题没有唯一正确答案，因此**不进这套自动评估** —— 这也是它只能标"可核对 / 未核对"的原因。
      </p>
      <details v-if="report.items && report.items.length">
        <summary class="small muted" style="cursor: pointer">看每条题目的作答与判分（前 50 条）</summary>
        <table style="margin-top: 10px">
          <thead><tr><th>题型</th><th>题面</th><th>结果</th></tr></thead>
          <tbody>
            <tr v-for="(item, i) in report.items.slice(0, 50)" :key="i">
              <td class="small">{{ item.question.type }}</td>
              <td class="small">{{ item.question.questionText }}</td>
              <td class="small">
                <span v-if="item.failed" class="badge err">异常：{{ item.answerExcerpt }}</span>
                <span v-else-if="item.refused" class="badge warn">拒答：{{ item.answerExcerpt }}</span>
                <span v-else-if="item.grade && item.grade.hit" class="badge ok">命中</span>
                <span v-else class="badge err">{{ item.grade ? item.grade.note : '' }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </details>
    </div>
  </template>
</template>
