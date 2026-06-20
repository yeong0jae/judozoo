-- theme_daily 기준을 등락률 → 거래대금으로 변경하며 컬럼 구성이 바뀜
-- (flu_rt·theme_grp_cd 제거, trading_value 추가, 유니크 (date, theme_name)).
-- ddl-auto=update는 컬럼을 드롭하지 못하므로 기존 테이블을 버리고 재생성한다.
-- 신생 테이블(누적 데이터 없음)이라 DROP 후 ddl-auto 재생성으로 충분. 각 DB(kis-real·kiwoom-real)에 수동 적용.
DROP TABLE IF EXISTS theme_daily;
