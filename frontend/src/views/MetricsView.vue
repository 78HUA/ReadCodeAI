<script setup>
// ④ 指标：一键跑评估集。
//
// 这一页存在的意义不是"数字好看"，而是**把评估的边界写在数字旁边**：
// 自动出题与自动判卷用的都是确定性问题（答案由静态分析算出），
// 所以它测的是管线自洽与回归，不是开放问答的准确率 —— 这句话必须和 100% 一起出现，
// 否则那个 100% 就是在骗人。
import { ref } from 'vue'
import { api, rate } from '../api.js'

const props = defineProps({ repoId: Number })

const perType = ref(20)
const seed = ref(20260918)
const report = ref(null)
const busy = ref(false)
const error = ref('')

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
