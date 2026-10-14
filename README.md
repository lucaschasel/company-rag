# 知序 · 企业 RAG 知识库

面向企业内部的首版可运行实现：Java 17 / Spring Boot 3.5.7 + React 19 / TypeScript。包含真实后端、数据库迁移、权限检查、文件入库和检索问答链路。

## 已实现

- Session 登录、BCrypt 密码哈希、CSRF 防护，ADMIN / EDITOR / VIEWER 三种角色。
- 知识库创建与列表、按库授予成员访问权限。管理员可访问全部知识库；编辑者可创建知识库并编辑有权访问的文档；阅读者只能读取被授权知识库。
- TXT / Markdown / PDF / DOCX 上传（20 MB）、持久化处理状态、解析分块、失败重试、重新索引、删除、下载和分块预览。
- OpenAI 兼容的 embeddings / chat completions 接口，支持云端模型与 Ollama `/v1`。
- 向量召回、关键词加权排序、问答与来源编号、文档名和分块序号溯源。未启用模型时只返回关键词匹配原文，界面明确标识。
- 用户创建、成员添加/移除、操作审计、Docker Compose 部署。

## 一键启动 / 停止（推荐）

在项目根目录双击 **Start.cmd**，脚本会识别并停止本项目旧进程、按需构建后端、安装有变化的前端依赖、构建前端，等待服务健康后自动打开 http://localhost:5173。重复执行相当于安全重启；不会停止其他目录的项目。仍需已安装 Java 17、Node 20+，Maven 使用项目 `.tools` 或系统安装版本。

双击 **Stop.cmd** 停止本项目后端、前端及其子进程，保留数据库、文档、账号与 AI 配置。失败时窗口保留错误提示，日志在 `.tools`。

管理员账号 `admin`。首次自动生成密码存于 `.tools/dev-password.txt`；已有账号密码不会被环境变量覆盖。不要同时执行一键启动和手动 Maven/Vite 启动。

## 本地启动（Windows PowerShell）

依赖 Java 17、Node 20+、Maven 3.9+。此次开发已下载 Maven 至项目 `.tools`，该目录不提交版本控制。

终端一，在项目根目录执行：

```powershell
$env:ADMIN_PASSWORD = '请设置初始密码'
cd backend
..\.tools\apache-maven-3.9.11\bin\mvn.cmd spring-boot:run
# 若已全局安装 Maven，可用 mvn spring-boot:run
```

终端二：

```powershell
cd frontend
npm ci
npm run dev
```

访问 http://localhost:5173，默认账号 `admin`，密码为上面设置的值。密码仅在空库首次启动时使用，重启时不会重置已有账号。

构建 JAR 并安装前端依赖后，也可在根目录运行 `./scripts/start-dev.ps1`，后台启动两个开发服务。未设置 ADMIN_PASSWORD 时，脚本自动生成本地开发密码并保存到 `.tools/dev-password.txt`（已被 Git 忽略），控制台输出进程 PID 及停止命令。`examples/研发交付规范.md` 可用于测试上传和检索。

开发默认使用 H2 文件数据库，位于 `backend/data`；无需外部数据库。后端端口 8080，Vite 将 `/api` 请求代理到后端。请使用同一浏览器地址登录，勿混用 localhost 和 127.0.0.1。

### 启动时报 H2 文件被占用（90020）

如果出现 `Database may be already in use` 或 `The file is locked`，通常是已运行的后端占用了同一个 H2 文件。后台启动与 Maven 手动启动二选一，不要重复启动；不要删除数据库文件或关闭文件锁。

从后台演示服务切换到 Maven 开发时，在项目根目录执行：

```powershell
./scripts/stop-dev.ps1 -BackendOnly
cd backend
../.tools/apache-maven-3.9.11/bin/mvn.cmd spring-boot:run
```

不带 `-BackendOnly` 时会同时停止启动脚本运行的前端。停止脚本核对完整项目入口路径，也能识别本项目 Maven/Vite 及其子进程，不会批量结束其他 Java/Node 服务。已有数据库中的用户和文档会保留。

`ADMIN_PASSWORD` **只用于首次创建管理员**；已有数据库再次设置该变量不会修改登录密码。使用启动脚本初始化的账号，其原始密码位于 `.tools/dev-password.txt`。新建空库时初始密码不能为空。

### npm ci 报 EPERM / unlink esbuild.exe

Windows 上，运行中的 Vite/esbuild 会锁住 `node_modules/@esbuild/win32-x64/esbuild.exe`。安装依赖前先停止前端；无需停止后端或删除锁文件。在项目根目录执行：

```powershell
./scripts/stop-dev.ps1 -FrontendOnly
cd frontend
npm ci
npm run dev
```

`-FrontendOnly` 只停止本项目后台 Vite 和项目路径内残留的 esbuild 进程，保留后端。若前端由自己在终端通过 `npm run dev` 启动，先在该终端按 Ctrl+C，再执行安装。不要同时运行安装和前端构建/开发服务。

## 后台 AI 接口设置

以管理员登录，打开左侧 **模型与部署 → AI 接口设置**。配置分为聊天模型和向量模型，可分别指定协议、基础地址、API Key、模型名称。

| 类型 | 基础地址示例 | 请求接口 |
| --- | --- | --- |
| 云端 OpenAI 兼容 | `https://供应商地址/v1` | `/chat/completions`、`/embeddings` |
| 本地兼容（如 LM Studio / vLLM） | `http://localhost:1234/v1` | 同上 |
| Ollama 原生 | `http://localhost:11434` | `/api/chat`、`/api/embed` |

1. 选择接口类型并填写实际模型名称。云服务按供应商要求填写 Key；本地无鉴权服务可以留空。
2. 点击“测试聊天连接” / “测试向量连接”，使用当前未保存表单发送固定测试文本；测试可能产生少量 API 费用，不上传知识库资料。
3. 开启“AI 回答”，点击“保存并应用”，新请求立即生效，无需重启。
4. 可以暂时关闭“向量检索”：系统会用关键词召回原文，再调用聊天模型生成答案。因此只提供聊天接口的供应商也可使用。
5. 需要语义检索时开启“向量检索”，配置真实向量模型，保存后点击“全部文档重新入库”。重建过程中的文档暂不可检索，会显示队列状态。

聊天与向量可以混用不同服务，例如云端聊天 + 本地 Ollama 向量。Ollama 模型需自行安装、拉取并启动，例如 `ollama pull qwen2.5:7b` 和 `ollama pull nomic-embed-text`。Docker 内访问宿主机请用 `host.docker.internal`，不是容器内 localhost。

API Key 在数据库中以 AES-GCM 加密存储，读取配置接口仅返回“已配置”状态，不返回原密钥。留空保留同一接口的旧密钥；更换协议或地址后不会复用旧密钥，可勾选清除。密钥文件为 `backend/data/model-secret.key`（Docker 为持久化卷中的 `/app/data/model-secret.key`），应保护访问权限并与数据库一同备份；丢失后无法解密配置。

无后台保存配置时沿用环境变量 `MODEL_ENABLED`、`MODEL_BASE_URL`、`MODEL_API_KEY`、`CHAT_MODEL`、`EMBEDDING_MODEL`、`EMBEDDING_VERSION`；后台保存后以数据库为准。更换向量模型、地址、协议、版本后，旧向量不再参与召回，需重新入库。每个问答和入库任务使用开始时的配置快照，避免处理中途切换向量空间。

## 统一智能问答

智能问答自动搜索当前用户可访问的全部知识库，无需先选库。权限在数据库候选查询阶段生效，来源卡片标明所属知识库、文档和分块，下载仍校验权限。检索测试默认全库，也可限定单库用于排查。

消息区域独立滚动，输入框固定在底部；Enter 发送，Shift + Enter 换行。左侧为历史会话列表，可切换或删除会话；“新对话”开始一个空白会话。对话记录按账号保存，下次登录仍可查看；每次问题独立检索，请写明主题。

## 检索测试的用途

检索测试只检查“能否找到正确原文”，不调用聊天模型生成答案。展示真实分块、文档名、分块编号、匹配分数。开启向量检索时会调用向量模型，关闭时仅做关键词检索。

- 未找到正确资料：优先检查文档状态、知识库选择、分块及向量配置。
- 能找到资料但问答不准确：再检查聊天模型和提示词。
- 分数用于相对排序，不是答案正确率或概率。

## 已导入的真实公开知识库

导入 4 个主题知识库、6 篇 Kubernetes 官方中文文档：概述、Pod、Deployment、ConfigMap、Secret、Service。来源是 Kubernetes 官方 website 仓库，许可 CC BY 4.0；原始文件、许可证、固定 Git 提交版本和 SHA-256 清单保存在 `corpus/kubernetes`。整理仅移除 Hugo 元数据、模板标记和英文对照注释，模板引用示例请查看官方网页；正文未由 AI 改写。

`docs/corpus-import-report.json` 记录实际入库数量和检索验证。可问：“Pod 是什么？”、“Deployment 如何回滚？”、“ConfigMap 和 Secret 有什么区别？”、“Service 如何发现 Pod？”；在智能问答中直接提问即可，无需选择知识库。

需要在另一份本地数据库导入时，启动系统后执行 `python scripts/import-corpus.py`。脚本使用 `.tools/dev-password.txt` 中的初始管理员密码登录，按库名和文件名跳过已有数据；若已修改账号密码需自行调整认证方式。`scripts/fetch-corpus.py` 用于获取新的官方文档快照，重新下载不会自动覆盖数据库已有文件。

## Docker Compose

```powershell
Copy-Item .env.example .env
# 编辑 .env，设置 ADMIN_PASSWORD 及 DB_PASSWORD
docker compose up --build -d
```

访问 http://localhost:8088。包含 Nginx、Spring Boot、PostgreSQL 16 + pgvector，数据库与原始文件使用持久化卷。数据库和后端不映射公网端口，网页默认只绑定本机。生产部署应接入 HTTPS 反向代理并设置 `COOKIE_SECURE=true`；通过 HTTPS 代理访问时按实际环境调整端口和 Nginx 配置。Nginx 对登录按 IP 限速。

## 验证

```powershell
cd backend
..\.tools\apache-maven-3.9.11\bin\mvn.cmd test package
cd ../frontend
npm run build
```

集成测试覆盖：匿名拒绝、CSRF、知识库权限隔离、阅读者禁止写入、非管理员禁止用户管理、上传→解析→检索→引用→删除、登录 Session、非法上传和参数校验。单元测试覆盖中英文分词、分块边界和向量计算。

## API 概览

所有写操作需要先 GET `/api/auth/csrf`，按返回 `headerName` 发送 `token`，并保留 Session Cookie。

| API | 用途 |
| --- | --- |
| GET `/api/auth/csrf` | 获取 CSRF 令牌 |
| POST `/api/auth/login` | `{username,password}` 登录 |
| POST `/api/auth/logout` | 注销 |
| GET `/api/auth/me` | 当前用户 |
| GET / POST `/api/kbs` | 列表 / 创建 `{name,description}` |
| GET / POST `/api/kbs/{id}/members` | 成员列表 / 添加 `{username}` |
| DELETE `/api/kbs/{id}/members/{uid}` | 撤销成员权限 |
| GET / POST `/api/kbs/{id}/documents` | 文档列表 / multipart `file` 上传 |
| GET `/api/kbs/{id}/documents/{did}/chunks` | 分块预览 |
| GET `/api/kbs/{id}/documents/{did}/download` | 下载原始文档 |
| POST `/api/kbs/{id}/documents/{did}/retry` | 重试或重新索引 |
| DELETE `/api/kbs/{id}/documents/{did}` | 删除文件及索引 |
| POST `/api/search` | `{question,topK,kbId?}` 检索，默认全部有权访问的库；topK 为 1–10 |
| POST `/api/chat` | 同上，返回 `{answer,mode,citations}` |
| GET / POST `/api/admin/users` | 用户列表 / 创建 `{username,password,role}` |
| GET `/api/admin/audit` | 最近 200 条操作审计 |
| GET `/api/system` | 非敏感模型和存储状态 |
| GET / PUT `/api/admin/model-settings` | 脱敏读取 / 保存加密模型配置 |
| POST `/api/admin/model-settings/test/{chat或embedding}` | 测试当前表单连接 |
| POST `/api/admin/model-settings/reindex` | 全部就绪/失败文档重新入库 |
| GET `/actuator/health` | 健康状态 |

## 架构与当前边界

```text
React → Nginx / Vite proxy → Spring Security → REST API
                                             ├─ JDBC / Flyway → PostgreSQL + pgvector
                                             ├─ 原文件存储 → 单实例后台入库队列
                                             └─ OpenAI-compatible embeddings + chat
```

这是企业系统的首版基础实现，尚未完成生产验收，以下为明确边界：

- 后台队列和 Session 目前按单实例设计。重启恢复 PROCESSING 任务；多实例部署前需引入分布式任务租约、共享 Session 和对象存储。
- pgvector 使用无固定维数向量列及精确距离排序，兼容不同模型维数，尚未建立 HNSW/IVFFlat 索引。规模增长后需按向量维数和模型分表/分区建索引并压测。
- H2 / 纯关键词模式使用内存扫描，当前检索范围内超过 20,000 分块会明确拒绝检索。PostgreSQL 向量模式先取 80 个向量候选，再进行关键词加权；这不是独立 BM25 双路召回。
- 按 900 字符切块、150 字符重叠，未按模型 tokenizer 计算；上传提取文本最多 200 万字符。PDF 扫描件无 OCR，复杂排版和表格可能损失结构，来源目前精确到分块而非 PDF 页码。
- 云端模型会收到相关文档分块；有离线要求时配置 Ollama。实际模型可用性和回答质量需接入真实模型后评估。
- 对话记录已按账号持久化保存（会话与消息入库，重新登录后仍可查看）；暂不把历史问题传给模型，每次提问应包含完整主题。尚无流式输出、SSO/LDAP、用户禁用/密码重置、知识库重命名/删除、文件病毒扫描、备份自动化和灾备；上线前需结合业务需求补齐。
- 问答使用资料隔离提示词但不保证消除提示注入；模型输出按纯文本呈现，引用卡片始终来自后端真实检索结果。重要决策需核对原文。
- 审计接口固定展示最近 200 条，数据库保留全部记录；尚无分页导出和归档策略。现有文档和知识库列表未分页。

构建参考：[Spring Boot 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Embeddings API](https://developers.openai.com/api/reference/resources/embeddings/methods/create)、[Chat Completions API](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)。
