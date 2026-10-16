terraform {
  backend "gcs" {
    bucket = "trading-496508-tfstate"
    prefix = "auto-trading/state"
  }
}
