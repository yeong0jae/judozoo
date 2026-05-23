# tasks-001 — 인프라 / 배포 초기 구성

`infra/docs/plan.md`의 단계별 체크리스트.
완료 항목은 `[x]`로 체크.

표시 규칙:
- 표시 없음 → Claude가 파일 작성으로 처리
- **🧑 (수동)** → 사용자가 직접 해야 함 (외부 시스템·자격증명·실환경 확인이 필요한 작업)

## A. 도커라이즈 (루트)

- [x] `backend/.dockerignore` 작성 (`build/`, `.gradle/`, `out/`, `*.iml`, `.idea/`, `node_modules`, `.env`)
- [x] `backend/Dockerfile` 작성 — Temurin 21 multi-stage, `EXPOSE 8080`, `backend-0.0.1-SNAPSHOT.jar`
- [x] `frontend/.dockerignore` 작성 (`node_modules`, `dist`, `.vite`, `*.tsbuildinfo`)
- [x] `frontend/Dockerfile` 작성 — node:24-alpine 빌드 → nginx:alpine 런타임, `EXPOSE 3000`
- [x] `frontend/nginx.conf` 작성 — `listen 3000`, SPA fallback, `/api`·`/ws` → `backend:8080` proxy (WS upgrade 헤더 포함)
- [x] 루트 `docker-compose.yml` 작성 — mysql + backend + frontend, 호스트는 3000만 노출
- [x] 루트 `docker-compose.prod.yml` 작성 — `build: !reset null`, AR 이미지 + `pull_policy: always` + `restart: unless-stopped`
- [x] 로컬에서 `docker compose up --build` 동작 확인 (mysql healthy, backend Spring Boot 부팅, frontend nginx 200, `/api`→backend 프록시 OK) — 검증 과정에서 두 가지 수정 발생: ① mysql DB 비번 인터폴레이션 미작동 → mysql 서비스에 `env_file` + `MYSQL_ROOT_PASSWORD` 신설, ② backend가 mysql ready 전에 connect → mysql `healthcheck` + `depends_on.condition: service_healthy`

## B. Terraform 디렉토리 (`infra/terraform/`)

- [x] `versions.tf` — required_version ≥ 1.6, google provider ~> 6.0
- [x] `backend.tf` — GCS backend, bucket=`trading-496508-tfstate`, prefix=`auto-trading/state`
- [x] `variables.tf` — project_id, region(asia-northeast3), zone(asia-northeast3-a), github_repository(`yeong0jae/autonomous-trading`), allowed_web_source_ranges, machine_type(e2-medium), boot_disk_size_gb(20)
- [x] `startup.sh` — Docker + compose plugin 설치 (레퍼런스와 동일)
- [x] `main.tf` — APIs 활성화
- [x] `main.tf` — `google_compute_address.frontend` (`auto-trading-frontend-ip`)
- [x] `main.tf` — VM SA `auto-trading-vm` + AR reader / Secret accessor 권한
- [x] `main.tf` — Firewall `auto-trading-allow-web` (tcp:3000, target_tag `auto-trading`)
- [x] `main.tf` — Firewall `auto-trading-allow-ssh-iap` (REDACTED_IP/20)
- [x] `main.tf` — `google_compute_instance.app` (`auto-trading-app`, tag `auto-trading`, startup-script)
- [x] `main.tf` — Artifact Registry `auto-trading` (DOCKER, asia-northeast3)
- [x] `main.tf` — Secret Manager 12개 컨테이너 (`AT_*`)
- [x] `main.tf` — Deployer SA `auto-trading-gha-deployer` + 5개 권한 + VM SA `iam.serviceAccountUser`
- [x] `main.tf` — WIF pool `auto-trading-pool` + provider `auto-trading-provider` (repo 조건 고정)
- [x] `main.tf` — Deployer SA에 `roles/iam.workloadIdentityUser` (attribute.repository 바인딩)
- [x] `outputs.tf` — vm_external_ip, vm_name, vm_zone, artifact_registry_repo, wif_provider, deployer_sa_email
- [x] `terraform.tfvars.example` 작성

## C. 배포 스크립트 (`infra/deploy/`)

- [x] `remote_deploy.sh` 작성
  - [x] 12개 시크릿 → `backend/.env` 매핑 (`AT_*` → 원본 env명)
  - [x] `umask 077`로 권한 보호
  - [x] AR Docker login (access token 방식)
  - [x] `SPRING_PROFILES_ACTIVE=vts` 주입
  - [x] compose `pull` + `up -d` + `image prune -f`

## D. GitHub Actions (`.github/workflows/deploy.yml`)

- [x] `on: push: branches: [main]`
- [x] `permissions: contents: read, id-token: write`
- [x] 환경변수 7개 (`vars.GCP_PROJECT_ID`, `vars.GCP_REGION`, `vars.AR_REPO`, `vars.VM_NAME`, `vars.VM_ZONE`, `vars.WIF_PROVIDER`, `vars.DEPLOY_SA`)
- [x] Step: checkout
- [x] Step: WIF auth
- [x] Step: setup-gcloud
- [x] Step: `gcloud auth configure-docker`
- [x] Step: backend / frontend 이미지 빌드·푸시 (`:${GITHUB_SHA}` + `:latest`)
- [x] Step: `gcloud compute scp` — `docker-compose.yml`, `docker-compose.prod.yml`, `infra/deploy/remote_deploy.sh` (IAP)
- [x] Step: `gcloud compute ssh` — `bash ~/remote_deploy.sh ${GITHUB_SHA} ${AR_REPO} ${REGION}` (IAP)
- [x] Step: 헬스체크 — VM 내부에서 `curl localhost:3000/` 200 폴링 (최대 4분)

## E. 기타

- [x] `.gitignore` 보강 (`infra/terraform/.terraform/`, `*.tfstate*`, `*.tfplan`, `terraform.tfvars`)
- [ ] `infra/docs/plan.md`에 추후 변경사항 동기화 (예: real 프로파일 전환 시점)

## F. 사용자 1회성 작업 (모두 🧑)

> 외부 시스템·자격증명이 필요해 코드로 자동화하지 않는 항목.

- [x] 🧑 GCS state 버킷 `gs://trading-496508-tfstate` 존재 확인 (없으면 `gsutil mb -l asia-northeast3 ...` + `gsutil versioning set on ...`)
- [x] 🧑 `cd infra/terraform && terraform init && terraform apply`
- [x] 🧑 12개 시크릿 값 주입 (`echo -n "<value>" | gcloud secrets versions add AT_* --data-file=-`)
- [x] 🧑 GitHub Actions Variables 7개 등록 (Settings → Secrets and variables → Actions → Variables) — `terraform output` 결과 그대로 복사
- [x] 🧑 `main` 브랜치에 push → Actions 실행 확인 (run 26322607448, 4m31s 통과)

## G. 배포 후 검증 (모두 🧑)

- [x] 🧑 GitHub Actions `deploy` 워크플로우가 끝까지 통과
- [x] 🧑 VM 외부 IP `:3000/` 에서 프론트 페이지 렌더링 (`http://REDACTED_IP:3000/` → 200 OK)
- [x] 🧑 프론트의 `/api/...` 호출이 백엔드(8080)에 도달 (nginx → backend JSON envelope 회수로 검증)
- [x] 🧑 backend 로그에 `Activated profile: vts` 확인 (`gcloud compute ssh ... -- sudo docker logs ...`)
- [ ] 🧑 VM 재시작 후에도 MySQL 데이터 유지 (`mysql-data` named volume) — named volume 존재·InnoDB 파일까지 확인, 실제 reboot 테스트는 미수행
