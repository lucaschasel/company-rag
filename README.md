# 知序 · 企业 RAG 知识库

面向企业内部的知识库系统：文档上传、向量与关键词检索、智能问答、引用溯源、权限隔离与操作审计。首版可运行实现，Java 17 / Spring Boot 3.5.7 + React 19 / TypeScript。

## 功能特性

- **认证与权限**：Session 登录、BCrypt 密码哈希、CSRF 防护；ADMIN / EDITOR / VIEWER 三种角色。
- **知识库与成员**：创建知识库、按库授予成员访问权限。管理员可见全部；编辑者可创建库并编辑文档；阅读者只读被授权库。
- **文档入库**：TXT / Markdown / PDF / DOCX 上传（单文件 ≤ 20 MB），自动解析分块、状态跟踪、失败重试、重新索引、下载与分块预览。
- **检索与问答**：向量召回 + 关键词加权排序，答案带来源编号、文档名与分块序号溯源；未启用模型时只返回关键词匹配原文。
- **对话记录**：问答按账号持久化保存，左侧历史会话栏可切换、新建、删除，重新登录后仍可查看。
- **模型配置**：OpenAI 兼容接口与 Ollama 原生接口，聊天 / 向量独立配置；API Key 在服务端 AES-GCM 加密存储。
- **用户与审计**：用户创建、成员添加 / 移除、操作审计。
- **部署**：Docker Compose（Nginx + Spring Boot + PostgreSQL 16 + pgvector）。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.5.7、Spring Security、Spring JDBC、Flyway、PDFBox、Apache POI |
| 前端 | React 19、TypeScript、Vite、lucide-react |
| 存储 | 开发环境 H2 文件库；生产环境 PostgreSQL 16 + pgvector |

## 快速开始

### 一键启动 / 停止（Windows，推荐）

项目根目录双击 **Start.cmd**：识别并停止本项目旧进程 → 按需构建后端 → 构建前端 → 等待服务健康 → 自动打开 http://localhost:5173。重复执行相当于安全重启，不影响其他目录的项目。

双击 **Stop.cmd** 停止本项目后端、前端及其子进程，保留数据库、文档、账号与 AI 配置。日志位于 `.tools`。

> 依赖：Java 17、Node 20+；Maven 使用项目 `.tools` 内版本或系统安装版本。

### 手动启动（Windows PowerShell）

终端一：

```powershell
$env:ADMIN_PASSWORD = '请设置初始密码'
cd backend
..\.tools\apache-maven-3.9.11\bin\mvn.cmd spring-boot:run
```

终端二：

```powershell
cd frontend
npm ci
npm run dev
```

访问 http://localhost:5173。开发默认使用 H2 文件数据库（`backend/data`），后端端口 8080，Vite 将 `/api` 代理到后端。

### Docker Compose

```powershell
Copy-Item .env.example .env
# 编辑 .env，设置 ADMIN_PASSWORD 与 DB_PASSWORD
docker compose up --build -d
```

访问 http://localhost:8088。包含 Nginx、Spring Boot、PostgreSQL 16 + pgvector，数据库与原始文件使用持久化卷，网页默认只绑定本机。生产部署应接入 HTTPS 反向代理并设置 `COOKIE_SECURE=true`。

## 默认账号

管理员账号 `admin`。首次启动通过 `ADMIN_PASSWORD` 环境变量设置初始密码；未设置时启动脚本自动生成密码并保存到 `.tools/dev-password.txt`（已 Git 忽略）。`ADMIN_PASSWORD` 只在空库首次创建管理员时生效，已有账号密码不会被覆盖。

## AI 接口设置

以管理员登录，进入「模型与部署 → AI 接口设置」。聊天模型与向量模型独立配置：

| 类型 | 基础地址示例 | 请求接口 |
| --- | --- | --- |
| 云端 OpenAI 兼容 | `https://供应商地址/v1` | `/chat/completions`、`/embeddings` |
| 本地兼容（LM Studio / vLLM） | `http://localhost:1234/v1` | 同上 |
| Ollama 原生 | `http://localhost:11434` | `/api/chat`、`/api/embed` |

- 可只启用「AI 回答」，用关键词召回原文再生成答案；也可开启「向量检索」做语义检索。
- 保存后新请求立即生效；更换向量模型 / 地址 / 版本后需点击「全部文档重新入库」。
- API Key 加密存储，读取接口只返回「已配置」状态。密钥文件为 `backend/data/model-secret.key`，应与数据库一同备份，丢失后无法解密。

## 检索测试与问答

- **检索测试**：只检查「能否找到正确原文」，不调用聊天模型；展示真实分块、文档名、分块编号与匹配分数。
- **智能问答**：自动检索当前用户可访问的全部知识库，返回答案与来源引用；对话记录按账号保存，左侧会话栏可切换与删除。

## 已内置语料

内置 6 篇 Kubernetes 官方中文文档（概述、Pod、Deployment、ConfigMap、Secret、Service），来源为 Kubernetes 官方 website 仓库，许可 CC BY 4.0。原始文件、许可证与 SHA-256 清单保存在 `corpus/kubernetes`。

可直接提问：「Pod 是什么？」「Deployment 如何回滚？」「ConfigMap 和 Secret 有什么区别？」「Service 如何发现 Pod？」。需要导入时执行 `python scripts/import-corpus.py`。

## API 概览

所有写操作需先 `GET /api/auth/csrf` 获取令牌，按返回的 `headerName` 发送 `token`，并保留 Session Cookie。

| API | 用途 |
| --- | --- |
| GET `/api/auth/csrf` | 获取 CSRF 令牌 |
| POST `/api/auth/login` | `{username,password}` 登录 |
| POST `/api/auth/logout` | 注销 |
| GET `/api/auth/me` | 当前用户 |
| GET / POST `/api/kbs` | 知识库列表 / 创建 `{name,description}` |
| GET / POST `/api/kbs/{id}/members` | 成员列表 / 添加 `{username}` |
| DELETE `/api/kbs/{id}/members/{uid}` | 撤销成员权限 |
| GET / POST `/api/kbs/{id}/documents` | 文档列表 / multipart `file` 上传 |
| GET `/api/kbs/{id}/documents/{did}/chunks` | 分块预览 |
| GET `/api/kbs/{id}/documents/{did}/download` | 下载原始文档 |
| POST `/api/kbs/{id}/documents/{did}/retry` | 重试或重新索引 |
| DELETE `/api/kbs/{id}/documents/{did}` | 删除文件及索引 |
| POST `/api/search` | 检索，`{question,topK,kbId?}`，topK 1–10 |
| POST `/api/chat` | 问答，返回 `{answer,mode,citations,conversationId}`；带 `conversationId` 追加会话、带 `title` 新建会话 |
| GET `/api/conversations` | 当前用户的会话列表 |
| GET `/api/conversations/{id}/messages` | 某会话的消息 |
| DELETE `/api/conversations/{id}` | 删除会话 |
| GET / POST `/api/admin/users` | 用户列表 / 创建 `{username,password,role}` |
| GET `/api/admin/audit` | 最近 200 条操作审计 |
| GET `/api/system` | 非敏感模型与存储状态 |
| GET / PUT `/api/admin/model-settings` | 脱敏读取 / 保存加密模型配置 |
| POST `/api/admin/model-settings/test/{chat或embedding}` | 测试当前表单连接 |
| POST `/api/admin/model-settings/reindex` | 全部就绪 / 失败文档重新入库 |
| GET `/actuator/health` | 健康状态 |

## 项目结构

```text
backend/   Spring Boot 后端（Controller、Service、Flyway 迁移、集成测试）
frontend/  React 前端（Vite + TypeScript）
scripts/   启动 / 停止 / 语料导入脚本
corpus/    内置公开知识库语料
docs/      截图与验证报告
examples/  测试上传样例
```

## 架构

```text
React → Nginx / Vite proxy → Spring Security → REST API
                                              ├─ JDBC / Flyway → PostgreSQL + pgvector
                                              ├─ 原文件存储 → 单实例后台入库队列
                                              ├─ 会话与消息 → 按账号持久化
                                              └─ OpenAI-compatible embeddings + chat
```

## 常见问题

- **启动报 H2 文件被占用（90020）**：通常是已有后端占用了同一 H2 文件。后台启动与 Maven 手动启动二选一，勿重复启动。
- **npm ci 报 EPERM / unlink esbuild.exe**：先停止前端（`./scripts/stop-dev.ps1 -FrontendOnly`）再安装，避免运行中的 Vite/esbuild 锁住文件。

## 当前边界

这是企业系统的首版基础实现，尚未完成生产验收：

- 后台入库队列与 Session 按单实例设计；多实例部署前需引入分布式任务租约、共享 Session 与对象存储。
- pgvector 使用精确距离排序，未建 HNSW / IVFFlat 索引；规模增长后需按向量维数分表 / 分区建索引并压测。
- H2 / 纯关键词模式为内存扫描，检索范围超 20,000 分块会拒绝检索；PostgreSQL 向量模式先取 80 个候选再做关键词加权，非独立 BM25 双路召回。
- 按 900 字符切块、150 字符重叠；PDF 扫描件无 OCR，复杂表格可能损失结构，来源精确到分块而非页码。
- 对话历史已持久化，但暂不把历史问题传给模型；每次提问应包含完整主题。
- 尚无流式输出、SSO / LDAP、用户禁用 / 密码重置、知识库重命名 / 删除、文件病毒扫描、备份自动化与灾备。
- 问答使用资料隔离提示词但不保证消除提示注入；引用卡片始终来自后端真实检索结果，重要决策请核对原文。

## 参考

- [Spring Boot 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Embeddings API](https://developers.openai.com/api/reference/resources/embeddings/methods/create)
- [Chat Completions API](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
