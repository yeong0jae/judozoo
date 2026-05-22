# GCS 원격 state. 백엔드 블록은 변수 사용 불가 → 버킷명 하드코딩.
# 기존 trading 프로젝트 state 버킷을 재사용하고 prefix로 분리.
# 버킷이 없다면 terraform init 전에 1회 생성:
#   gsutil mb -l asia-northeast3 gs://trading-496508-tfstate
#   gsutil versioning set on gs://trading-496508-tfstate
terraform {
  backend "gcs" {
    bucket = "trading-496508-tfstate"
    prefix = "auto-trading/state"
  }
}
