# 知序 · 企业知识库

基于 Spring Boot 和 React 的自托管 RAG 知识库，支持文档管理、混合检索、智能问答和权限控制。

上传团队文档，在同一个对话中检索多个知识库，获取带来源引用的回答。支持云端模型和本地模型，聊天与向量接口可独立配置。

[快速开始](#快速开始) · [模型配置](#模型配置) · [部署配置](#部署配置) · [开发指南](#开发指南)

## 功能

- **跨库问答**：自动检索当前用户有权访问的知识库，无需逐一选择。
- **引用溯源**：回答附带来源引用，可查看原始文档、分块及 PDF 页码。
- **历史会话**：按账号保存对话，支持多轮问答、新建、切换、重命名和删除。
- **混合检索**：结合 BM25 关键词评分与向量相似度，支持 PostgreSQL、pgvector 和 HNSW 索引。
- **文档处理**：支持 PDF、DOCX、Markdown 和纯文本，后台完成解析、分块与索引；扫描件可启用 OCR。
- **模型接入**：支持 OpenAI 兼容接口与 Ollama，聊天模型和向量模型分别配置。
- **权限管理**：提供用户角色、知识库成员授权和操作审计，历史会话同样遵循来源权限。
- **存储集成**：支持本地文件和 S3 / MinIO，可接入 Redis 共享会话与 ClamAV 文件扫描。

## 快速开始

### Docker Compose

克隆仓库并创建配置文件：

```bash
git clone https://github.com/lucaschasel/company-rag.git
cd company-rag
cp .env.example .env
```

在 `.env` 中设置 `ADMIN_PASSWORD` 和 `DB_PASSWORD`，然后启动：

```bash
docker compose up --build -d
```

访问 **[localhost:8088](http://localhost:8088)**，使用 `admin` 和配置的密码登录。

Compose 包含前后端服务、PostgreSQL + pgvector 和 Redis。数据库与上传文件保存在持久化卷中。

查看后端日志或停止服务：

```bash
docker compose logs -f backend
docker compose down
```

停止服务会保留数据卷。`ADMIN_PASSWORD` 仅用于首次创建管理员，不会重置已有账号。

### Windows 一键启动

安装 **Java 17**、**Node.js 20+** 和 **Maven 3.9+**，在项目根目录运行：

```powershell
.\Start.cmd
```

也可以直接双击 `Start.cmd`。脚本会构建并启动前后端，打开 **[localhost:5173](http://localhost:5173)**。未设置 `ADMIN_PASSWORD` 时，初始密码自动生成并保存在 `.tools/dev-password.txt`，运行日志位于 `.tools/`。

停止服务：

```powershell
.\Stop.cmd
```

本地启动默认使用 H2 数据库，应用数据保存在 `backend/data/`。

## 使用知识库

创建知识库、添加成员并上传文档。文档状态变为 `READY` 后，即可参与检索和问答。

智能问答默认搜索当前账号可访问的知识库。对话记录按账号保存，回答中的引用可用于查看原始资料。

**检索测试**用于检查问题召回的原文片段，不调用聊天模型；启用向量检索时，会使用配置的向量模型生成查询向量。

### 示例资料

仓库提供六篇 Kubernetes 官方文档，涵盖基础概念、Pod、Deployment、ConfigMap、Secret 和 Service。上传 [corpus/kubernetes](corpus/kubernetes) 中的 Markdown 文件后，可以尝试：

- Pod 是什么？
- 如何回滚 Deployment？
- ConfigMap 和 Secret 有什么区别？

示例资料附带来源、修订信息、校验清单和 CC BY 4.0 许可证，由用户按需导入。

## 模型配置

以管理员登录，在 **AI 接口设置** 中分别配置聊天接口和向量接口，测试连接后保存。

| 接口类型 | 基础地址示例 | 请求接口 |
| --- | --- | --- |
| OpenAI 兼容服务 | `https://your-provider.example/v1` | `/chat/completions`、`/embeddings` |
| 本地 OpenAI 兼容服务 | `http://localhost:1234/v1` | `/chat/completions`、`/embeddings` |
| Ollama | `http://localhost:11434` | `/api/chat`、`/api/embed` |

填写基础地址、模型名称及所需的 API Key，系统会自动拼接请求路径。

AI 回答和向量检索可分别启用。例如，使用云端聊天模型搭配本地向量模型，或先使用关键词检索，再接入向量模型。管理后台保存的配置优先于环境变量中的默认值。

更换向量接口类型、地址、模型或版本后，在设置页面重新索引文档。

后端运行在 Docker 中时，可通过 `host.docker.internal` 访问宿主机模型服务；模型服务需允许来自 Docker 的连接。

API Key 加密存储。备份数据库时应同时备份 `model-secret.key`，本地默认路径为 `backend/data/model-secret.key`。

## 部署配置

基础 [Compose 配置](compose.yaml) 使用 PostgreSQL、Redis 和本地文件存储，网页绑定 `127.0.0.1:8088`。对外提供服务时，通过 HTTPS 反向代理接入，并设置 `COOKIE_SECURE=true`。

### MinIO、OCR 与 ClamAV

可选的 [集成配置](compose.integrations.yaml) 提供 MinIO、存储桶初始化和 ClamAV，并开启后端 OCR。

在私有 `.env` 文件中添加以下配置，将密码占位值替换为实际密码：

```dotenv
MINIO_ROOT_USER=knowledge-admin
MINIO_ROOT_PASSWORD=replace-with-an-admin-password
S3_ACCESS_KEY=knowledge-app
S3_SECRET_KEY=replace-with-a-separate-app-password
S3_BUCKET=knowledge
```

启动集成服务：

```bash
docker compose -f compose.yaml -f compose.integrations.yaml up --build -d
```

MinIO 控制台位于 **[localhost:9001](http://localhost:9001)**。后端使用独立的应用账号访问文档存储桶。ClamAV 首次启动时会初始化病毒库。

停止时使用相同的配置文件：

```bash
docker compose -f compose.yaml -f compose.integrations.yaml down
```

### 环境变量

Compose 从 `.env` 读取变量。直接启动 Java 服务时，通过进程环境变量配置。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ADMIN_PASSWORD` | — | 管理员初始密码 |
| `SPRING_PROFILES_ACTIVE` | — | 启用 `postgres`、`redis`，或同时启用 |
| `DB_URL`、`DB_USER`、`DB_PASSWORD` | 本地 H2 配置 | 数据库连接 |
| `REDIS_HOST`、`REDIS_PORT` | `localhost`、`6379` | redis profile 下的连接配置 |
| `STORAGE_TYPE` | `local` | 文件存储类型：`local` 或 `s3` |
| `STORAGE_PATH` | `./data/uploads` | 本地上传目录 |
| `S3_BUCKET`、`S3_ENDPOINT`、`S3_REGION` | 区域：`us-east-1` | S3 兼容存储配置 |
| `S3_ACCESS_KEY`、`S3_SECRET_KEY` | — | 对象存储凭证 |
| `S3_PATH_STYLE` | `true` | 使用路径式对象寻址 |
| `CHUNK_MAX_TOKENS` | `512` | 目标分块大小，按估算 token 数计算 |
| `CHUNK_OVERLAP_TOKENS` | `64` | 相邻分块重叠大小 |
| `OCR_ENABLED` | `false` | 对提取文字较少的 PDF 页面启用 OCR |
| `OCR_ALL_PAGES` | `false` | 对所有 PDF 页面执行 OCR |
| `OCR_LANGUAGES` | `chi_sim+eng` | Tesseract 识别语言 |
| `OCR_TIMEOUT_SECONDS` | `60` | 单页 OCR 进程超时秒数 |
| `CLAMD_HOST`、`CLAMD_PORT` | 未启用、`3310` | 设置主机地址后启用 ClamAV |
| `COOKIE_SECURE` | 随 profile 配置 | HTTPS 部署设为 `true` |

后端容器已包含 Tesseract 和简体中文语言包。本地 Java 部署需单独安装 Tesseract，未加入 PATH 时通过 `OCR_EXECUTABLE` 指定路径。也可以使用 `OCR_COMMAND` 配置自定义 OCR 命令，支持 `{file}`、`{page}` 占位符，并从标准输出读取 UTF-8 文本。

单个文件最大 20 MB，支持 PDF、DOCX、UTF-8 TXT 和 Markdown。文档处理完成后开放下载。

备份应同时包含数据库、原始文档和模型加密密钥。从本地存储迁移至 S3 时，先按原文件名复制已有对象，再切换存储配置。

## 开发指南

### 本地运行

开发环境需要 Java 17、Maven 3.9+ 和 Node.js 20+。

在 PowerShell 中启动后端：

```powershell
$env:ADMIN_PASSWORD = '替换为管理员初始密码'
cd backend
mvn spring-boot:run
```

另开终端启动前端：

```bash
cd frontend
npm ci
npm run dev
```

Vite 在 5173 端口提供前端页面，并将 API 请求代理至 8080 端口的后端服务。

### 构建与测试

后端：

```bash
cd backend
mvn package
```

前端：

```bash
cd frontend
npm ci
npm run build
```

PostgreSQL 集成测试通过 `TEST_POSTGRES_URL`、`TEST_POSTGRES_USER`、`TEST_POSTGRES_PASSWORD` 连接测试数据库。准备好支持 pgvector 的测试库后，在 `backend/` 下运行 `mvn -Dtest=PostgresVectorTest test`。

### 目录结构

```text
backend/      Spring Boot 接口、文档处理、检索、数据库迁移与测试
frontend/     React 应用与 Nginx 配置
scripts/      开发环境启停与语料工具
corpus/       附来源信息的示例资料
docs/         技术文档
examples/     上传样例
```

后端使用 Spring Security、JDBC、Flyway、PDFBox 和 Apache POI；前端使用 React 19、TypeScript 和 Vite。

### API

REST 接口统一使用 `/api` 前缀，通过 Session Cookie 认证。发送写请求前，调用 `GET /api/auth/csrf` 获取 CSRF 令牌。

| 接口 | 用途 |
| --- | --- |
| `/api/auth` | 认证与登录会话 |
| `/api/kbs` | 知识库、成员与文档管理 |
| `POST /api/search` | 检索 |
| `POST /api/chat` | 问答 |
| `POST /api/chat/stream` | SSE 流式问答 |
| `/api/conversations` | 历史会话 |
| `/api/admin/model-settings` | 模型配置与连接测试 |
| `/api/admin/users` | 用户管理 |
| `/api/admin/audit` | 操作审计 |
| `GET /actuator/health` | 服务健康检查 |

流式问答提供 `meta`、`delta`、`done` 和 `error` 事件。两种问答接口均可通过传入会话 ID 继续已有对话。
