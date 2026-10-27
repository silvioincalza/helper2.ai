# Stand-in for the platform's cf-tunnel-site module (module "edge" in ../../main.tf), for `terraform
# validate` in CI only: the real module pulls a private submodule that CI cannot clone. Same inputs and
# outputs, no resources. CI swaps it in with an override file it writes itself; never commit one.
variable "account_id" {}
variable "tunnel_name" {}
variable "hosts" {}
variable "origin_url" {}
variable "dns_record_comment" {}

output "tunnel_token" {
  value     = ""
  sensitive = true
}

output "zone_ids" {
  value = {}
}
