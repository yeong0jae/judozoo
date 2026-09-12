# 인프라 / 배포 구성 계획

> **최초 구축 시점(2026-05)의 계획 기록이며 현재 구성의 명세가 아니다.**
> 현재 배포 구성은 [`docs/spec.md`](../../docs/spec.md) §11과 실제 파일
> (`docker-compose.yml`, `infra/terraform/`, `infra/deploy/remote_deploy.sh`)이 정의한다.
>
> 이 문서에서 더 이상 참이 아닌 것: Kotlin/Spring 백엔드와 `backend/Dockerfile`(삭제됨,
> 지금은 `python-backend/` FastAPI가 `:8000`), `SPRING_PROFILES_ACTIVE`·`vts` 프로파일(제거됨),
> `/ws` WebSocket 프록시(STOMP 제거로 없어짐), 시크릿 12종(현재 8종).

`/Users/yeong0jae/project/trading`(레퍼런스 프로젝트)의 인프라 골격을 그대로 따른다.
GCE VM(Ubuntu) + Docker Compose + Artifact Registry + Secret Manager + GitHub Actions (WIF OIDC).
`main` 브랜치에 푸시되면 자동으로 이미지 빌드/푸시 → IAP SSH로 VM 갱신.

## 결정사항 (확정됨)

| 항목 | 값 |
| --- | --- |
| GCP 프로젝트 | 기존 `trading-496508` 재사용 (리소스명에 `auto-trading-` 프리픽스 부여하여 기존 `trading-*` 리소스와 충돌 회피) |
| 리전 / 존 | `asia-northeast3` / `asia-northeast3-a` |
| 초기 KIS 프로파일 | `vts` (모의투자) — `SPRING_PROFILES_ACTIVE`를 GitHub Variables로 빼서 추후 `real`로 전환 가능 |
| MySQL | 같은 VM 안 Docker Compose 컨테이너 (`mysql:8.4` + named volume) |
| GitHub 저장소 | `yeong0jae/autonomous-trading` (WIF attribute_condition 고정) |

## 레퍼런스와 다른 점 (autonomous-trading 특이사항)

| 항목 | 레퍼런스 trading | autonomous-trading |
| --- | --- | --- |
| 백엔드 포트 | 8081 | **8080** |
| MySQL | 외부/미사용 | **같은 compose에 mysql:8.4 컨테이너로 포함** |
| 프론트 런타임 | Next.js standalone (`server.js`) | **Vite 정적 빌드** → `nginx:alpine`이 dist 서빙 + `/api`,`/ws` reverse proxy |
| KIS 시크릿 | KIWOOM 2종 | **REAL_KIS_\*, VTS_KIS_\*, KIWOOM_\*, KIS_HTS_ID, DB_PASSWORD 등 12종** |
| Spring profile | 단일 | **vts / real** (`application-{vts,real}.yaml`) |

## 디렉토리 레이아웃

```
infra/
├── docs/
│   └── plan.md                    # (이 문서)
├── terraform/
│   ├── versions.tf
│   ├── backend.tf                 # GCS state (기존 trading-496508-tfstate 재사용, prefix=auto-trading/state)
│   ├── variables.tf
│   ├── main.tf
│   ├── outputs.tf
│   ├── startup.sh                 # VM 최초 부팅 시 Docker/Compose 설치
│   └── terraform.tfvars.example
└── deploy/
    └── remote_deploy.sh           # VM에서 실행. Secret Manager → secrets/.env, compose pull/up
```

루트에는 다음 파일을 추가한다.

```
backend/Dockerfile
backend/.dockerignore
frontend/Dockerfile
frontend/.dockerignore
frontend/nginx.conf
docker-compose.yml                 # base: mysql + backend + frontend
docker-compose.prod.yml            # override: build 제거, AR 이미지로 교체
.github/workflows/deploy.yml
```

`.gitignore`에 다음을 추가한다.

```
infra/terraform/.terraform/
infra/terraform/*.tfstate*
infra/terraform/*.tfplan
infra/terraform/terraform.tfvars
```

## 1. 도커라이즈

### backend/Dockerfile (Temurin 21 multi-stage)

- build stage: `eclipse-temurin:21-jdk` → gradle 의존성 캐시 → `./gradlew bootJar`
- runtime stage: `eclipse-temurin:21-jre` → `build/libs/backend-0.0.1-SNAPSHOT.jar` 복사
- `EXPOSE 8080`, TZ는 compose env에서 `Asia/Seoul` 주입

### frontend/Dockerfile (Vite → nginx)

- deps + build stage: `node:24-alpine`로 `npm ci && npm run build` → `/app/dist`
- runtime stage: `nginx:alpine`에 dist + `nginx.conf` 복사
- `EXPOSE 3000` (nginx가 0.0.0.0:3000으로 리슨)

### frontend/nginx.conf

- `listen 3000`
- `location /` → `try_files $uri /index.html` (SPA fallback)
- `location /api` → `proxy_pass http://backend:8080`
- `location /ws`  → `proxy_pass http://backend:8080` + WebSocket Upgrade 헤더

### docker-compose.yml (base, 로컬 개발에서도 사용 가능)

```yaml
services:
  mysql:
    image: mysql:8.4
    environment:
      MYSQL_ROOT_PASSWORD: ${DB_PASSWORD}
      MYSQL_DATABASE: trading
      TZ: Asia/Seoul
    volumes:
      - mysql-data:/var/lib/mysql
    networks: [trading]

  backend:
    build: ./backend
    environment:
      TZ: Asia/Seoul
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-vts}
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/trading?useSSL=false&serverTimezone=Asia/Seoul&allowPublicKeyRetrieval=true
      DB_USERNAME: root
    env_file:
      - ./secrets/.env       # KIS/KIWOOM 시크릿 + DB_PASSWORD (prod에서 remote_deploy.sh가 생성)
    expose:
      - "8080"
    depends_on: [mysql]
    networks: [trading]

  frontend:
    build: ./frontend
    ports:
      - "3000:3000"
    depends_on: [backend]
    networks: [trading]

volumes:
  mysql-data:

networks:
  trading:
```

### docker-compose.prod.yml (override)

- backend / frontend의 `build:`를 `!reset null`로 제거
- `image: ${BACKEND_IMAGE}:${IMAGE_TAG}` / `image: ${FRONTEND_IMAGE}:${IMAGE_TAG}`
- `pull_policy: always`, `restart: unless-stopped`

## 2. Terraform 리소스 (모두 `auto-trading-` 프리픽스)

### 네트워킹

- `google_compute_address.frontend` → `auto-trading-frontend-ip` (프론트 공개용 고정 IP)
- `google_compute_firewall.web` → `auto-trading-allow-web` (tcp:3000, target_tag `auto-trading`)
- `google_compute_firewall.ssh_iap` → `auto-trading-allow-ssh-iap` (IAP REDACTED_IP/20만 허용)

### VM

- `google_compute_instance.app` → `auto-trading-app`
  - 머신 타입 `e2-medium`, 부트 디스크 20GB, 이미지 `ubuntu-2404-lts-amd64`
  - tag: `["auto-trading"]`
  - service_account: VM SA (`auto-trading-vm@`)
  - metadata: `enable-oslogin=TRUE`, `startup-script=file("startup.sh")`

### IAM / 서비스 계정

- VM SA: `auto-trading-vm`
  - `roles/artifactregistry.reader`
  - `roles/secretmanager.secretAccessor`
- Deployer SA: `auto-trading-gha-deployer`
  - `roles/artifactregistry.writer`
  - `roles/compute.instanceAdmin.v1`
  - `roles/compute.osAdminLogin` (VM에서 sudo로 docker 실행)
  - `roles/iap.tunnelResourceAccessor`
  - VM SA에 대한 `roles/iam.serviceAccountUser` (act-as)

### Workload Identity Federation (GitHub OIDC)

- `google_iam_workload_identity_pool.github` → pool id `auto-trading-pool`
- `google_iam_workload_identity_pool_provider.github` → provider id `auto-trading-provider`
  - `issuer_uri = https://token.actions.githubusercontent.com`
  - `attribute_condition = assertion.repository == "yeong0jae/autonomous-trading"`
  - attribute_mapping: `google.subject = assertion.sub`, `attribute.repository = assertion.repository`
- Deployer SA에 `roles/iam.workloadIdentityUser` 부여 (`attribute.repository/yeong0jae/autonomous-trading`)

### Artifact Registry

- `google_artifact_registry_repository.docker` → repository_id `auto-trading`, format `DOCKER`, location `asia-northeast3`

### Secret Manager (컨테이너만 생성, 값은 수동 주입)

12개 시크릿:

| Secret ID | 매핑되는 env (secrets/.env) | 비고 |
| --- | --- | --- |
| `AT_DB_PASSWORD` | `DB_PASSWORD` | MySQL root + Spring datasource 공용 |
| `AT_KIS_HTS_ID` | `KIS_HTS_ID` | real/vts 공용 |
| `AT_REAL_KIS_APP_KEY` | `REAL_KIS_APP_KEY` | real-quotation에서 항상 필요 |
| `AT_REAL_KIS_APP_SECRET` | `REAL_KIS_APP_SECRET` | real-quotation에서 항상 필요 |
| `AT_REAL_KIS_ACCOUNT_NO` | `REAL_KIS_ACCOUNT_NO` | 실거래 시 사용 |
| `AT_REAL_KIS_ACCOUNT_PRODUCT_CODE` | `REAL_KIS_ACCOUNT_PRODUCT_CODE` | 실거래 시 사용 |
| `AT_VTS_KIS_APP_KEY` | `VTS_KIS_APP_KEY` | 모의투자 |
| `AT_VTS_KIS_APP_SECRET` | `VTS_KIS_APP_SECRET` | 모의투자 |
| `AT_VTS_KIS_ACCOUNT_NO` | `VTS_KIS_ACCOUNT_NO` | 모의투자 |
| `AT_VTS_KIS_ACCOUNT_PRODUCT_CODE` | `VTS_KIS_ACCOUNT_PRODUCT_CODE` | 모의투자 |
| `AT_KIWOOM_APP_KEY` | `KIWOOM_APP_KEY` | 주도주 후보 발굴 |
| `AT_KIWOOM_APP_SECRET` | `KIWOOM_APP_SECRET` | 주도주 후보 발굴 |

값 주입 명령(예시):

```bash
echo -n "<value>" | gcloud secrets versions add AT_VTS_KIS_APP_KEY --data-file=-
```

### State backend

```hcl
terraform {
  backend "gcs" {
    bucket = "trading-496508-tfstate"   # 기존 버킷 재사용
    prefix = "auto-trading/state"
  }
}
```

## 3. 배포 스크립트 (`infra/deploy/remote_deploy.sh`)

레퍼런스와 동일한 호출 인터페이스: `bash ~/remote_deploy.sh <IMAGE_TAG> <AR_REPO> <REGION>`.

수행 순서:

1. `cd $HOME && mkdir -p backend`
2. 12개 시크릿을 `gcloud secrets versions access latest`로 읽어 `secrets/.env`로 출력 (`umask 077`)
3. `gcloud auth print-access-token | sudo docker login -u oauth2accesstoken --password-stdin "https://${REGION}-docker.pkg.dev"`
4. `sudo env BACKEND_IMAGE=... FRONTEND_IMAGE=... IMAGE_TAG=... SPRING_PROFILES_ACTIVE=vts docker compose -f docker-compose.yml -f docker-compose.prod.yml pull`
5. 위와 동일 옵션으로 `up -d`
6. `sudo docker image prune -f`

`SPRING_PROFILES_ACTIVE`는 일단 스크립트에 `vts` 하드코딩.
이후 `real` 전환이 필요해지면 GitHub Variables → `remote_deploy.sh` 인자로 빼는 단순한 리팩토링으로 처리.

## 4. GitHub Actions (`.github/workflows/deploy.yml`)

`on: push: branches: [main]`. `permissions: { contents: read, id-token: write }`.

단계:

1. `actions/checkout@v4`
2. `google-github-actions/auth@v2` (WIF)
3. `google-github-actions/setup-gcloud@v2`
4. `gcloud auth configure-docker "${REGION}-docker.pkg.dev"`
5. `docker build/push` — `${AR_REPO}/backend:${GITHUB_SHA}` + `:latest`, frontend도 동일
6. `gcloud compute scp --tunnel-through-iap` 으로 `docker-compose.yml`, `docker-compose.prod.yml`, `infra/deploy/remote_deploy.sh` 를 VM 홈으로 전송
7. `gcloud compute ssh --tunnel-through-iap --command "bash ~/remote_deploy.sh ${GITHUB_SHA} ${AR_REPO} ${REGION}"`
8. 헬스체크: VM 내부 `localhost:3000/`이 200을 반환할 때까지 폴링 (최대 4분)

필요한 GitHub Actions **Variables**(Secrets 아님):

| 이름 | 값(예시) |
| --- | --- |
| `GCP_PROJECT_ID` | `trading-496508` |
| `GCP_REGION` | `asia-northeast3` |
| `AR_REPO` | `asia-northeast3-docker.pkg.dev/trading-496508/auto-trading` |
| `VM_NAME` | `auto-trading-app` |
| `VM_ZONE` | `asia-northeast3-a` |
| `WIF_PROVIDER` | `projects/<NUMBER>/locations/global/workloadIdentityPools/auto-trading-pool/providers/auto-trading-provider` |
| `DEPLOY_SA` | `auto-trading-gha-deployer@trading-496508.iam.gserviceaccount.com` |

`WIF_PROVIDER`, `DEPLOY_SA`, `AR_REPO` 값은 `terraform apply` 후 outputs에서 그대로 복사 가능.

## 5. 사용자 작업 (1회성)

1. (이미 있으면 skip) GCS state 버킷
   ```bash
   gsutil mb -l asia-northeast3 gs://trading-496508-tfstate || true
   gsutil versioning set on gs://trading-496508-tfstate
   ```
2. Terraform 적용
   ```bash
   cd infra/terraform
   terraform init
   terraform apply
   ```
3. 12개 시크릿 값 주입 (`gcloud secrets versions add ...`)
4. GitHub Actions Variables 7개 등록 (`terraform output` 결과 사용)
5. `git push origin main` → 자동 배포

## 검증 기준

- `terraform apply`가 깨끗하게 통과 (drift 없음)
- main 푸시 후 GitHub Actions가 끝까지 통과
- VM 외부 IP `:3000/`에서 프론트가 응답
- `/api/...` 요청이 nginx를 거쳐 백엔드(8080)에 도달
- backend가 `vts` 프로파일로 부팅되어 모의투자 KIS 엔드포인트와 통신
- MySQL volume이 VM 재기동 후에도 유지됨
