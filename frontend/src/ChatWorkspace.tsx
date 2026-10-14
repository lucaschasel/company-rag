import { useEffect, useRef, useState } from 'react';
import { ArrowUp, ArrowUpRight, BookOpen, MessageSquare, Plus, RefreshCw, Search, Trash2 } from 'lucide-react';
import { api } from './api';

type Citation = { index: number; kbId: string; kbName: string; documentId: string; documentName: string; ordinal: number; content: string; score: number };
type ChatResponse = { answer: string; mode: string; citations: Citation[]; conversationId: string };
type TurnAnswer = { answer: string; mode: string; citations: Citation[] };
type Turn = { key: string; question: string; answer?: TurnAnswer; error?: string };
type Conv = { id: string; title: string; updated_at: string; message_count: number };
type Msg = { id: string; role: string; content: string; mode: string | null; citations: string | null; error: string | null };

function reconstruct(msgs: Msg[]): Turn[] {
  const turns: Turn[] = [];
  for (const m of msgs) {
    if (m.role === 'user') {
      turns.push({ key: 'm' + m.id, question: m.content });
    } else {
      const last = turns[turns.length - 1];
      if (last) {
        if (m.error) last.error = m.error;
        else last.answer = { answer: m.content, mode: m.mode || 'KEYWORD', citations: m.citations ? JSON.parse(m.citations) : [] };
      }
    }
  }
  return turns;
}

export function ChatWorkspace({ active, modelEnabled }: { active: boolean; modelEnabled: boolean }) {
  const [question, setQuestion] = useState('');
  const [turns, setTurns] = useState<Turn[]>([]);
  const [pending, setPending] = useState(false);
  const [conversations, setConversations] = useState<Conv[]>([]);
  const [convId, setConvId] = useState<string | null>(null);
  const [error, setError] = useState('');

  const scroll = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const request = useRef(0);
  const inFlight = useRef(false);
  const seq = useRef(0);

  const reloadConversations = async () => {
    try {
      setConversations(await api<Conv[]>('/conversations'));
    } catch (e) {
      setError((e as Error).message);
    }
  };

  // Invalidate late responses when logout unmounts this user's workspace.
  useEffect(() => () => { request.current++; }, []);

  useEffect(() => {
    if (active) reloadConversations();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active]);

  useEffect(() => {
    if (active && scroll.current) scroll.current.scrollTop = turns.length ? scroll.current.scrollHeight : 0;
  }, [active, turns, pending]);

  function resetToEmpty() {
    request.current++;
    inFlight.current = false;
    setPending(false);
    setTurns([]);
    setQuestion('');
    setError('');
  }

  function newChat() {
    resetToEmpty();
    setConvId(null);
    input.current?.focus();
  }

  async function selectConversation(id: string) {
    resetToEmpty();
    setConvId(id);
    try {
      const msgs = await api<Msg[]>(`/conversations/${id}/messages`);
      setTurns(reconstruct(msgs));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function removeConversation(id: string) {
    try {
      await api(`/conversations/${id}`, 'DELETE');
      if (id === convId) newChat();
      await reloadConversations();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function submit() {
    const text = question.trim();
    if (!text || inFlight.current) return;
    inFlight.current = true;
    setPending(true);
    setQuestion('');
    setError('');
    const rid = ++request.current;
    const key = 't' + (++seq.current);
    setTurns(old => [...old, { key, question: text }]);
    try {
      const res = await api<ChatResponse>('/chat', 'POST', {
        question: text,
        topK: 5,
        ...(convId ? { conversationId: convId } : { title: text.slice(0, 30) }),
      });
      if (request.current === rid) {
        setTurns(old =>
          old.map(t => (t.key === key ? { ...t, answer: { answer: res.answer, mode: res.mode, citations: res.citations } } : t))
        );
        if (res.conversationId && res.conversationId !== convId) setConvId(res.conversationId);
        reloadConversations();
      }
    } catch (e) {
      if (request.current === rid) setTurns(old => old.map(t => (t.key === key ? { ...t, error: (e as Error).message } : t)));
    } finally {
      if (request.current === rid) {
        inFlight.current = false;
        setPending(false);
        input.current?.focus();
      }
    }
  }

  return (
    <section className="chat-workspace" hidden={!active} aria-label="智能问答工作区">
      <aside className="chat-history">
        <button className="chat-new" onClick={newChat}>
          <Plus size={16} />
          新对话
        </button>
        <div className="chat-history-list">
          {conversations.map(c => (
            <div key={c.id} className={'chat-history-item' + (c.id === convId ? ' active' : '')}>
              <button className="chat-history-open" title={c.title} onClick={() => selectConversation(c.id)}>
                <MessageSquare size={15} />
                <span>{c.title}</span>
              </button>
              <button
                className="chat-history-del"
                aria-label="删除会话"
                title="删除会话"
                onClick={() => removeConversation(c.id)}
              >
                <Trash2 size={14} />
              </button>
            </div>
          ))}
          {!conversations.length && <p className="chat-history-empty">暂无历史会话</p>}
        </div>
      </aside>

      <div className="chat-main">
        {error && (
          <div className="chat-error" role="alert">
            <p>{error}</p>
          </div>
        )}

        <div className="conversation-panel">
          <div className="conversation-messages" ref={scroll} role="log" aria-label="对话记录" aria-live="polite" tabIndex={0}>
            {!turns.length && (
              <div className="chat-welcome">
                <span className="hero-icon"><Search size={26} /></span>
                <h2>智能问答</h2>
                <p>
                  自动检索你有权访问的资料，
                  <br />
                  汇总答案并提供原文出处。
                </p>
                <div className="suggestions">
                  {['Pod 是什么？', 'ConfigMap 和 Secret 有什么区别？', 'Service 如何发现 Pod？'].map(q => (
                    <button key={q} onClick={() => { setQuestion(q); input.current?.focus(); }}>
                      {q}
                      <ArrowUpRight size={15} />
                    </button>
                  ))}
                </div>
              </div>
            )}

            {turns.map(t => (
              <article className="conversation-turn" key={t.key} aria-label={'问题：' + t.question}>
                <div className="user-message">
                  <span>你</span>
                  <p>{t.question}</p>
                </div>

                <div className="assistant-message">
                  <div className="assistant-label">
                    <BookOpen size={17} />
                    知序
                    {t.answer && <small>{t.answer.mode === 'RAG' ? '依据企业资料回答' : '原文检索结果'}</small>}
                  </div>

                  {t.answer && (
                    <>
                      <div className="answer-text">{t.answer.answer}</div>
                      {t.answer.citations.length > 0 && (
                        <div className="citation-heading">
                          <BookOpen size={16} />
                          参考来源 · {t.answer.citations.length}
                        </div>
                      )}
                      {t.answer.citations.map(c => (
                        <details className="citation" key={c.index}>
                          <summary>
                            <span className="citation-index">{c.index}</span>
                            <span className="source-title">
                              {c.documentName}
                              <small>{c.kbName} · 分块 {c.ordinal}</small>
                            </span>
                            <span className="source-expand">展开原文</span>
                          </summary>
                          <p>{c.content}</p>
                          <a href={`/api/kbs/${encodeURIComponent(c.kbId)}/documents/${encodeURIComponent(c.documentId)}/download`}>
                            下载原文 <ArrowUpRight size={13} />
                          </a>
                        </details>
                      ))}
                    </>
                  )}

                  {t.error && (
                    <div className="chat-error" role="alert">
                      <p>{t.error}</p>
                      <button disabled={pending} onClick={() => { setQuestion(t.question); input.current?.focus(); }}>
                        重新编辑问题
                      </button>
                    </div>
                  )}

                  {!t.answer && !t.error && (
                    <div className="working" role="status">
                      <RefreshCw size={18} />
                      正在检索可访问的企业资料…
                    </div>
                  )}
                </div>
              </article>
            ))}
          </div>

          <div className="composer-dock">
            <form className="question-box" onSubmit={e => { e.preventDefault(); void submit(); }}>
              <textarea
                ref={input}
                required
                maxLength={2000}
                aria-label="输入问题"
                placeholder="直接向企业知识提问…"
                value={question}
                onChange={e => setQuestion(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault();
                    void submit();
                  }
                }}
              />
              <div>
                <span>Enter 发送 · Shift + Enter 换行</span>
                <button className="primary" disabled={pending || !question.trim()} aria-label="提交问题">
                  <ArrowUp size={20} />
                </button>
              </div>
            </form>
            <p className="composer-note">
              {modelEnabled ? '答案基于有权访问的资料，请核对原文' : '未启用 AI 回答，当前返回相关原文'} · 对话记录会保存到当前账号
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}
