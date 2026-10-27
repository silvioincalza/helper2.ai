# Own state per environment (helper2-<env>.tfstate), on purpose: an apply on the platform stack can never touch
# helper2, and vice versa. backend.hcl is rendered by `make init` (it names the R2 account).
terraform {
  required_version = ">= 1.6.0"

  required_providers {
    kubernetes = { source = "hashicorp/kubernetes", version = ">= 2.38.0" }
    cloudflare = { source = "cloudflare/cloudflare", version = "~> 5.22" }
  }

  backend "s3" {
    use_lockfile                = true
    profile                     = ""
    skip_credentials_validation = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    skip_metadata_api_check     = true
    skip_s3_checksum            = true
    use_path_style              = true
    shared_credentials_files    = []
    shared_config_files         = []
  }
}

provider "kubernetes" {
  config_path = var.kubeconfig_path
}

provider "cloudflare" {
  api_token = var.cloudflare_api_token
}
