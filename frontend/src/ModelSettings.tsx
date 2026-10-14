import { useEffect, useState } from 'react';
import { api } from './api';
import { Save, PlugZap, RefreshCw } from 'lucide-react';

type Endpoint = { provider: 'OPENAI' | 'OLLAMA'; baseUrl: string; apiKey: string; model: string; keyConfigured?: boolean };
type Config = { enabled: boolean; embeddingEnabled: boolean; chat: Endpoint; embedding: Endpoint; version: string };

export function ModelSettings({ admin, onSaved }: { admin: boolean; onSaved: () => void }) {
  const [config, setConfig] = useState<Config | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [clearChatKey, setClearChatKey] = useState(false);
  const [clearEmbeddingKey, setClearEmbeddingKey] = useState(false);
  const [tests, setTests] = useState<Record<string, { ok: boolean; message: string; elapsedMs: number }>>({});

  useEffect(() => {
    if (admin) api<Config>('/admin/model-settings').then(setConfig).catch(e => setError(e.message));
  }, [admin]);

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await fn();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const body = { config, clearChatKey, clearEmbeddingKey };

  const change = (kind: 'chat' | 'embedding', patch: Partial<Endpoint>) => {
    if (config) setConfig({ ...config, [kind]: { ...config[kind], ...patch } });
    setTests({});
  };

  const preset = (kind: 'chat' | 'embedding', value: string) => {
    const local = value === 'OLLAMA';
    change(kind, {
      provider: local ? 'OLLAMA' : 'OPENAI',
      baseUrl: local
        ? 'http://localhost:11434'
        : value === 'LOCAL'
          ? 'http://localhost:1234/v1'
          : 'https://api.openai.com/v1',
      model: local ? (kind === 'chat' ? 'qwen2.5:7b' : 'nomic-embed-text') : '',
      apiKey: '',
      keyConfigured: false,
    });
    if (kind === 'chat') setClearChatKey(true);
    else setClearEmbeddingKey(true);
  };

  if (!admin)
    return (
      <div className="config-card">
        <h3>模型配置由管理员维护</h3>
        <p>请联系管理员设置聊天模型和向量模型。保存后，新请求会使用最新配置。</p>
      </div>
    );

  return (
    <>
      <div className="page-heading">
        <div>
          <h1>AI 接口设置</h1>
          <p>聊天与向量接口独立配置，支持云端、自建兼容服务和本地 Ollama。</p>
        </div>
      </div>
      {error && <div className="error" role="alert">{error}</div>}
      {notice && <div className="notice" role="status">{notice}</div>}
      {config && (
        <form
          onSubmit={e => {
            e.preventDefault();
            run(async () => {
              setConfig(await api('/admin/model-settings', 'PUT', body));
              setClearChatKey(false);
              setClearEmbeddingKey(false);
              setNotice('配置已保存，新请求立即生效。变更向量接口后请重新建立索引。');
              onSaved();
            });
          }}
        >
          <fieldset disabled={busy} className="settings-fields">
            <div className="settings-toggles">
              <label>
                <input type="checkbox" checked={config.enabled} onChange={e => setConfig({ ...config, enabled: e.target.checked })} />
                启用 AI 回答
              </label>
              <label>
                <input type="checkbox" checked={config.embeddingEnabled} onChange={e => setConfig({ ...config, embeddingEnabled: e.target.checked })} />
                启用向量检索
              </label>
            </div>
            <p className="muted">
              可以先只启用 AI 回答，使用关键词检索的资料生成答案；开启向量检索需要配置可用的向量模型并重新入库。
            </p>

            <div className="model-grid">
              {(['chat', 'embedding'] as const).map(kind => (
                <section className="config-card model-form" key={kind}>
                  <h3>{kind === 'chat' ? '聊天模型 · 生成答案' : '向量模型 · 语义检索'}</h3>
                  <div className="preset-row">
                    <button type="button" onClick={() => preset(kind, 'OPENAI')}>云端兼容接口</button>
                    <button type="button" onClick={() => preset(kind, 'LOCAL')}>本地兼容接口</button>
                    <button type="button" onClick={() => preset(kind, 'OLLAMA')}>Ollama 原生</button>
                  </div>

                  <label>
                    接口协议
                    <select
                      aria-label={kind + ' 协议'}
                      value={config[kind].provider}
                      onChange={e => change(kind, { provider: e.target.value as Endpoint['provider'] })}
                    >
                      <option value="OPENAI">OpenAI 兼容（云端 / 本地）</option>
                      <option value="OLLAMA">Ollama 原生接口</option>
                    </select>
                  </label>

                  <label>
                    接口基础地址
                    <input
                      aria-label={kind + ' 地址'}
                      type="url"
                      required
                      maxLength={500}
                      value={config[kind].baseUrl}
                      onChange={e => change(kind, { baseUrl: e.target.value })}
                    />
                  </label>
                  <small>
                    {config[kind].provider === 'OLLAMA'
                      ? '例如 http://localhost:11434，不加 /api/chat 或 /v1'
                      : '填写含 /v1 的基础地址，不加 /chat/completions 或 /embeddings'}
                  </small>

                  <label>
                    API Key
                    <input
                      aria-label={kind + ' 密钥'}
                      type="password"
                      autoComplete="new-password"
                      maxLength={2000}
                      value={config[kind].apiKey}
                      placeholder={config[kind].keyConfigured ? '已保存，留空保留；更换地址不会复用旧密钥' : '本地无鉴权服务可以留空'}
                      onChange={e => {
                        change(kind, { apiKey: e.target.value });
                        if (kind === 'chat') setClearChatKey(false);
                        else setClearEmbeddingKey(false);
                      }}
                    />
                  </label>

                  <label className="check-label">
                    <input
                      type="checkbox"
                      checked={kind === 'chat' ? clearChatKey : clearEmbeddingKey}
                      onChange={e =>
                        kind === 'chat'
                          ? setClearChatKey(e.target.checked)
                          : setClearEmbeddingKey(e.target.checked)
                      }
                    />
                    清除已保存的密钥
                  </label>

                  <label>
                    模型名称
                    <input
                      aria-label={kind + ' 模型'}
                      required
                      maxLength={150}
                      value={config[kind].model}
                      onChange={e => change(kind, { model: e.target.value })}
                      placeholder="填写服务商提供或本机已下载的模型名"
                    />
                  </label>

                  <button
                    type="button"
                    onClick={() =>
                      run(async () => {
                        const result = await api<{ ok: boolean; message: string; elapsedMs: number }>(
                          `/admin/model-settings/test/${kind}`,
                          'POST',
                          body
                        );
                        setTests(prev => ({ ...prev, [kind]: result }));
                      })
                    }
                  >
                    <PlugZap size={16} />
                    测试{kind === 'chat' ? '聊天' : '向量'}连接
                  </button>
                  {tests[kind] && (
                    <p className={tests[kind].ok ? 'test-ok' : 'failure'} role="status">
                      {tests[kind].message} · {tests[kind].elapsedMs} ms
                    </p>
                  )}
                </section>
              ))}
            </div>

            <div className="settings-footer">
              <label>
                向量索引版本
                <input required maxLength={60} value={config.version} onChange={e => setConfig({ ...config, version: e.target.value })} />
              </label>
              <button type="submit" className="primary">
                <Save size={17} />
                保存并应用
              </button>
              <button
                type="button"
                onClick={() => {
                  if (confirm('将全部就绪或失败文档加入重新入库队列。处理期间这些文档暂不可检索；已保存的向量接口会收到文档分块。继续？'))
                    run(async () => {
                      const r = await api<{ queued: number }>('/admin/model-settings/reindex', 'POST');
                      setNotice(`已将 ${r.queued} 份文档加入队列，使用已保存配置处理。`);
                    });
                }}
              >
                <RefreshCw size={16} />
                全部文档重新入库
              </button>
            </div>
          </fieldset>

          <p className="settings-note">
            密钥在服务端加密保存，浏览器不会读取已保存的密钥。连接测试使用当前表单并发送固定测试文本，不上传知识库内容。保存后，启用的云端服务会接收问答上下文或入库分块。本地模型需先自行安装并启动。
          </p>
        </form>
      )}
    </>
  );
}
