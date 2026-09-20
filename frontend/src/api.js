// 后端接口的薄封装：只做三件事 —— 拼 URL、统一解 { code, message, data }、把错误原样抛出来。
// 不做缓存、不做重试：这个前端要展示的正是"后端此刻算出来的东西"。

// 会话内备忘录：只活在当前页面里（刷新即清）。它的作用只有一个 —— 别让来回切页签重复触发模型调用。
// 后端那一层的缓存键是「仓库 + 索引版本 + 模型名」，比这个严格得多，两者不冲突。
const summaryMemo = new Map()

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' },
    ...options
  })
  const text = await response.text()
  let body
  try {
    body = text ? JSON.parse(text) : {}
  } catch {
    throw new Error(`${response.status} ${text.slice(0, 200)}`)
  }
  if (!response.ok || (body.code !== undefined && body.code !== 0)) {
    // 后端的错误消息是中文、且是给人看的（例如"未配置 LLM…确定性能力不受影响"），原样往上抛
    throw new Error(body.message || `HTTP ${response.status}`)
  }
  return body.data
}

export const api = {
  repos: () => request('/api/repos'),
  // 索引是异步的：提交拿到的是任务（立刻返回），进度用 indexJob 轮询
  indexJob: (id) => request(`/api/index-jobs/${id}`),
  indexQueue: () => request('/api/index-jobs/queue'),
  status: () => request('/api/status'),
  indexLocal: (path) => request('/api/repos', { method: 'POST', body: JSON.stringify({ path }) }),
  indexRemote: (gitUrl) => request('/api/repos', { method: 'POST', body: JSON.stringify({ gitUrl }) }),
  uploadArchive: (file) => {
    const form = new FormData()
    form.append('file', file)
    return request('/api/repos/upload', { method: 'POST', body: form })
  },
  deleteRepo: (id) => request(`/api/repos/${id}`, { method: 'DELETE' }),

  // 摘要：第一次真算，之后同一仓库在**本次会话内**直接复用（切页签不再发请求）。
  // refresh=true 绕过本地备忘录并请后端重新生成（"重新生成"按钮用它）。
  summary: async (repoId, semantics = true, refresh = false) => {
    const key = `${repoId}/${semantics}`
    if (!refresh && summaryMemo.has(key)) return summaryMemo.get(key)
    const data = await request(`/api/summary?repoId=${repoId}&semantics=${semantics}&refresh=${refresh}`)
    summaryMemo.set(key, data)
    return data
  },

  ask: (repoId, question, mode) =>
    request('/api/agent', { method: 'POST', body: JSON.stringify({ repoId, question, mode }) }),
  askSingleHop: (repoId, question, scopePath) =>
    request('/api/ask', { method: 'POST', body: JSON.stringify({ repoId, question, scopePath }) }),

  fileContent: (repoId, path, startLine, endLine) =>
    request(`/api/files/content?repoId=${repoId}&path=${encodeURIComponent(path)}`
      + (startLine ? `&startLine=${startLine}` : '') + (endLine ? `&endLine=${endLine}` : '')),

  symbol: (id) => request(`/api/symbols/${id}`),
  callers: (id) => request(`/api/symbols/${id}/callers`),
  callees: (id) => request(`/api/symbols/${id}/callees`),
  implementations: (id) => request(`/api/symbols/${id}/implementations`),
  search: (repoId, q) => request(`/api/search?repoId=${repoId}&q=${encodeURIComponent(q)}`),

  runEval: (repoId, seed, perType) =>
    request('/api/eval/run', { method: 'POST', body: JSON.stringify({ repoId, seed, perType }) }),

  // 运行统计：问答流水的累计（真实使用，不是自动评估）。不传 repoId = 全部仓库
  metrics: (repoId) => request(`/api/metrics${repoId ? `?repoId=${repoId}` : ''}`)
}

export function formatMs(ms) {
  if (ms === null || ms === undefined) return '-'
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

export function formatNumber(value) {
  if (value === null || value === undefined) return '-'
  return value.toLocaleString('zh-CN')
}

// Java 那边这些比率是 record 的**方法**，不会出现在 JSON 里 —— 前端自己算（分母为 0 时给 null）
export function rate(numerator, denominator) {
  if (!denominator) return null
  return numerator / denominator
}

export function formatPercent(rate) {
  if (rate === null || rate === undefined) return '-'
  return `${(rate * 100).toFixed(1)}%`
}
