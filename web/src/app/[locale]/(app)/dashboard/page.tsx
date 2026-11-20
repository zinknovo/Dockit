'use client'

import { useState, useEffect, useCallback } from 'react'
import { http } from '@/lib/http'

type Tab = 'ai' | 'files' | 'docs' | 'knowledge'

interface ToolResult {
  error?: string
  info?: string
  result?: string
  keywords?: string[]
  [key: string]: unknown
}

interface UploadResult {
  error?: string
  originalFilename?: string
  fileSize?: number
  objectName?: string
}

interface DocItem {
  id: string
  title?: string
  category?: string
  version?: number
  status?: string
  summary?: string
}

interface DocVersion {
  version?: number | string
  versionNumber?: number | string
  createTime?: string
  createdAt?: string
}

export default function ToolkitPage() {
  const [tab, setTab] = useState<Tab>('ai')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<ToolResult | null>(null)
  const [msg, setMsg] = useState('')

  // AI 处理
  const [aiMode, setAiMode] = useState('summarize')
  const [aiInput, setAiInput] = useState('')
  const [fileName, setFileName] = useState('')
  const aiModes = [
    { key: 'summarize', label: '摘要', hint: 'AI 生成内容摘要' },
    { key: 'keywords', label: '关键词', hint: '提取核心关键词' },
    { key: 'analyze', label: '分析', hint: '深度分析文档内容' }
  ]

  // 文件
  const [fileMsg, setFileMsg] = useState('')
  const [uploadResult, setUploadResult] = useState<UploadResult | null>(null)
  const [downloadId, setDownloadId] = useState('')

  // 文档
  const [docs, setDocs] = useState<DocItem[]>([])
  const [docSearch, setDocSearch] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState({ title: '', fileId: '', category: '', tags: '' })
  const [detail, setDetail] = useState<DocItem | null>(null)
  const [versions, setVersions] = useState<DocVersion[]>([])

  // 知识库
  const [kgMode, setKgMode] = useState<'qa' | 'search' | 'index'>('qa')
  const [kgQuery, setKgQuery] = useState('')
  const [kgQuestion, setKgQuestion] = useState('')
  const [kgDocId, setKgDocId] = useState('')
  const [kgContent, setKgContent] = useState('')

  // ====== AI ======
  const handleFile = useCallback(async (file: File) => {
    setFileName(file.name); setAiInput('')
    if (file.type === 'text/plain' || file.name.endsWith('.txt') || file.name.endsWith('.md')) {
      setAiInput(await file.text())
      return
    }
    setLoading(true)
    try {
      const fd = new FormData(); fd.append('file', file)
      const res = await http.post<UploadResult>({ url: '/api/ai/upload', data: fd, headers: { 'Content-Type': 'multipart/form-data' } })
      setAiInput(`[已上传: ${res.originalFilename ?? '-'}, ${((res.fileSize ?? 0)/1024).toFixed(1)}KB]`)
      setResult({ info: '文件上传成功，粘贴文本内容后点分析' })
    } catch (err) { setResult({ error: err instanceof Error ? err.message : '请求失败' }) }
    finally { setLoading(false) }
  }, [])

  const aiSubmit = async () => {
    if (!aiInput.trim()) return
    setLoading(true); setResult(null)
    try {
      const eps: Record<string, string> = { summarize: '/api/ai/summarize', keywords: '/api/ai/keywords', analyze: '/api/ai/analyze' }
      const data = await http.post<ToolResult>({ url: eps[aiMode], data: { content: aiInput, maxLength: aiMode === 'summarize' ? 300 : undefined } })
      setResult(data)
    } catch (err) { setResult({ error: err instanceof Error ? err.message : '请求失败' }) }
    finally { setLoading(false) }
  }

  // ====== 文件 ======
  const fileUpload = async (file: File) => {
    setFileMsg(''); setUploadResult(null); setLoading(true)
    try {
      const fd = new FormData(); fd.append('file', file)
      const data = await http.post<UploadResult>({ url: '/api/ai/upload', data: fd, headers: { 'Content-Type': 'multipart/form-data' } })
      setUploadResult(data)
      if (data.objectName) setDownloadId(data.objectName)
    } catch (err) { setUploadResult({ error: err instanceof Error ? err.message : '上传失败' }) }
    finally { setLoading(false) }
  }

  const fileDownload = async () => {
    if (!downloadId.trim()) return
    try {
      const data = await http.get<{ fileUrl?: string }>({ url: `/api/ai/download/url?objectName=${encodeURIComponent(downloadId)}` })
      if (data.fileUrl) window.open(data.fileUrl, '_blank')
      else setFileMsg('未获取到下载地址')
    } catch (err) { setFileMsg('下载失败: ' + (err instanceof Error ? err.message : '未知错误')) }
  }

  // ====== 文档 ======
  const loadDocs = useCallback(async () => {
    setLoading(true)
    try {
      const params: { pageNum: number; pageSize: number; keyword?: string } = { pageNum: 1, pageSize: 20 }
      if (docSearch.trim()) params.keyword = docSearch.trim()
      const data = await http.get<{ records?: DocItem[]; lists?: DocItem[] } | DocItem[]>({ url: '/api/documents/search', params })
      setDocs(Array.isArray(data) ? data : data.records || data.lists || [])
    } catch { setDocs([]) }
    finally { setLoading(false) }
  }, [docSearch])

  useEffect(() => { if (tab === 'docs') void loadDocs() }, [loadDocs, tab])

  const docCreate = async () => {
    if (!form.title.trim()) return
    setMsg('')
    try {
      await http.post({ url: '/api/documents', data: { ...form, tags: form.tags ? form.tags.split(',').map(t => t.trim()) : [] } })
      setShowCreate(false); setForm({ title: '', fileId: '', category: '', tags: '' }); setMsg('创建成功'); await loadDocs()
    } catch (err) { setMsg(err instanceof Error ? err.message : '操作失败') }
  }

  const docDelete = async (id: string) => {
    if (!confirm('确认删除？')) return
    try { await http.del({ url: `/api/documents/${id}` }); await loadDocs() }
    catch (err) { setMsg(err instanceof Error ? err.message : '操作失败') }
  }

  const docDetail = async (id: string) => {
    try { const d = await http.get<DocItem>({ url: `/api/documents/${id}` }); setDetail(d); setVersions([]) }
    catch (err) { setMsg(err instanceof Error ? err.message : '操作失败') }
  }

  const docVersions = async (id: string) => {
    try {
      const d = await http.get<{ versions?: DocVersion[] } | DocVersion[]>({ url: `/api/documents/${id}/versions` })
      setVersions(Array.isArray(d) ? d : d.versions || [])
      setDetail(null)
    }
    catch (err) { setMsg(err instanceof Error ? err.message : '操作失败') }
  }

  // ====== 知识库 ======
  // 后端用 @RequestBody String 收原文；不声明 text/plain 的话，http 拦截器会先 JSON.stringify，引号和 \n 转义会被原样存进去
  const plainText = { 'Content-Type': 'text/plain;charset=UTF-8' }

  const kgQA = async () => {
    if (!kgQuestion.trim()) return
    setLoading(true); setResult(null)
    try { setResult(await http.post<ToolResult>({ url: '/api/ai/rag/query', data: kgQuestion, headers: plainText })) }
    catch (err) { setResult({ error: err instanceof Error ? err.message : '请求失败' }) }
    finally { setLoading(false) }
  }

  const kgSearch = async () => {
    if (!kgQuery.trim()) return
    setLoading(true); setResult(null)
    try { setResult(await http.get<ToolResult>({ url: '/api/ai/rag/search', params: { query: kgQuery, topK: 5 } })) }
    catch (err) { setResult({ error: err instanceof Error ? err.message : '请求失败' }) }
    finally { setLoading(false) }
  }

  const kgIndex = async () => {
    if (!kgDocId.trim() || !kgContent.trim()) return
    setLoading(true); setMsg('')
    try { await http.post({ url: '/api/ai/rag/index', params: { documentId: kgDocId }, data: kgContent, headers: plainText }); setMsg('索引成功') }
    catch (err) { setMsg('索引失败: ' + (err instanceof Error ? err.message : '未知错误')) }
    finally { setLoading(false) }
  }

  // ====== 公共组件 ======
  const DragZone = ({ onFile, label }: { onFile: (f: File) => void; label: string }) => {
    const [over, setOver] = useState(false)
    return (
      <div
        onDragOver={e => { e.preventDefault(); setOver(true) }}
        onDragLeave={() => setOver(false)}
        onDrop={e => { e.preventDefault(); setOver(false); const f = e.dataTransfer.files[0]; if (f) onFile(f) }}
        onClick={() => document.getElementById('drop-input')?.click()}
        className={`rounded-xl border-2 border-dashed p-4 text-center cursor-pointer transition ${over ? 'border-theme bg-theme-5' : 'border-border hover-border-g-400'}`}
      >
        <input id="drop-input" type="file" className="d-none" onChange={e => { const f = e.target.files?.[0]; if (f) onFile(f) }} />
        <p className="text-sm text-g-600">{label}</p>
      </div>
    )
  }

  const ResultBox = () => {
    if (!result) return null
    return (
      <div className="rounded-xl border border-border bg-box p-4 mt-3">
        <p className="text-xs fw-medium text-g-900 mb-1">结果</p>
        {result.error
          ? <p className="text-sm text-danger">{result.error}</p>
          : <pre className="text-sm text-g-700 text-pre-wrap wrap-break-word">{result.result || result.keywords?.join('、') || JSON.stringify(result, null, 2)}</pre>}
      </div>
    )
  }

  const tabs: { key: Tab; label: string }[] = [
    { key: 'ai', label: 'AI 处理' },
    { key: 'files', label: '文件' },
    { key: 'docs', label: '文档' },
    { key: 'knowledge', label: '知识库' }
  ]

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl fw-semibold text-g-900">工具箱</h2>
        <p className="mt-1 text-sm text-g-600">AI 处理 · 文件 · 文档 · 知识库</p>
      </div>

      {/* 标签栏 */}
      <div className="d-flex gap-1 border-bottom border-border">
        {tabs.map(t => (
          <button key={t.key} onClick={() => { setTab(t.key); setResult(null); setMsg(''); setDetail(null); setVersions([]) }}
            className={`px-3 py-2 text-sm border-bottom border-2 mb-npx transition ${tab === t.key ? 'border-theme text-theme' : 'border-transparent text-g-600 hover-text-g-900'}`}>
            {t.label}
          </button>
        ))}
      </div>

      {msg && <div className={`rounded-2 px-3 py-2 text-sm ${msg.includes('失败') ? 'bg-danger-subtle text-danger-emphasis' : 'bg-success-subtle text-success-emphasis'}`}>{msg}</div>}

      {/* ====== AI 处理 ====== */}
      {tab === 'ai' && (
        <div className="space-y-4">
          <div className="d-flex gap-2">
            {aiModes.map(m => (
              <button key={m.key} onClick={() => { setAiMode(m.key); setResult(null) }}
                className={`px-2 py-1 rounded-2 text-xs border transition ${aiMode === m.key ? 'border-theme bg-theme-10 text-theme' : 'border-border text-g-600'}`}>
                {m.label}
              </button>
            ))}
          </div>
          <p className="text-xs text-g-500 mt-n2">{aiModes.find(m => m.key === aiMode)?.hint}</p>

          <DragZone onFile={handleFile} label={fileName ? `已选择: ${fileName}` : '拖拽文件到此处，或点击选择'} />
          <textarea className="form-control h-32 bg-box" placeholder="或直接粘贴文本..." value={aiInput} onChange={e => setAiInput(e.target.value)} />
          <button onClick={aiSubmit} disabled={loading || !aiInput.trim()} className="btn btn-primary">{loading ? '处理中...' : '提交分析'}</button>
          <ResultBox />
        </div>
      )}

      {/* ====== 文件 ====== */}
      {tab === 'files' && (
        <div className="space-y-6">
          <div>
            <DragZone onFile={fileUpload} label={loading ? '上传中...' : fileName ? `已选择: ${fileName}` : '拖拽文件上传至 MinIO'} />
          </div>
          {uploadResult && (
            <div className="rounded-xl border border-border bg-box p-4">
              <p className="text-xs fw-medium text-g-900 mb-2">{uploadResult.error ? '上传失败' : '上传成功'}</p>
              {uploadResult.error ? <p className="text-sm text-danger">{uploadResult.error}</p> : (
                <div className="row g-2 text-sm text-g-700">
                  <div className="col-6"><span className="text-g-500">文件名：</span>{uploadResult.originalFilename ?? '-'}</div>
                  <div className="col-6"><span className="text-g-500">大小：</span>{((uploadResult.fileSize ?? 0) / 1024).toFixed(1)} KB</div>
                  <div className="col-12 text-xs"><span className="text-g-500">对象名：</span><code>{uploadResult.objectName ?? '-'}</code></div>
                </div>
              )}
            </div>
          )}
          <div className="rounded-xl border border-border bg-box p-4 space-y-3">
            <p className="text-xs fw-medium text-g-900">文件下载</p>
            <div className="input-group">
              <input className="form-control" placeholder="输入 objectName" value={downloadId} onChange={e => setDownloadId(e.target.value)} />
              <button onClick={fileDownload} disabled={!downloadId.trim()} className="btn btn-primary">下载</button>
            </div>
            {fileMsg && <p className="text-sm text-g-600">{fileMsg}</p>}
          </div>
        </div>
      )}

      {/* ====== 文档 ====== */}
      {tab === 'docs' && (
        <div className="space-y-4">
          <div className="input-group">
            <input className="form-control" placeholder="搜索文档..." value={docSearch} onChange={e => setDocSearch(e.target.value)} onKeyDown={e => e.key === 'Enter' && loadDocs()} />
            <button onClick={loadDocs} className="btn btn-outline-secondary">搜索</button>
            <button onClick={() => setShowCreate(!showCreate)} className="btn btn-primary">{showCreate ? '收起' : '新建'}</button>
          </div>

          {showCreate && (
            <div className="rounded-xl border border-border bg-box p-4 space-y-2">
              <input className="form-control" placeholder="标题 *" value={form.title} onChange={e => setForm(p => ({...p, title: e.target.value}))} />
              <input className="form-control" placeholder="文件ID（可选）" value={form.fileId} onChange={e => setForm(p => ({...p, fileId: e.target.value}))} />
              <input className="form-control" placeholder="分类" value={form.category} onChange={e => setForm(p => ({...p, category: e.target.value}))} />
              <input className="form-control" placeholder="标签，逗号分隔" value={form.tags} onChange={e => setForm(p => ({...p, tags: e.target.value}))} />
              <button onClick={docCreate} className="btn btn-primary">创建</button>
            </div>
          )}

          {detail && (
            <div className="rounded-xl border border-border bg-box p-4 space-y-2">
              <div className="d-flex justify-content-between"><p className="text-sm fw-medium">详情</p><button onClick={() => setDetail(null)} className="text-xs text-g-500">关闭</button></div>
              <div className="row g-1 text-sm"><span className="col-4 text-g-500">标题：</span><span className="col-8">{detail.title}</span><span className="col-4 text-g-500">版本：</span><span className="col-8">v{detail.version||1}</span><span className="col-4 text-g-500">分类：</span><span className="col-8">{detail.category||'-'}</span><span className="col-4 text-g-500">状态：</span><span className="col-8">{detail.status||'-'}</span></div>
              {detail.summary && <p className="text-sm text-g-700">{detail.summary}</p>}
            </div>
          )}

          {versions.length > 0 && (
            <div className="rounded-xl border border-border bg-box p-4 space-y-1">
              <div className="d-flex justify-content-between"><p className="text-sm fw-medium">版本历史</p><button onClick={() => setVersions([])} className="text-xs text-g-500">关闭</button></div>
              {versions.map((v: DocVersion, i: number) => <div key={i} className="text-xs text-g-600 d-flex justify-content-between"><span>v{v.version||v.versionNumber||i+1}</span><span>{v.createTime||v.createdAt||'-'}</span></div>)}
            </div>
          )}

          <div className="space-y-2">
            {docs.length === 0 ? <p className="text-sm text-g-500">暂无文档</p> :
              docs.map((d: DocItem) => (
                <div key={d.id} className="rounded-xl border border-border bg-box p-3 d-flex align-items-center justify-content-between">
                  <div><p className="text-sm fw-medium">{d.title||'无标题'}</p><p className="text-xs text-g-500">{d.category} · v{d.version||1}</p></div>
                  <div className="d-flex gap-2 text-xs">
                    <button onClick={() => docDetail(d.id)} className="text-theme">详情</button>
                    <button onClick={() => docVersions(d.id)} className="text-g-600">版本</button>
                    <button onClick={() => docDelete(d.id)} className="text-danger">删除</button>
                  </div>
                </div>
              ))}
          </div>
        </div>
      )}

      {/* ====== 知识库 ====== */}
      {tab === 'knowledge' && (
        <div className="space-y-4">
          <div className="d-flex gap-2">
            {(['qa','search','index'] as const).map(m => (
              <button key={m} onClick={() => { setKgMode(m); setResult(null); setMsg('') }}
                className={`px-2 py-1 rounded-2 text-xs border transition ${kgMode === m ? 'border-theme bg-theme-10 text-theme' : 'border-border text-g-600'}`}>
                {{qa:'问答',search:'检索',index:'索引'}[m]}
              </button>
            ))}
          </div>

          {kgMode === 'qa' && <>
            <textarea className="form-control h-24 bg-box" placeholder="输入问题..." value={kgQuestion} onChange={e => setKgQuestion(e.target.value)} />
            <button onClick={kgQA} disabled={loading||!kgQuestion.trim()} className="btn btn-primary">提问</button>
          </>}

          {kgMode === 'search' && <>
            <div className="input-group">
              <input className="form-control" placeholder="搜索内容..." value={kgQuery} onChange={e => setKgQuery(e.target.value)} onKeyDown={e => e.key==='Enter'&&kgSearch()} />
              <button onClick={kgSearch} disabled={loading||!kgQuery.trim()} className="btn btn-primary">搜索</button>
            </div>
          </>}

          {kgMode === 'index' && <>
            <input className="form-control" placeholder="文档 ID" value={kgDocId} onChange={e => setKgDocId(e.target.value)} />
            <textarea className="form-control h-24 bg-box" placeholder="文档内容" value={kgContent} onChange={e => setKgContent(e.target.value)} />
            <button onClick={kgIndex} disabled={loading||!kgDocId.trim()||!kgContent.trim()} className="btn btn-primary">索引</button>
          </>}
          <ResultBox />
        </div>
      )}
    </div>
  )
}
