// 后端接口的薄封装：只做三件事 —— 拼 URL、统一解 { code, message, data }、把错误原样抛出来。
// 不做缓存、不做重试：这个前端要展示的正是"后端此刻算出来的东西"。

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
  indexLocal: (path) => request('/api/repos', { method: 'POST', body: JSON.stringify({ path }) }),
  indexRemote: (gitUrl) => request('/api/repos', { method: 'POST', body: JSON.stringify({ gitUrl }) }),
  uploadArchive: (file) => {
    const form = new FormData()
    form.append('file', file)
    return request('/api/repos/upload', { method: 'POST', body: form })
  },
  deleteRepo: (id) => request(`/api/repos/${id}`, { method: 'DELETE' }),

  summary: (repoId, semantics = true) => request(`/api/summary?repoId=${repoId}&semantics=${semantics}`),

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
    request('/api/eval/run', { method: 'POST', body: JSON.stringify({ repoId, seed, perType }) })
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
