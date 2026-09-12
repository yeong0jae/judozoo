# 016 — 도메인 연결 + HTTPS

Goal: 고정 IP `:3000` 직결을 도메인 + HTTPS로 바꾼다. 프론트와 Grafana 둘 다 인증서를 붙이고, 호스트에 직접 노출된 앱 포트는 닫는다.

> 도메인 미보유 상태에서 시작한다. 등록처는 **Cloudflare Registrar**를 전제로 쓴다 — DNS-01 인증을 쓰기 위해서다. 다른 등록처를 고르면 §도메인 확보와 §Cloudflare API 토큰 절이 통째로 바뀐다.

## 진입 조건 (현재 상태)

- GCE VM 1대(`auto-trading-app-kiwoom-real`) + 고정 외부 IP (`terraform output vm_external_ips`)
- frontend nginx가 호스트 `:3000`, Grafana가 `:3001` 퍼블리시
- 방화벽 `auto-trading-allow-web`이 tcp 3000·3001을 IP 화이트리스트(집·헬스장 6개)에만 개방. 80/443은 닫혀 있음
- 프론트는 API를 상대경로(`/api/...`)로만 호출 → **프론트 코드 변경 없음**
- CI 규약: 디렉터리명 = 이미지명 = 빌드 컨텍스트 (`deploy.yml` 매트릭스)

## 설계 결정

| 항목 | 선택 | 이유 |
|---|---|---|
| TLS 종단 | Caddy 컨테이너 | compose에 1개 추가로 끝난다. 발급·갱신 자동 |
| ACME 챌린지 | **DNS-01** | HTTP-01은 `:80`을 `0.0.0.0/0`에 열어야 한다. IP 화이트리스트를 유지하려면 DNS-01뿐 |
| Cloudflare 프록시 | **끈다 (grey cloud)** | 켜면 오리진 접속 주체가 Cloudflare가 되어 IP 화이트리스트가 무력화된다 |
| Grafana | 서브도메인 | 기본 계정이 admin/admin이라 비밀번호 교체가 같이 따라온다 |
| 작업 순서 | HTTPS를 **먼저 띄우고** 기존 포트를 나중에 닫는다 | 새 경로가 실증되기 전에 기존 경로를 끊으면 되돌릴 방법이 없다 |

---

## 1. 도메인 확보

- [ ] 도메인 구입 (Cloudflare Registrar). 네임서버가 Cloudflare로 잡히는지 확인
- [ ] A 레코드 2건 등록 — `<도메인>`, `grafana.<도메인>` → 고정 외부 IP. **둘 다 프록시 끄고 DNS only**
- [ ] `dig +short <도메인>` / `dig +short grafana.<도메인>`이 고정 IP를 돌려주는지 확인

## 2. Cloudflare API 토큰 → Secret Manager

- [ ] Zone:DNS:Edit 권한 토큰 발급 — 해당 zone 하나로 범위 제한
- [ ] `infra/terraform/main.tf`의 `app_secrets`에 `AT_CLOUDFLARE_API_TOKEN` 추가 → `terraform apply`
- [ ] 토큰 값 주입 (`echo -n "<값>" | gcloud secrets versions add AT_CLOUDFLARE_API_TOKEN --data-file=-`)
- [ ] `remote_deploy.sh`의 `fetch`로 `CLOUDFLARE_API_TOKEN`을 `secrets/.env`에 기록

## 3. Grafana 비밀번호 분리

> 도메인이 붙으면 admin/admin을 그대로 둘 수 없다. Caddy보다 먼저 처리해야 첫 HTTPS 노출 시점에 이미 잠겨 있다.

- [ ] `AT_GRAFANA_ADMIN_PASSWORD` 시크릿 추가 → `terraform apply` → 값 주입
- [ ] `remote_deploy.sh`가 `GRAFANA_ADMIN_PASSWORD`를 `.env`에 기록
- [ ] `docker-compose.yml`의 `GF_SECURITY_ADMIN_PASSWORD`를 하드코딩 `admin`에서 환경변수 참조로 교체

## 4. Caddy 이미지

- [ ] `caddy/Dockerfile` — 표준 caddy 이미지에는 DNS 프로바이더가 없다. `caddy:builder`의 xcaddy로 `caddy-dns/cloudflare` 포함해 빌드하고 최종 이미지에 바이너리만 복사
- [ ] `caddy/Caddyfile` — 사이트 블록 2개. `<도메인>` → `reverse_proxy frontend:3000`, `grafana.<도메인>` → `reverse_proxy grafana:3000`. `tls { dns cloudflare {env.CLOUDFLARE_API_TOKEN} }`
- [ ] `deploy.yml` 빌드 매트릭스에 `caddy` 추가 (`image: [python-backend, frontend, caddy]`)
- [ ] `docker-compose.prod.yml`에 caddy 서비스 override 추가 — `build: !reset null` + `image: ${CADDY_IMAGE}:${IMAGE_TAG}`, `restart: unless-stopped`
- [ ] `remote_deploy.sh`의 `COMPOSE_ENV`에 `CADDY_IMAGE` 추가

## 5. Caddy를 compose에 투입 (기존 포트는 유지)

- [ ] `docker-compose.yml`에 caddy 서비스 추가 — `ports: 80:80, 443:443`, `env_file: ./secrets/.env`, `depends_on: [frontend, grafana]`
- [ ] **명명 볼륨 `caddy-data`를 `/data`에 마운트** — 인증서가 여기 저장된다. 휘발되면 재배포마다 재발급해서 Let's Encrypt 발급 한도에 걸린다
- [ ] 방화벽 `auto-trading-allow-web`에 tcp 80·443 추가 (3000·3001은 아직 그대로) → `terraform apply`
- [ ] 배포 후 발급 확인 — `https://<도메인>`, `https://grafana.<도메인>` 접속. 인증서 발급자·만료일 확인

### 검증 (5단계 종료 시점)

- [ ] 화이트리스트 IP에서 두 도메인 모두 200
- [ ] 화이트리스트 밖 IP(모바일 LTE 등)에서 접속 불가
- [ ] `http://<도메인>`이 https로 리다이렉트
- [ ] `/api/leading-stocks/...` 호출이 Caddy를 지나 정상 응답 (프론트 화면이 빈 채로 뜨지 않는지)
- [ ] Grafana가 서브도메인에서 정상 렌더 — 로그인 리다이렉트가 깨지면 `GF_SERVER_ROOT_URL` 설정 필요

## 6. 기존 포트 닫기

> 5단계가 실증된 뒤에만 진행한다.

- [ ] `docker-compose.yml`에서 frontend·grafana의 `ports:` 제거 (caddy만 호스트에 노출)
- [ ] 죽은 변수 정리 — `FRONTEND_HOST_PORT`, `GRAFANA_HOST_PORT`
- [ ] `remote_deploy.sh` 헬스체크 교체 — 현재 `curl http://localhost:3000/`은 포트를 닫는 순간 실패한다. `curl -fsS --resolve "<도메인>:443:127.0.0.1" "https://<도메인>/"`로 바꿔 인증서까지 함께 검증
- [ ] 방화벽에서 tcp 3000·3001 제거 → `terraform apply`
- [ ] `deploy.yml`의 "VM 내부 localhost:3000을 검사" 주석 갱신

## 7. 문서 갱신

- [ ] `docs/spec.md` §11 배포 — 접속 경로를 IP:포트에서 도메인으로. Caddy 계층 추가
- [ ] `infra/docs/plan.md` 구성도에 caddy 반영
- [ ] `frontend/nginx.conf` 주석의 `tasks-015` 표기를 새 파일명으로 정정

---

## Verification / DoD

- [ ] `https://<도메인>`, `https://grafana.<도메인>` 둘 다 유효한 인증서로 응답
- [ ] 호스트에 노출된 포트가 80·443뿐 (`sudo ss -tlnp`로 확인)
- [ ] 방화벽 규칙에 3000·3001이 없다
- [ ] IP 화이트리스트가 그대로 작동 — 허용 밖에서 차단
- [ ] 재배포를 한 번 더 돌려도 인증서를 재발급하지 않는다 (`caddy-data` 영속 확인) — 이게 깨지면 한도 소진이 시간 문제다
- [ ] Grafana 기본 비밀번호가 더 이상 `admin`이 아니다
- [ ] 배포 파이프라인 헬스체크가 새 경로를 검사하고, 일부러 백엔드를 죽였을 때 실패로 잡힌다

## 알려진 함정

- **Cloudflare 프록시(orange cloud)를 켜면 안 된다.** 켜는 순간 오리진에 접속하는 주체가 Cloudflare 대역이 되어 IP 화이트리스트가 무의미해지고, 동시에 화이트리스트 때문에 Cloudflare가 오리진에 붙지 못해 522가 난다.
- **`caddy-data` 볼륨이 핵심이다.** Let's Encrypt는 도메인당 주당 발급 한도가 있다. 배포마다 재발급하면 금방 소진되고, 그러면 인증서 없이 며칠을 보낸다.
- **`docker image prune -af`는 명명 볼륨을 건드리지 않는다** — 기존 배포 스크립트와 충돌 없음.
- **Grafana를 서브도메인 루트에 두면** `GF_SERVER_ROOT_URL`이 없을 때 로그인 후 리다이렉트가 내부 주소로 튈 수 있다.

## 후속

- Caddy access log를 Alloy가 수집하도록 연결 (현재 옵저버빌리티 스택은 앱 컨테이너만 본다)
- IP 화이트리스트를 인증(Cloudflare Access / Grafana OAuth)으로 대체할지 검토 — 유동 IP가 바뀔 때마다 `terraform apply`하는 현재 방식의 대안
