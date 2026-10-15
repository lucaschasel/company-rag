# 知序 · Knowledge Hub

A self-hosted knowledge base for teams, built with Spring Boot and React.

Upload documents, organize access, and ask questions across your team's knowledge. Answers stream into persistent conversations with citations back to the source documents. Connect a cloud provider or run your models locally.

[Getting started](#getting-started) · [Model configuration](#model-configuration) · [Deployment](#deployment) · [Development](#development)

## Features

- **Search across knowledge bases.** Query every collection you have access to from a single conversation.
- **Answers with sources.** Follow citations to the document, text chunk, or PDF page behind an answer.
- **Persistent conversations.** Continue previous discussions, rename sessions, and keep separate conversation histories for each account.
- **Hybrid retrieval.** Combine BM25 keyword scoring with vector similarity, backed by PostgreSQL and pgvector HNSW indexes.
- **Document ingestion.** Upload PDF, DOCX, Markdown, and plain text. Extract, split, and index content in the background, with optional OCR for scanned PDFs.
- **Your choice of models.** Configure chat and embedding models independently through OpenAI-compatible endpoints or Ollama.
- **Access management.** Assign roles, manage knowledge base membership, and review audit events. Source permissions also apply to conversation history.
- **Flexible storage.** Use local files or S3-compatible object storage, including MinIO. Enable Redis for shared sessions and ClamAV for file scanning.

## Getting started

### Docker Compose

Clone the repository and create your configuration:

```bash
git clone https://github.com/lucaschasel/company-rag.git
cd company-rag
cp .env.example .env
```

Set `ADMIN_PASSWORD` and `DB_PASSWORD` in `.env`, then start the stack:

```bash
docker compose up --build -d
```

Open **[localhost:8088](http://localhost:8088)** and sign in as `admin` with the password you configured.

Compose starts the web application, PostgreSQL with pgvector, and Redis. Database records and uploaded files are stored in persistent volumes.

```bash
docker compose logs -f backend
docker compose down
```

Stopping the stack preserves its data volumes. `ADMIN_PASSWORD` initializes the administrator on the first run; it does not reset an existing account.

### Windows

Install **Java 17**, **Node.js 20+**, and **Maven 3.9+**, then run:

```powershell
.\Start.cmd
```

The launcher builds the application, starts both services, and opens **[localhost:5173](http://localhost:5173)**. If `ADMIN_PASSWORD` is not set, it generates an initial password in `.tools/dev-password.txt`. Logs are written to `.tools/`.

To stop the application:

```powershell
.\Stop.cmd
```

The local setup uses an H2 database and stores application data in `backend/data/`.

## Using the knowledge base

Create a knowledge base, add its members, and upload documents. Once a document reaches `READY`, it is available for retrieval and chat.

Chat searches across the signed-in user's accessible knowledge bases. Conversations are saved per account, and citations let readers inspect the original material.

Use **Retrieval Test** to inspect the passages returned for a question before generating an answer. It runs the retrieval step without calling the chat model; when vector retrieval is enabled, it uses the configured embedding model.

### Sample documents

The repository includes six Kubernetes documentation samples covering core concepts, Pods, Deployments, ConfigMaps, Secrets, and Services. Upload the Markdown files from [corpus/kubernetes](corpus/kubernetes) to try questions such as:

- What is a Pod?
- How do I roll back a Deployment?
- When should I use a Secret instead of a ConfigMap?

The corpus includes source references, revision information, checksums, and its CC BY 4.0 license. Samples are imported explicitly rather than seeded into new databases.

## Model configuration

Sign in as an administrator and open **AI Interface Settings**. Configure and test the chat and embedding endpoints separately, then save.

| Provider | Example base URL | Endpoints |
| --- | --- | --- |
| OpenAI-compatible service | `https://your-provider.example/v1` | `/chat/completions`, `/embeddings` |
| Local OpenAI-compatible server | `http://localhost:1234/v1` | `/chat/completions`, `/embeddings` |
| Ollama | `http://localhost:11434` | `/api/chat`, `/api/embed` |

Enter the base URL, model name, and any required API key. The application appends the endpoint path.

Chat generation and vector retrieval can be enabled independently. For example, use a hosted chat model with a local embedding model, or start with keyword retrieval and add embeddings later. Saved settings take precedence over environment defaults.

After changing the embedding provider, URL, model, or version, reindex documents from the settings page.

When the backend runs in Docker, use `host.docker.internal` to reach a model server on the host, provided that server accepts connections from Docker.

API keys are encrypted at rest. Back up `model-secret.key` with the database; the default local path is `backend/data/model-secret.key`.

## Deployment

The base [Compose configuration](compose.yaml) uses PostgreSQL, Redis, and local file storage. The web interface binds to `127.0.0.1:8088`. For external access, place it behind an HTTPS reverse proxy and set `COOKIE_SECURE=true`.

### MinIO, OCR, and ClamAV

An optional [Compose configuration](compose.integrations.yaml) adds MinIO, bucket initialization, and ClamAV, and enables OCR in the backend.

Add these values to your private `.env`:

```dotenv
MINIO_ROOT_USER=knowledge-admin
MINIO_ROOT_PASSWORD=replace-with-an-admin-password
S3_ACCESS_KEY=knowledge-app
S3_SECRET_KEY=replace-with-a-separate-app-password
S3_BUCKET=knowledge
```

Start the combined stack:

```bash
docker compose -f compose.yaml -f compose.integrations.yaml up --build -d
```

The MinIO console is available at **[localhost:9001](http://localhost:9001)**. The backend uses a separate application account with access to the document bucket. ClamAV initializes its virus database on first startup.

Use the same Compose files when stopping the stack:

```bash
docker compose -f compose.yaml -f compose.integrations.yaml down
```

### Configuration reference

Compose reads variables from `.env`. For a directly launched Java process, set them in the process environment.

| Setting | Default | Description |
| --- | --- | --- |
| `ADMIN_PASSWORD` | — | Initial administrator password |
| `SPRING_PROFILES_ACTIVE` | — | Enable `postgres`, `redis`, or both |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Local H2 configuration | Database connection |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | Redis connection with the redis profile |
| `STORAGE_TYPE` | `local` | `local` or `s3` |
| `STORAGE_PATH` | `./data/uploads` | Local upload directory |
| `S3_BUCKET`, `S3_ENDPOINT`, `S3_REGION` | Region: `us-east-1` | S3-compatible storage |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY` | — | Object storage credentials |
| `S3_PATH_STYLE` | `true` | Path-style object addressing |
| `CHUNK_MAX_TOKENS` | `512` | Target chunk size, using estimated token counts |
| `CHUNK_OVERLAP_TOKENS` | `64` | Overlap between adjacent chunks |
| `OCR_ENABLED` | `false` | Enable OCR for PDF pages with little extracted text |
| `OCR_ALL_PAGES` | `false` | Run OCR on every PDF page |
| `OCR_LANGUAGES` | `chi_sim+eng` | Tesseract language selection |
| `OCR_TIMEOUT_SECONDS` | `60` | Per-page OCR process timeout |
| `CLAMD_HOST`, `CLAMD_PORT` | Disabled, `3310` | Enable ClamAV by setting its host |
| `COOKIE_SECURE` | Profile-dependent | Set to `true` for HTTPS deployments |

The backend container includes Tesseract and Simplified Chinese language data. For local Java deployments, install Tesseract separately and set `OCR_EXECUTABLE` if it is not on PATH. Custom OCR commands can be configured with `OCR_COMMAND`, using `{file}` and `{page}` placeholders and UTF-8 text on stdout.

File uploads support PDF, DOCX, UTF-8 TXT, and Markdown, up to 20 MB per file. Files become available for download after processing completes.

Back up the database, document storage, and model encryption key together. When moving from local storage to S3, copy existing objects under their original names before changing the storage configuration.

## Development

### Run locally

Requirements: Java 17, Maven 3.9+, and Node.js 20+.

Start the backend from a PowerShell terminal:

```powershell
$env:ADMIN_PASSWORD = 'your-initial-admin-password'
cd backend
mvn spring-boot:run
```

Start the frontend in a second terminal:

```bash
cd frontend
npm ci
npm run dev
```

Vite serves the frontend on port 5173 and proxies API requests to the backend on port 8080.

### Build and test

```bash
cd backend
mvn package
```

```bash
cd frontend
npm ci
npm run build
```

The PostgreSQL integration test accepts `TEST_POSTGRES_URL`, `TEST_POSTGRES_USER`, and `TEST_POSTGRES_PASSWORD`. Point these variables at a test database with pgvector available, then run `mvn -Dtest=PostgresVectorTest test` from `backend/`.

### Repository layout

```text
backend/      Spring Boot API, ingestion, retrieval, database migrations, tests
frontend/     React application and Nginx configuration
scripts/      Development launchers and corpus utilities
corpus/       Attributed sample documents
docs/         Technical notes and supporting documentation
examples/     Sample upload files
```

The backend uses Spring Security, JDBC, Flyway, PDFBox, and Apache POI. The frontend uses React 19, TypeScript, and Vite.

### API

The application exposes REST endpoints under `/api`. Clients use session cookies for authentication and obtain a CSRF token from `GET /api/auth/csrf` before sending write requests.

| Endpoint | Purpose |
| --- | --- |
| `/api/auth` | Authentication and session |
| `/api/kbs` | Knowledge bases, membership, and documents |
| `POST /api/search` | Retrieval |
| `POST /api/chat` | Chat |
| `POST /api/chat/stream` | Streaming chat over SSE |
| `/api/conversations` | Conversation history |
| `/api/admin/model-settings` | Model configuration and connection tests |
| `/api/admin/users` | User administration |
| `/api/admin/audit` | Audit events |
| `GET /actuator/health` | Service health |

Streaming chat emits `meta`, `delta`, `done`, and `error` events. Both chat endpoints accept an optional conversation ID to continue an existing session.
