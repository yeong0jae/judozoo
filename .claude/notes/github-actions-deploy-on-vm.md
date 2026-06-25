# GitHub Actions — Deploy on VM 단계

## 사용자 요약 (본인 표현 그대로)

actions 러너가 GitHub가 발급한 JWT(OIDC 토큰)를 GCP에 제출하면, GCP가 이 러너를 신뢰하고 단기 자격증명을 줌. 그 자격증명으로 IAP SSH 터널을 통해 VM에 접속하고 remote_deploy.sh를 실행함.
1. GCP Secret Manager에서 시크릿 꺼내서 .env 파일 생성
2. Artifact Registry에 Docker 로그인
3. 새 이미지 pull
4. docker compose up -d

## 교정 메모

- 앞 단계 추가: `git push main` → `build` job이 이미지 빌드 후 Artifact Registry에 push → 그 다음 `deploy` job 시작.
- 뒤 단계 추가: `docker compose up -d` 이후 IAP SSH로 VM 내부에서 `curl localhost:3000` 을 24회 재시도(최대 240초). 통과하면 배포 완료.
- IAP = Identity-Aware Proxy. VM의 포트 22를 외부에 열지 않고 구글 내부망으로 SSH 터널링.
- WIF(Workload Identity Federation): GCP 비밀 키를 GitHub Secrets에 저장하지 않아도 됨. GitHub OIDC 토큰을 GCP가 신뢰하도록 미리 설정해두는 방식.
- 시크릿을 GitHub 러너가 꺼내지 않고 VM이 직접 꺼내는 이유: 시크릿이 이동하는 경로를 최소화(보안), 최소 권한 원칙.
