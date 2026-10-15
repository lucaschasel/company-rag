# 2026-10-15 检索与文件处理修复

## 本次行为

- PostgreSQL 不再直接对不定维数 vector 列建 HNSW。按 embedding_model 的 SHA-256 签名与维数建立独立表达式/部分索引，查询使用相同表达式。启动 15 秒后检查，此后每 5 分钟检查新模型；并发建索引使用数据库锁，构建失败保留精确查询能力。vector HNSW 仅为 1–2000 维创建；更高维数保留精确距离查询。H2 不使用该索引。
- 会话列表、历史读取及两种问答接口重新检查整个会话引用的来源权限。来源被撤权、删除或处于非 READY 状态时，整个会话不再展示或用于续问。重新授权且资料恢复 READY 后可再次访问。已发送给浏览器或用户已下载的内容无法远程撤回。
- 未 READY 的文件不提供下载和分块预览；PDF 文件头、DOCX ZIP 结构、UTF-8 文本进行基础检查。配置 ClamAV 后通过分块 INSTREAM 协议扫描，连接/读取超时或非明确 clean 结果均不放行。现存 READY 文档需要重新入库才能采用新检查。
- OCR 支持 PDFBox 渲染页面后执行 Tesseract；低文本量页可自动识别，混合页面可强制识别全部页。外部命令保留兼容，先分词再替换路径，支持 JSON 参数数组；输出与错误分离、限制超时和输出大小，并清理子进程及临时文件。
- S3 使用可配置的 path-style 寻址，适配 MinIO；失败下载清理临时文件。Redis Session 仅在 redis profile 激活时启用；本地默认使用容器内 Session。Nginx 关闭 API 响应缓冲以支持 SSE。

## OCR

Docker 后端镜像已加入 Tesseract 和简体中文语言包，设置 OCR_ENABLED=true 即启用；本机 Java 运行需另行安装 Tesseract，必要时指定 OCR_EXECUTABLE 的绝对路径。

- OCR_LANGUAGES=chi_sim+eng
- OCR_MIN_TEXT_CHARS=20：少于此字符数的页尝试识别。
- OCR_ALL_PAGES=true：强制识别每页，适合混合扫描件。
- OCR_TIMEOUT_SECONDS=60：单页外部程序超时。
- OCR_COMMAND 可为空；自定义命令需将识别文本以 UTF-8 输出到 stdout。支持 {file}、{page}；JSON 参数数组更适合带空格路径。

这不是表格结构恢复或识别准确率保证；当前机器未安装实际 Tesseract，已用受控外部进程验证参数、输出和超时。

## MinIO / ClamAV 可选集成部署

compose.integrations.yaml 增加 MinIO、建桶/应用用户初始化、ClamAV，并为后端开启 S3、OCR 和扫描。先在私有 .env 中设置 DB_PASSWORD、ADMIN_PASSWORD、MINIO_ROOT_USER、MINIO_ROOT_PASSWORD、S3_ACCESS_KEY、S3_SECRET_KEY。应用使用独立用户及桶级读写策略，不复用 MinIO 管理员。S3_BUCKET 默认 knowledge，仅使用合法桶名。首次 ClamAV 下载病毒库可能较慢。

启动命令：docker compose -f compose.yaml -f compose.integrations.yaml up --build -d

MinIO 控制台绑定 127.0.0.1:9001；对象 API 和扫描服务仅在 Docker 内网提供。镜像当前使用上游 latest/stable 标签，生产部署请在验证后固定版本或摘要。

注意：切换 STORAGE_TYPE 不会自动迁移原文件。已有本地数据需先备份并在停写期间，将 uploads 下对象按原 UUID 文件名复制到桶中，核对数量与内容后再切换；不要直接切换并删除本地文件。此轮未修改现有存储配置，也未执行任何数据迁移。

## PostgreSQL 迁移兼容

V6 原脚本对原始不定维数列无法正常建索引，本次修正了该迁移。H2 不加载 V6，已有 H2 数据不受迁移校验影响。若某个 PostgreSQL 环境曾手工修改列维数并成功执行旧 V6，升级前需备份、检查 flyway_schema_history 与实际索引，按受控流程处理 V6 校验；本程序不会自动 repair 或修改历史记录。

可设置 TEST_POSTGRES_URL、TEST_POSTGRES_USER、TEST_POSTGRES_PASSWORD 后执行 Maven 的 PostgresVectorTest，验证全部迁移、混合维数、实际创建索引和 EXPLAIN 使用索引。该测试只操作随机生成的临时 schema，结束删除该 schema；应使用测试数据库。

## 验证范围

默认测试覆盖 H2 权限与文档流程、本机 ClamAV 协议模拟、S3 HTTP 协议模拟、OCR 参数与超时，以及原有模型协议测试。Compose 配置解析已通过。

本机 Docker Engine 未启动，未运行真实 PostgreSQL/pgvector、MinIO、Redis、ClamAV 容器，也未验证真实 OCR 识别质量；PostgresVectorTest 在未提供测试数据库时明确跳过。新增能力不能据此视为已通过生产验收。

最终执行：Maven package 成功，24 项测试中 23 项通过、1 项 PostgresVectorTest 跳过，0 失败、0 错误。docker compose 双文件 config -q 和 git diff --check 通过。未运行生产数据库迁移、未启动外部服务、未修改管理员密码或模型配置。
