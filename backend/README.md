# backend — 이전 구현 (Kotlin / Spring Boot)

**이 디렉터리는 더 이상 빌드·배포되지 않는다.** 2026-09-12에 `python-backend/`로 이관이
끝나면서(`docs/tasks/015-백엔드-python-마이그레이션.md`) 이미지 빌드 대상과 compose 서비스에서 제거됐다.

지우지 않고 남겨 둔 이유는 두 가지다.

1. **시크릿 파일 위치** — `backend/.env`를 `mysql`과 `python-backend`가 둘 다 읽는다.
   배포 스크립트(`infra/deploy/remote_deploy.sh`)도 이 경로에 `.env`를 쓴다.
   그래서 디렉터리 이름을 바꾸지 않았다.
2. **대조용 원본** — 이관 중 "Kotlin은 어떻게 했나"를 확인할 곳이 필요하다.
   특히 외부 API 함정(키움 부호 변종, `_AL`/`_NX` 코드, 야간선물 +24시간 등)의
   맥락이 주석으로 남아 있다.

## 더 이상 참이 아닌 것

- `SPRING_PROFILES_ACTIVE` — 배포에서 주입하지 않는다
- `@Scheduled` 폴러 9종 — Python APScheduler가 대신 돈다
- `build/` — 마지막 빌드 산출물이 남아 있을 수 있으나 배포와 무관하다

## 현재 구현

`python-backend/` — FastAPI + SQLAlchemy. DB 스키마는 여기서 만들지 않는다(이관 기간에
`ddl-auto=update`가 만들어 둔 테이블을 그대로 쓴다). 자세한 이관 경위는
`docs/tasks/015-백엔드-python-마이그레이션.md`.
