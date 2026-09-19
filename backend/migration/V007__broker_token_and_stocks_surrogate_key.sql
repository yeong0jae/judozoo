-- broker_token · stocks에 내부 식별자를 주고 PK를 옮긴다. V006(app_user)과 같은 모양이다.
-- 앱은 DDL을 만들지 않으므로 각 환경에 수동 적용한다(로컬·운영 각각).
--
-- 왜 — 두 테이블만 자연키를 PK로 쓰고 있었다(`provider`, `short_code`). 나머지 여덟은
-- 전부 `id`다. 자연키는 "바뀌지 않는다"는 가정 위에 서는데, 그 가정이 깨지면 PK를 바꾸는
-- 일이 되어 값을 참조하던 모든 자리를 함께 손봐야 한다. `short_code`는 실제로 바뀐다 —
-- 액면분할·상호변경·이전상장에서 종목코드가 재발급된다.
--
-- 되돌리려면 PK를 옛 열로 되돌리고 id를 DROP한다. 참조하는 FK가 **0개**라
-- (코드베이스 전체에 ForeignKey가 없다) 어느 방향이든 안전하다.
--
-- **적용 순서를 지킨다 — 이 파일을 먼저 적용하고 배포한다.** 반대로 하면 모델이
-- 없는 열(id)을 읽어 두 테이블에 닿는 경로가 전부 실패한다. broker_token이 막히면
-- 재기동이 브로커 토큰 발급을 한 번씩 소비하므로, 순서를 어기면 대가가 크다.
--
-- 조회 경로는 둘 다 PK가 아니라 열로 찾고 있어(`where provider = …`, `where short_code = …`)
-- 코드 쪽 변경은 모델 선언뿐이다. `session.get()`으로 PK를 직접 집는 자리는 없다.
--
-- 알아둘 것 — stocks는 갱신이 전체 교체(delete-all + insert-all)라 매 갱신마다 id가
-- 새로 발급된다. 즉 **stocks.id는 행을 가로질러 오래 사는 식별자가 아니다.** 종목을
-- 가리킬 때는 계속 `short_code`를 쓴다. 여기 id는 "PK 모양을 맞추는 열"이지
-- 바깥에 내보낼 식별자가 아니다.

ALTER TABLE broker_token
  DROP PRIMARY KEY,
  ADD UNIQUE KEY uk_broker_token_provider (provider),
  ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

ALTER TABLE stocks
  DROP PRIMARY KEY,
  ADD UNIQUE KEY uk_stocks_short_code (short_code),
  ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;
