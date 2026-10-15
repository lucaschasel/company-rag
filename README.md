# 知序 · 企业 RAG 知识库

基于 Java / Spring Boot 与 React / TypeScript 的企业内部知识库系统。员工可以直接提问，系统在其有权访问的知识库中检索资料，以流式回答展示结果，并提供文档、分块和 PDF 页码引用。

支持云端 OpenAI 兼容接口、Ollama 原生接口及本地兼容模型服务。聊天模型与向量模型独立配置；无需模型也能先体验文档管理和关键词检索。

## 当前能力

| 模块 | 已实现 |
| --- | --- |
| 知识库 | 创建知识库、成员授权、文档上传与状态跟踪、重试与重新索引 |
| 智能问答 | 跨授权知识库检索、SSE 流式输出、最近 12 条历史消息上下文、来源引用 |
| 历史会话 | 按账号持久化，新建、切换、重命名、删除；读取与续问时重新校验来源权限 |
| 检索 | BM25 关键词评分与向量融合；PostgreSQL 下按模型签名与维数建立 HNSW 索引 |
| 文档处理 | TXT、Markdown、PDF、DOCX，单文件最多 20 MB；估算 token 分块，PDF 页码引用 |
| 可选 OCR | PDF 页面渲染后调用 Tesseract，或接入自定义 OCR 命令 |
| 文件检查 | 文件头、DOCX 结构、UTF-8 文本检查；可接入 ClamAV 扫描 |
| 账号与权限 | Session、BCrypt、CSRF、ADMIN / EDITOR / VIEWER、知识库成员权限、操作审计 |
| 模型设置 | 管理后台编辑地址、模型、密钥，测试连接；API Key 使用 AES-GCM 加密保存 |
| 存储与部署 | H2 / PostgreSQL + pgvector、本地文件 / S3 / MinIO、可选 Redis Session、Docker Compose |

当前版本提供可运行的实现与自动化测试，仍有明确的部署和检索边界，见[验证与当前限制](#验证与当前限制)。本次修复的细节见 [2026-10-15 修复记录](docs/REPAIR-NOTES.md)。

## 技术与结构

- 后端：Java 17、Spring Boot 3.5.7、Spring Security、JDBC、Flyway、PDFBox、Apache POI。
- 前端：React 19、TypeScript、Vite。
- 数据：本地开发默认 H2；Compose 使用 PostgreSQL 16 + pgvector、Redis。
- 文件：默认本地磁盘，可切换 S3 兼容对象存储。

```text
浏览器 React
  └─ Vite 代理 / Nginx
       └─ Spring Security + REST / SSE
            ├─ 用户、知识库、会话、分块 → H2 / PostgreSQL
            ├─ 文档处理 → 文件检查 → 解析 / OCR → 分块 → 向量生成
            ├─ 原始文件 → 本地磁盘 / S3
            ├─ 检索与生成 → OpenAI 兼容服务 / Ollama
            └─ 登录会话 → 默认内存 / Redis profile
```

```text
backend/                    Spring Boot 服务、数据库迁移与测试
frontend/                   React 页面及 Nginx 配置
scripts/                    Windows 启停、公开语料获取与导入
corpus/kubernetes/          公开文档、来源、许可证和校验清单
docs/                       修复说明与验证资料
examples/                   上传样例
compose.yaml                基础容器部署
compose.integrations.yaml   可选 MinIO、ClamAV 与 OCR 配置
```

## 快速开始

### Windows 一键启动

准备 Java 17、Node.js 20+、Maven 3.9+，并加入 PATH。首次构建需要下载 Maven 与 npm 依赖。

```powershell
git clone https://github.com/lucaschasel/company-rag.git
cd company-rag
.\Start.cmd
```

启动后访问 [本地界面](http://localhost:5173)。也可以直接双击根目录的 **Start.cmd**。

启动脚本会停止本目录旧服务、按需构建后端、安装或更新前端依赖并构建前端，等待服务就绪后打开浏览器。如果其他项目占用 8080 或 5173 端口，脚本报错退出。

双击 **Stop.cmd** 或运行以下命令停止本项目服务，数据库和文档会保留：

```powershell
.\Stop.cmd
```

日志保存在 `.tools/`。脚本优先使用已有的 `.tools/apache-maven-3.9.11/`，否则使用系统 Maven；Git 仓库不包含这份本地工具目录。

**管理员账号为 `admin`。** 空数据库首次启动时使用环境变量 `ADMIN_PASSWORD`；一键脚本未收到此变量时生成随机初始密码，保存在本地 `.tools/dev-password.txt`。已有账号密码不会被环境变量或脚本覆盖。项目不提供统一默认密码。

### 手动启动

在项目根目录打开两个 PowerShell 终端。

后端：

```powershell
$env:ADMIN_PASSWORD = '替换为你设置的初始密码'
cd backend
mvn spring-boot:run
```

前端：

```powershell
cd frontend
npm ci
npm run dev
```

默认后端端口 8080；前端通过 Vite 代理访问 API。以 `backend` 为工作目录启动时，H2 数据库、文件与模型加密密钥保存在 `backend/data/`。

`.env` 用于 Docker Compose 的变量替换；上述本机启动方式不会自动加载它。本机配置请使用环境变量。

### Docker Compose

需安装并启动 Docker Engine 和 Compose。

```powershell
Copy-Item .env.example .env
# 编辑 .env，填写 ADMIN_PASSWORD 与 DB_PASSWORD
docker compose up --build -d
```

访问 [容器部署界面](http://localhost:8088)。基础部署包含 Nginx、后端、PostgreSQL + pgvector 和 Redis，原始文件仍使用本地持久化卷。网页仅绑定本机地址，数据库和 Redis 不向主机发布端口。

```powershell
docker compose logs -f backend
docker compose down
```

`down` 默认保留数据卷；不要给日常停止命令添加 `-v`。对外提供服务时应配置 HTTPS，并将 `COOKIE_SECURE=true`。

**已有 PostgreSQL 环境升级前请阅读 [V6 迁移兼容说明](docs/REPAIR-NOTES.md#postgresql-迁移兼容)。** 本次修正了原本不能直接用于不定维数向量列的 V6 索引脚本；若旧 V6 曾经成功执行，需要检查 Flyway 校验与实际索引，不能直接假定升级无影响。应用不会自动修复迁移历史。

## 配置 AI 接口

以管理员登录，在「AI 接口设置」分别配置聊天模型和向量模型，测试连接后保存。

| 接口类型 | 基础地址示例 | 系统调用 |
| --- | --- | --- |
| OpenAI 兼容云服务 | `https://供应商地址/v1` | `/chat/completions`、`/embeddings` |
| 本地 OpenAI 兼容服务 | `http://localhost:1234/v1` | 同上，服务必须实现相应接口 |
| Ollama 原生 | `http://localhost:11434` | `/api/chat`、`/api/embed` |

地址应填写基础地址，不要重复填写完整请求路径。模型名称和可用功能以实际部署或供应商提供的接口为准。项目不会自动安装或下载模型。

- **AI 回答**控制生成答案；关闭时可查看检索到的原文。
- **向量检索**控制语义召回；关闭时使用关键词检索。
- 两者可以分别启用，也可以使用不同供应商。只有聊天接口的供应商不能替代 embedding 接口。
- 管理后台保存的配置优先于环境变量，后续请求使用保存结果。
- 更换向量接口类型、地址、模型或版本后，应执行「全部文档重新入库」，使资料向量与查询向量一致。
- 容器内的 `localhost` 指向容器自身。访问宿主机模型服务时，使用可访问的宿主机地址，例如基础 Compose 已配置的 `host.docker.internal`。

API Key 不通过读取接口回传明文。默认加密密钥文件为 `backend/data/model-secret.key`；自定义 `STORAGE_PATH` 时，密钥位于上传目录的同级。备份必须包含数据库和此密钥，丢失密钥将无法解密已有模型配置。

## 使用流程与权限

1. 管理员创建用户；管理员或编辑者创建知识库并管理资料。
2. 为知识库添加需要访问的成员，上传文档，等待状态变为 `READY`。
3. 在「检索测试」检查问题是否能命中正确资料，再进入智能问答验证答案及引用。
4. 普通员工可以直接在智能问答中提问，系统默认搜索其有权访问的全部知识库。

**检索测试**只检查召回结果，不调用聊天模型；启用向量检索时仍会调用 embedding 接口。它适合排查资料未入库、分块不合适、关键词不匹配或向量配置错误。

全局角色和知识库成员授权共同控制访问，用户不能通过改写知识库 ID 绕过服务端校验。会话属于当前账号，读取历史和继续提问时还会检查整个会话所引用的来源。

来源被撤权、删除或处于非 `READY` 状态时，相关会话会从列表隐藏，并拒绝读取和续问；重新授权且资料恢复就绪后可再次访问。这不会删除历史记录，也无法撤回已经发送到浏览器或用户已下载的内容。

文档在处理完成前不开放下载和分块内容。已有 `READY` 文档需要重新入库，才能应用本次新增的文件检查。

## 导入公开知识库

仓库附带 6 篇 Kubernetes 官方中文资料：概述、Pod、Deployment、ConfigMap、Secret、Service。资料依据 CC BY 4.0 使用，来源、修订信息、许可证和 SHA-256 清单位于 [corpus/kubernetes](corpus/kubernetes)。

**文件随仓库提供，空数据库不会自动导入。** 可以直接通过页面上传其中的 Markdown 文件。

本地默认服务也可以使用 Python 3 导入：

```powershell
python scripts/import-corpus.py
```

脚本固定连接 `http://localhost:8080/api`，使用 `admin` 和本地 `.tools/dev-password.txt` 登录。只有该文件中的密码仍与账号匹配时才能直接使用；自定义密码、修改过密码或使用容器端口时，优先通过页面上传。不要将实际凭证写入脚本并提交。

导入后可以测试：「Pod 是什么？」「Deployment 如何回滚？」「ConfigMap 和 Secret 有什么区别？」。脚本会生成本次导入报告；仓库中的历史报告不代表新环境已经导入成功。

## OCR、ClamAV 与对象存储

### OCR

Docker 后端镜像包含 Tesseract 和简体中文语言包，设置 `OCR_ENABLED=true` 即启用。直接运行 Java 时需自行安装 Tesseract，可通过 `OCR_EXECUTABLE` 指定路径。

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `OCR_ENABLED` | `false` | 启用内置 OCR 流程 |
| `OCR_MIN_TEXT_CHARS` | `20` | 页面文本少于此字符数时尝试 OCR |
| `OCR_ALL_PAGES` | `false` | 强制识别所有页面，适用于混合扫描件 |
| `OCR_LANGUAGES` | `chi_sim+eng` | Tesseract 语言 |
| `OCR_TIMEOUT_SECONDS` | `60` | 单页外部程序超时 |
| `OCR_COMMAND` | 空 | 自定义 OCR 命令，可替代 Tesseract |

自定义命令支持 `{file}` 与 `{page}`，将识别文字以 UTF-8 输出到 stdout。可以使用 JSON 参数数组处理带空格的路径。OCR 是文字提取流程，不保证表格结构恢复或识别准确率。

### ClamAV

设置 `CLAMD_HOST` 后，后端通过 INSTREAM 协议将文件发送给扫描服务。默认端口 `CLAMD_PORT=3310`，连接和读取超时由 `CLAMD_TIMEOUT_MS=15000` 控制。非明确通过的响应不放行；未配置地址时不会进行病毒扫描。

文件格式检查不能替代病毒扫描，病毒扫描也不能保证识别所有恶意文件。

### S3 / MinIO

设置 `STORAGE_TYPE=s3`，并配置 `S3_BUCKET`、`S3_ENDPOINT`、`S3_REGION`、`S3_ACCESS_KEY`、`S3_SECRET_KEY`。默认 `S3_PATH_STYLE=true` 适配 MinIO；其他服务按实际要求调整。

本地集成环境可在私有 `.env` 中补充 MinIO 管理员和独立应用凭证：

- `MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`
- `S3_ACCESS_KEY`、`S3_SECRET_KEY`
- 可选 `S3_BUCKET`，默认 `knowledge`

```powershell
docker compose -f compose.yaml -f compose.integrations.yaml up --build -d
```

该覆盖配置会启用 MinIO、建桶和应用用户初始化、ClamAV，以及后端 OCR / S3 / 扫描。MinIO 控制台为 [localhost:9001](http://localhost:9001)；应用账号只获得指定桶的读写权限。ClamAV 首次下载病毒库可能需要较长时间。停止时使用相同的两个 `-f` 参数和 `down`。

**切换存储类型不会自动搬迁文件。** 有数据的环境需先备份，在停写期间将本地对象按原 UUID 文件名复制到桶中，核对后再切换。完整步骤和验证边界见 [修复记录](docs/REPAIR-NOTES.md)。

## 检索实现与运行配置

默认按估算 token 数分块：`CHUNK_MAX_TOKENS=512`，重叠 `CHUNK_OVERLAP_TOKENS=64`。这不是模型专用 tokenizer 的精确计数。

关键词采用 BM25；启用向量后融合向量相似度与归一化 BM25 分数。PostgreSQL 向量路径先取最多 80 个向量候选再融合排序，并非关键词与向量分别召回后合并；H2 或关键词路径最多读取 20,000 个分块参与计算。

pgvector 负责存储向量与距离运算，HNSW 用来加速近邻查询。系统按模型签名和维数创建独立的表达式 / 部分索引，启动 15 秒后检查，此后每 5 分钟检查一次。当前仅为 1–2000 维建立 HNSW；更高维数保留精确距离查询。索引构建失败时保留精确查询能力，H2 不使用 HNSW。

Redis Session 仅在 `redis` profile 下启用，基础 Compose 已激活 `postgres,redis`。共享 Session 与 S3 是多实例部署的组成部分，但不代表完整的多实例可靠性已验收：入库租约默认 300 秒，目前没有续租和 fencing，长任务可能被其他实例重复领取。多个实例还需要共享同一份模型配置加密密钥。

## API 概览

写操作需先获取 `GET /api/auth/csrf`，按响应中的 `headerName` 发送 `token`，并保留登录 Session Cookie。

| 接口 | 用途 |
| --- | --- |
| `/api/auth/login`、`/logout`、`/me` | 登录、退出、当前账号（均位于 /api/auth 下） |
| `/api/kbs` | 知识库列表与创建 |
| `/api/kbs/{id}/members` | 成员授权管理 |
| `/api/kbs/{id}/documents` | 文档列表与上传 |
| `/api/kbs/{id}/documents/{did}/chunks`、`/download`、`/retry` | 文档分块、下载、重新入库（相同文档前缀） |
| `POST /api/search` | 检索，参数含 question、topK、可选 kbId；topK 为 1–10 |
| `POST /api/chat` | 非流式问答 |
| `POST /api/chat/stream` | SSE 问答，事件 meta / delta / done / error |
| `/api/conversations`、`/{id}/messages` | 当前账号会话列表与消息（相同会话前缀） |
| `PUT / DELETE /api/conversations/{id}` | 重命名 / 删除会话 |
| `/api/admin/users`、`/api/admin/audit` | 用户与操作审计 |
| `GET / PUT /api/admin/model-settings` | 读取脱敏模型配置 / 保存 |
| `POST /api/admin/model-settings/test/{chat或embedding}` | 测试接口 |
| `POST /api/admin/model-settings/reindex` | 重新入库 |
| `GET /api/system`、`GET /actuator/health` | 系统状态与健康检查 |

## 验证与当前限制

后端测试与打包：

```powershell
cd backend
mvn package
```

前端类型检查和构建：

```powershell
cd frontend
npm ci
npm run build
```

截至 2026-10-15，本轮后端打包成功：**24 项测试中 23 项通过，1 项 PostgreSQL 集成测试跳过，0 失败、0 错误**。覆盖 H2 权限与历史撤权、文档隔离、模拟 ClamAV / S3 协议、OCR 进程参数与超时，以及模型协议与分块测试。双文件 Compose 配置解析和 Git 差异空白检查通过。

真实 pgvector 测试需要单独提供测试数据库，并安装或允许创建 vector 扩展：

```powershell
$env:TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/rag_test'
$env:TEST_POSTGRES_USER = '测试数据库用户名'
$env:TEST_POSTGRES_PASSWORD = '测试数据库密码'
cd backend
mvn -Dtest=PostgresVectorTest test
```

该测试创建并清理随机 schema，验证迁移、混合维数索引和 EXPLAIN 查询计划；请使用测试数据库。基础 Compose 默认不发布 PostgreSQL 端口，不能直接把以上示例地址当作已可连接的服务。

本轮没有运行真实 PostgreSQL / pgvector、Redis、MinIO、ClamAV 容器，也没有验证真实 OCR 识别质量。模拟协议测试和配置解析不能替代外部服务的部署验收。

其他当前边界：

- 多轮生成会携带最近历史消息，但检索仍使用当前问题，没有基于上下文改写追问。
- 检索候选数量和估算分块方式需要结合实际语料评估，尚不具备大规模检索质量保证。
- 分布式租约缺少续租与 fencing，不应直接视为长任务下的可靠多实例处理。
- 暂无企业 SSO；生产部署需补充身份接入、备份恢复演练、监控和容量验证。
- 可选集成镜像使用 latest / stable 标签，正式部署应在验证后固定版本或摘要。

## 故障排查与备份

- **npm ci 提示 EPERM / unlink esbuild.exe**：先运行 `.\Stop.cmd` 停止本项目 Vite，再安装依赖，避免可执行文件被占用。
- **H2 提示文件被占用**：不要同时启动一键脚本和手动后端；先停止已有服务。
- **模型测试连接失败**：核对接口类型、基础地址、模型名称，以及后端所在主机或容器能否访问服务。
- **问答找不到资料**：先确认文档为 READY、账号有权限，再用检索测试检查分块；向量配置变更后重新入库。
- **历史会话消失**：检查其中任一引用来源是否被撤权、删除或正在重新入库。
- **启用 ClamAV 后入库失败**：确认服务就绪、病毒库可用以及后端能连接扫描端口。

备份至少包括数据库、原始文件或对象桶、`model-secret.key`。私有 `.env`、`.tools/` 和数据目录不应提交到 Git。切换存储或处理 Flyway 历史前先备份，并验证恢复路径。
