import React, { useState, useEffect, useCallback, useRef } from 'react';
import { createRoot } from 'react-dom/client';
import {
  BookOpen, Search, MessageSquare, Users, ShieldCheck, Plus, ArrowUpRight,
  Upload, FileText, RefreshCw, Trash2, Download, LogOut, ArrowUp, Cpu,
  FolderOpen, Layers, X, ChevronRight, Settings, CheckCircle2,
} from 'lucide-react';
import { api, resetCsrf } from './api';
import './style.css';
import { ModelSettings } from './ModelSettings';
import { ChatWorkspace } from './ChatWorkspace';

type User = { id: string; username: string; role: string };
type KB = { id: string; name: string; description: string; owner_id: string; document_count: number; created_at: string };
type Doc = { id: string; name: string; size_bytes: number; status: string; chunk_count: number; error_message?: string };
type Citation = { index: number; kbId: string; kbName: string; documentId: string; documentName: string; ordinal: number; content: string; score: number };
type Answer = { answer: string; mode: string; citations: Citation[] };
type System = { modelEnabled: boolean; embeddingEnabled: boolean; embeddingModel: string; storage: string };
type Audit = { id: string; username: string; action: string; resource_id: string; created_at: string };

const labels: Record<string, string> = {
  PENDING: '等待处理',
  PROCESSING: '解析入库中',
  READY: '已就绪',
  FAILED: '处理失败',
};
const roles: Record<string, string> = {
  ADMIN: '管理员',
  EDITOR: '编辑者',
  VIEWER: '阅读者',
};

function App() {
  const [user, setUser] = useState<User | null>(null);
  const [checking, setChecking] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [page, setPage] = useState('知识库');
  const [kbs, setKbs] = useState<KB[]>([]);
  const [selected, setSelected] = useState<KB | null>(null);
  const [docs, setDocs] = useState<Doc[]>([]);
  const [system, setSystem] = useState<System | null>(null);
  const [filter, setFilter] = useState('');
  const [modal, setModal] = useState('');
  const [busy, setBusy] = useState(false);
  const [question, setQuestion] = useState('');
  const [answer, setAnswer] = useState<Answer | null>(null);
  const [chatKb, setChatKb] = useState('');
  const [users, setUsers] = useState<User[]>([]);
  const [audit, setAudit] = useState<Audit[]>([]);
  const [members, setMembers] = useState<User[]>([]);
  const [preview, setPreview] = useState<{ ordinal: number; content: string }[]>([]);

  const fileInput = useRef<HTMLInputElement>(null);
  const selectionRef = useRef<string | undefined>(undefined);
  selectionRef.current = selected?.id;

  const loadKbs = useCallback(async () => {
    const result = await api<KB[]>('/kbs');
    setKbs(result);
    setChatKb(old => (result.some(k => k.id === old) ? old : ''));
  }, []);

  const run = async (fn: () => Promise<void>) => {
    setError('');
    setBusy(true);
    try {
      await fn();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    api<User>('/auth/me').then(setUser).catch(() => {}).finally(() => setChecking(false));
    const expired = () => {
      setUser(null);
      setKbs([]);
      setSelected(null);
      setDocs([]);
      setUsers([]);
      setAudit([]);
      setMembers([]);
      setPreview([]);
      setAnswer(null);
      setChatKb('');
      setQuestion('');
      setPage('知识库');
      resetCsrf();
    };
    window.addEventListener('session-expired', expired);
    return () => window.removeEventListener('session-expired', expired);
  }, []);

  useEffect(() => {
    if (user) {
      loadKbs().catch(e => setError(e.message));
      api<System>('/system').then(setSystem).catch(e => setError(e.message));
    }
  }, [user, loadKbs]);

  useEffect(() => {
    if (!selected) return;
    const id = selected.id;
    const refresh = () =>
      api<Doc[]>(`/kbs/${id}/documents`)
        .then(d => { if (selectionRef.current === id) setDocs(d); })
        .catch(e => setError(e.message));
    setDocs([]);
    refresh();
    const timer = setInterval(refresh, 4000);
    return () => clearInterval(timer);
  }, [selected]);

  useEffect(() => {
    if (!user) return;
    if (page === '用户管理') api<User[]>('/admin/users').then(setUsers).catch(e => setError(e.message));
    if (page === '审计日志') api<Audit[]>('/admin/audit').then(setAudit).catch(e => setError(e.message));
  }, [page, user]);

  useEffect(() => {
    if (notice) {
      const t = setTimeout(() => setNotice(''), 3500);
      return () => clearTimeout(t);
    }
  }, [notice]);

  const navigate = (next: string) => {
    setPage(next);
    setSelected(null);
    setFilter('');
    setError('');
  };

  const writable = user?.role !== 'VIEWER';

  const upload = (files: FileList | null) =>
    run(async () => {
      if (!files || !selected) return;
      for (const file of Array.from(files)) {
        if (file.size > 20 * 1024 * 1024) throw new Error('文件不能超过 20 MB');
        const form = new FormData();
        form.append('file', file);
        await api(`/kbs/${selected.id}/documents`, 'POST', form);
      }
      setDocs(await api(`/kbs/${selected.id}/documents`));
      await loadKbs();
      setNotice('文档已上传，正在排队处理');
      if (fileInput.current) fileInput.current.value = '';
    });

  if (checking) return <div className="loading">正在连接知识空间…</div>;

  if (!user)
    return (
      <div className="login">
        <div className="login-story">
          <div className="brand">
            <span className="logo"><Layers size={23} /></span>
            知序 <small>KNOWLEDGE HUB</small>
          </div>
          <div>
            <h1>企业知识库</h1>
            <p>统一管理团队文档，检索与问答全程可溯源。</p>
          </div>
          <span>权限隔离 · 引用溯源 · 操作审计</span>
        </div>
        <form
          className="login-form"
          onSubmit={e => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            run(async () => {
              resetCsrf();
              setUser(await api('/auth/login', 'POST', Object.fromEntries(f)));
            });
          }}
        >
          <h2>登录</h2>
          <p className="muted">使用管理员分配的账号登录</p>
          <label>
            账号
            <input name="username" autoComplete="username" required placeholder="输入账号" />
          </label>
          <label>
            密码
            <input name="password" type="password" autoComplete="current-password" required placeholder="输入密码" />
          </label>
          {error && <div className="error" role="alert">{error}</div>}
          <button className="primary" disabled={busy}>
            登录 <ArrowUpRight size={17} />
          </button>
          <p className="login-hint">首次部署请通过 ADMIN_PASSWORD 设置管理员密码。</p>
        </form>
      </div>
    );

  return (
    <div className={page === '智能问答' ? 'shell chat-shell' : 'shell'}>
      <aside className="sidebar">
        <div className="brand">
          <span className="logo"><Layers size={23} /></span>
          知序 <small>KNOWLEDGE HUB</small>
        </div>

        <nav>
          {[
            [BookOpen, '知识库'],
            [MessageSquare, '智能问答'],
            [Search, '检索测试'],
            ...(user.role === 'ADMIN' ? [[Users, '用户管理'], [ShieldCheck, '审计日志']] : []),
            [Settings, '模型与部署'],
          ].map(([Icon, name]) => {
            const I = Icon as typeof BookOpen;
            return (
              <button
                key={String(name)}
                className={page === name ? 'active' : ''}
                onClick={() => navigate(String(name))}
              >
                <I size={18} />
                {String(name)}
                {page === name && <span className="nav-dot" />}
              </button>
            );
          })}
        </nav>

        <div className="sidebar-bottom">
          <div className="profile">
            <span className="avatar">{user.username[0].toUpperCase()}</span>
            <div>
              {user.username}
              <small>{roles[user.role]}</small>
            </div>
            <button
              aria-label="退出登录"
              onClick={() =>
                run(async () => {
                  await api('/auth/logout', 'POST');
                  window.dispatchEvent(new Event('session-expired'));
                })
              }
            >
              <LogOut size={17} />
            </button>
          </div>
        </div>
      </aside>

      <main>
        <header className="topbar">
          <span>
            工作空间 <ChevronRight size={14} /> {page}
            {selected && (
              <>
                {' '}
                <ChevronRight size={14} /> {selected.name}
              </>
            )}
          </span>
          <span className="mode">
            <i className={system?.modelEnabled ? '' : 'off'} />
            {system?.modelEnabled ? '大模型已配置' : '关键词检索模式'}
          </span>
        </header>

        <div className="content">
          {error && (
            <div className="error" role="alert">
              {error}
              <button aria-label="关闭错误提示" onClick={() => setError('')}><X size={15} /></button>
            </div>
          )}
          {notice && (
            <div className="notice" role="status">
              <CheckCircle2 size={17} />
              {notice}
            </div>
          )}

          {page === '知识库' && !selected && (
            <>
              <div className="page-heading">
                <div>
                  <h1>
                    企业知识库
                    <span className="count">{kbs.length}</span>
                  </h1>
                  <p>集中管理团队文档，支持检索与问答。</p>
                </div>
                {writable && (
                  <button className="primary" onClick={() => setModal('新建知识库')}>
                    <Plus size={17} />
                    新建知识库
                  </button>
                )}
              </div>

              <div className="stats">
                <Stat
                  label="可访问知识库"
                  value={kbs.length}
                  icon={<BookOpen />}
                  detail="你有权访问的知识库数量"
                />
                <Stat
                  label="文档总数"
                  value={kbs.reduce((n, k) => n + Number(k.document_count), 0)}
                  icon={<FileText />}
                  detail="所有知识库的文档数量"
                />
                <Stat
                  label="检索引擎"
                  value={system?.embeddingEnabled ? '语义增强' : '关键词'}
                  icon={<Cpu />}
                  detail={system?.embeddingEnabled ? '向量召回 + 关键词排序' : '未配置向量模型，使用关键词检索'}
                />
              </div>

              <div className="section-line">
                <h3>全部知识库</h3>
                <div className="search-field">
                  <Search size={16} />
                  <input
                    aria-label="搜索知识库"
                    placeholder="搜索知识库…"
                    value={filter}
                    onChange={e => setFilter(e.target.value)}
                  />
                </div>
              </div>

              <div className="kb-grid">
                {kbs
                  .filter(k => (k.name + k.description).toLowerCase().includes(filter.toLowerCase()))
                  .map((kb, i) => (
                    <button className="kb-card" key={kb.id} onClick={() => setSelected(kb)}>
                      <div className="card-top">
                        <span className={'kb-icon tone' + (i % 3)}><BookOpen size={24} /></span>
                        <ArrowUpRight size={18} />
                      </div>
                      <h3>{kb.name}</h3>
                      <p>{kb.description || '暂无描述'}</p>
                      <div className="card-footer">
                        <span><FileText size={14} />{kb.document_count} 份文档</span>
                        <span>{new Date(kb.created_at).toLocaleDateString('zh-CN')}</span>
                      </div>
                    </button>
                  ))}
                {writable && (
                  <button className="new-card" onClick={() => setModal('新建知识库')}>
                    <span><Plus size={23} /></span>
                    <strong>新建知识库</strong>
                    <small>按团队、项目或主题组织文档</small>
                  </button>
                )}
              </div>

              {kbs.length === 0 && (
                <div className="intro-strip">
                  <BookOpen size={20} />
                  <div>
                    <strong>从第一份文档开始</strong>
                    <p>创建知识库 → 上传文档 → 自动分块 → 检索与问答</p>
                  </div>
                </div>
              )}
            </>
          )}

          {page === '知识库' && selected && (
            <>
              <button className="text-button" onClick={() => { setSelected(null); loadKbs(); }}>
                ← 返回知识库
              </button>
              <div className="page-heading">
                <div>
                  <h1>{selected.name}</h1>
                  <p>{selected.description}</p>
                </div>
                <div className="actions">
                  {(user.role === 'ADMIN' || user.id === selected.owner_id) && (
                    <button
                      onClick={() =>
                        run(async () => {
                          setMembers(await api(`/kbs/${selected.id}/members`));
                          setModal('成员权限');
                        })
                      }
                    >
                      <Users size={16} />
                      成员权限
                    </button>
                  )}
                  {writable && (
                    <button className="primary" disabled={busy} onClick={() => fileInput.current?.click()}>
                      <Upload size={16} />
                      上传文档
                    </button>
                  )}
                </div>
              </div>

              <input
                ref={fileInput}
                type="file"
                hidden
                multiple
                accept=".pdf,.docx,.txt,.md"
                onChange={e => upload(e.target.files)}
              />
              {writable && (
                <button
                  className="upload-zone"
                  disabled={busy}
                  onClick={() => fileInput.current?.click()}
                  onDragOver={e => e.preventDefault()}
                  onDrop={e => { e.preventDefault(); upload(e.dataTransfer.files); }}
                >
                  <Upload size={26} />
                  <strong>拖拽文档到这里，或点击上传</strong>
                  <span>PDF、DOCX、TXT、Markdown · 单文件最大 20 MB · 扫描件需先 OCR</span>
                </button>
              )}

              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>文档名称</th>
                      <th>状态</th>
                      <th>分块</th>
                      <th>大小</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {docs.map(d => (
                      <tr key={d.id}>
                        <td>
                          <button
                            className="document-name"
                            onClick={() =>
                              run(async () => {
                                setPreview(await api(`/kbs/${selected.id}/documents/${d.id}/chunks`));
                                setModal('文档分块');
                              })
                            }
                          >
                            <FileText size={19} />
                            {d.name}
                          </button>
                          {d.error_message && <small className="failure">{d.error_message}</small>}
                        </td>
                        <td><span className={'status ' + d.status}>{labels[d.status]}</span></td>
                        <td>{d.chunk_count}</td>
                        <td>{(d.size_bytes / 1024).toFixed(1)} KB</td>
                        <td>
                          <div className="actions">
                            <a
                              className="icon-button"
                              aria-label={'下载 ' + d.name}
                              href={`/api/kbs/${selected.id}/documents/${d.id}/download`}
                            >
                              <Download size={16} />
                            </a>
                            {writable && (
                              <>
                                <button
                                  title="重新入库"
                                  aria-label={'重新入库 ' + d.name}
                                  disabled={busy || ['PENDING', 'PROCESSING'].includes(d.status)}
                                  onClick={() =>
                                    run(async () => {
                                      await api(`/kbs/${selected.id}/documents/${d.id}/retry`, 'POST');
                                      setNotice('已加入处理队列');
                                      setDocs(await api(`/kbs/${selected.id}/documents`));
                                    })
                                  }
                                >
                                  <RefreshCw size={15} />
                                </button>
                                <button
                                  title="删除"
                                  aria-label={'删除 ' + d.name}
                                  disabled={busy}
                                  onClick={() => {
                                    if (confirm(`确定删除“${d.name}”及其索引？`))
                                      run(async () => {
                                        await api(`/kbs/${selected.id}/documents/${d.id}`, 'DELETE');
                                        setDocs(await api(`/kbs/${selected.id}/documents`));
                                        await loadKbs();
                                      });
                                  }}
                                >
                                  <Trash2 size={15} />
                                </button>
                              </>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {!docs.length && <Empty text="暂无文档，点击上方上传" />}
              </div>
            </>
          )}

          {page === '检索测试' && (
            <>
              <div className="page-heading">
                <div>
                  <h1>{page}</h1>
                  <p>只检查能否找到相关原文，不调用聊天模型生成答案；启用向量时会调用向量接口。</p>
                </div>
                <select
                  aria-label="选择知识库"
                  disabled={busy}
                  value={chatKb}
                  onChange={e => { setChatKb(e.target.value); setAnswer(null); }}
                >
                  <option value="">全部可访问知识库</option>
                  {kbs.map(k => (
                    <option key={k.id} value={k.id}>{k.name}</option>
                  ))}
                </select>
              </div>

              <div className="chat-panel">
                {!answer && !busy && (
                  <div className="chat-welcome">
                    <span className="hero-icon"><Search size={26} /></span>
                    <h2>检索测试</h2>
                    <p>
                      输入问题测试检索，可按需限定知识库。
                      <br />
                      回答仅基于你有权访问的文档。
                    </p>
                    <div className="suggestions">
                      {['这份资料的核心内容是什么？', '项目交付需要哪些步骤？', '有哪些需要注意的规范？'].map(q => (
                        <button key={q} onClick={() => setQuestion(q)}>
                          {q}
                          <ArrowUpRight size={15} />
                        </button>
                      ))}
                    </div>
                  </div>
                )}
                {busy && (
                  <div className="working">
                    <RefreshCw size={19} />
                    正在检索和处理，请稍候…
                  </div>
                )}
                {answer && (
                  <div className="answer">
                    <span className="badge">{answer.mode === 'RAG' ? '生成回答' : '检索结果'}</span>
                    <div className="answer-text">{answer.answer}</div>
                    <div className="citation-heading">
                      <BookOpen size={16} />
                      参考来源 · {answer.citations.length}
                    </div>
                    {answer.citations.map(c => (
                      <details className="citation" key={c.index} open>
                        <summary>
                          <span className="citation-index">{c.index}</span>
                          {c.documentName}
                          <small>{c.kbName} · 分块 {c.ordinal} · 匹配 {c.score.toFixed(3)}</small>
                        </summary>
                        <p>{c.content}</p>
                        <a href={`/api/kbs/${c.kbId}/documents/${c.documentId}/download`}>
                          下载原文 <ArrowUpRight size={13} />
                        </a>
                      </details>
                    ))}
                  </div>
                )}
                <form
                  className="question-box"
                  onSubmit={e => {
                    e.preventDefault();
                    run(async () => {
                      const q = { kbId: chatKb, question, topK: 5 };
                      const citations = await api<Citation[]>('/search', 'POST', q);
                      setAnswer({
                        answer: citations.length ? '已找到以下相关原文片段。' : '没有找到相关内容。',
                        mode: 'SEARCH',
                        citations,
                      });
                    });
                  }}
                >
                  <textarea
                    required
                    maxLength={2000}
                    aria-label="输入问题"
                    placeholder="输入问题，检索相关知识…"
                    value={question}
                    onChange={e => setQuestion(e.target.value)}
                  />
                  <div>
                    <span>
                      {system?.embeddingEnabled
                        ? '语义检索 · 仅返回原文，不生成答案'
                        : '关键词检索 · 仅返回匹配原文'}
                    </span>
                    <button className="primary" disabled={busy || !question.trim()} aria-label="提交问题">
                      <ArrowUp size={20} />
                    </button>
                  </div>
                </form>
              </div>
            </>
          )}

          <ChatWorkspace
            key={user.id}
            active={page === '智能问答'}
            modelEnabled={!!system?.modelEnabled}
          />

          {page === '用户管理' && (
            <>
              <div className="page-heading">
                <div>
                  <h1>用户管理</h1>
                  <p>设置系统角色，并在知识库中分配访问成员。</p>
                </div>
                <button className="primary" onClick={() => setModal('新增用户')}>
                  <Plus size={16} />
                  新增用户
                </button>
              </div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>账号</th>
                      <th>角色</th>
                      <th>用户 ID</th>
                    </tr>
                  </thead>
                  <tbody>
                    {users.map(u => (
                      <tr key={u.id}>
                        <td>{u.username}</td>
                        <td><span className="status READY">{roles[u.role]}</span></td>
                        <td className="muted">{u.id}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}

          {page === '审计日志' && (
            <>
              <div className="page-heading">
                <div>
                  <h1>审计日志</h1>
                  <p>最近 200 条操作记录，不记录问题正文和文档内容。</p>
                </div>
                <button onClick={() => run(async () => setAudit(await api('/admin/audit')))}>
                  <RefreshCw size={16} />
                  刷新
                </button>
              </div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>操作人</th>
                      <th>事件</th>
                      <th>资源</th>
                      <th>时间</th>
                    </tr>
                  </thead>
                  <tbody>
                    {audit.map(e => (
                      <tr key={e.id}>
                        <td>{e.username}</td>
                        <td>{e.action}</td>
                        <td className="muted">{e.resource_id}</td>
                        <td>{new Date(e.created_at).toLocaleString('zh-CN')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}

          {page === '模型与部署' && (
            <ModelSettings
              admin={user.role === 'ADMIN'}
              onSaved={() => {
                api<System>('/system').then(setSystem).catch(e => setError(e.message));
              }}
            />
          )}

          <footer>
            知序 Knowledge Hub <span>企业知识库系统</span>
          </footer>
        </div>
      </main>

      {modal && (
        <div className="modal-backdrop" onClick={() => setModal('')}>
          <section
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-label={modal}
            onClick={e => e.stopPropagation()}
          >
            <div className="modal-heading">
              <h2>{modal}</h2>
              <button aria-label="关闭弹窗" onClick={() => setModal('')}><X size={20} /></button>
            </div>

            {modal === '新建知识库' && (
              <form
                onSubmit={e => {
                  e.preventDefault();
                  const data = Object.fromEntries(new FormData(e.currentTarget));
                  run(async () => {
                    await api('/kbs', 'POST', data);
                    await loadKbs();
                    setModal('');
                    setNotice('知识库已创建');
                  });
                }}
              >
                <label>
                  知识库名称
                  <input name="name" required maxLength={120} placeholder="例如：产品与研发知识库" autoFocus />
                </label>
                <label>
                  描述
                  <textarea name="description" maxLength={2000} placeholder="说明这个知识库的用途和内容范围" />
                </label>
                <p className="muted">创建后仅所有者和管理员可访问，可在成员权限中邀请团队成员。</p>
                <button className="primary" disabled={busy}>创建知识库</button>
              </form>
            )}

            {modal === '新增用户' && (
              <form
                onSubmit={e => {
                  e.preventDefault();
                  const data = Object.fromEntries(new FormData(e.currentTarget));
                  run(async () => {
                    await api('/admin/users', 'POST', data);
                    setUsers(await api('/admin/users'));
                    setModal('');
                  });
                }}
              >
                <label>
                  账号
                  <input name="username" required pattern="[a-zA-Z0-9_.-]{3,80}" placeholder="至少 3 位英文字母、数字、下划线或点" />
                </label>
                <label>
                  初始密码
                  <input name="password" type="password" minLength={1} maxLength={72} required autoComplete="new-password" placeholder="输入初始密码" />
                </label>
                <label>
                  系统角色
                  <select name="role">
                    <option value="VIEWER">阅读者 · 检索与问答</option>
                    <option value="EDITOR">编辑者 · 管理文档与创建知识库</option>
                    <option value="ADMIN">管理员 · 访问全部知识库与用户管理</option>
                  </select>
                </label>
                <button className="primary" disabled={busy}>创建用户</button>
              </form>
            )}

            {modal === '成员权限' && selected && (
              <>
                <p className="muted">所有者与管理员默认可访问。成员的编辑能力由系统角色决定。</p>
                {members.map(m => (
                  <div className="member" key={m.id}>
                    <span>{m.username} · {roles[m.role]}</span>
                    <button
                      disabled={busy}
                      onClick={() =>
                        run(async () => {
                          await api(`/kbs/${selected.id}/members/${m.id}`, 'DELETE');
                          setMembers(await api(`/kbs/${selected.id}/members`));
                        })
                      }
                    >
                      移除
                    </button>
                  </div>
                ))}
                <form
                  onSubmit={e => {
                    e.preventDefault();
                    const f = new FormData(e.currentTarget);
                    run(async () => {
                      await api(`/kbs/${selected.id}/members`, 'POST', Object.fromEntries(f));
                      setMembers(await api(`/kbs/${selected.id}/members`));
                    });
                  }}
                >
                  <label>
                    添加成员
                    <input name="username" required placeholder="输入已有用户的账号" />
                  </label>
                  <button className="primary" disabled={busy}>添加成员</button>
                </form>
              </>
            )}

            {modal === '文档分块' && (
              <div className="chunks">
                {preview.map(c => (
                  <div className="citation" key={c.ordinal}>
                    <strong>分块 {c.ordinal}</strong>
                    <p>{c.content}</p>
                  </div>
                ))}
                {!preview.length && <Empty text="文档尚未生成分块，请等待处理或检查失败原因" />}
              </div>
            )}

            {error && <p className="error" role="alert">{error}</p>}
          </section>
        </div>
      )}
    </div>
  );
}

function Stat({ label, value, icon, detail }: { label: string; value: React.ReactNode; icon: React.ReactNode; detail: string }) {
  return (
    <div className="stat">
      <div>
        <span>{label}</span>
        {icon}
      </div>
      <strong>{value}</strong>
      <small>{detail}</small>
    </div>
  );
}

function Empty({ text }: { text: string }) {
  return (
    <div className="empty">
      <FolderOpen size={28} />
      <p>{text}</p>
    </div>
  );
}

createRoot(document.getElementById('root')!).render(<App />);
