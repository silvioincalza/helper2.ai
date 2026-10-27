variable "kubeconfig_path" {
  description = "Admin kubeconfig for the homelab cluster (incalza-me-infra .secrets/kubeconfig)."
  type        = string
}

variable "environment" {
  description = "staging | prod. Selects the namespace and hostnames below; each has its own state key."
  type        = string
  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment must be staging or prod."
  }
}

variable "cloudflare_api_token" {
  description = "Scoped token: Account Cloudflare Tunnel:Edit; Zone:Read + DNS:Edit on incalza.me and helper2.ai; Single Redirect:Edit on helper2.ai (www -> apex)."
  type        = string
  sensitive   = true
}

variable "cloudflare_account_id" {
  description = "Account that owns the incalza.me and helper2.ai zones (same as the platform's)."
  type        = string
}

locals {
  # Namespaces must match tenants.yml (owner incalzame) and workload_namespaces in the platform's wiring.tf.
  environments = {
    staging = {
      namespace = "helper2-staging"
      hosts     = { "helper2.incalza.me" = { zone = "incalza.me", sni = "helper2.incalza.me" } }
      apex      = null
    }
    prod = {
      namespace = "helper2-prod"
      hosts = {
        "helper2.ai"     = { zone = "helper2.ai", sni = "helper2.ai" }
        "www.helper2.ai" = { zone = "helper2.ai", sni = "helper2.ai" }
      }
      apex = "helper2.ai"
    }
  }
  env = local.environments[var.environment]
}
