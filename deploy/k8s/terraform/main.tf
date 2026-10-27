# helper2 identity and edge, per environment. The workloads come from the Helm chart ../../helm/helper2.
resource "kubernetes_namespace_v1" "app" {
  metadata {
    name = local.env.namespace
    labels = {
      "app.kubernetes.io/part-of"          = "helper2"
      "pod-security.kubernetes.io/enforce" = "restricted"
    }
  }
}

# CF edge -> tunnel -> in-cluster cloudflared -> the helper2 Service. Plain HTTP inside the
# cluster, as for the incalza.me site: both environments are anonymous (no sessions, no secrets
# in transit), so origin TLS buys nothing yet. Revisit before sign-in is enabled.
module "edge" {
  source = "git::ssh://git@gitlab.com/incalzame/infra/platform.git//modules/cf-tunnel-site?ref=infra-modules-v1"

  account_id         = var.cloudflare_account_id
  tunnel_name        = local.env.namespace
  hosts              = local.env.hosts
  origin_url         = "http://helper2.${local.env.namespace}.svc.cluster.local:80"
  dns_record_comment = "helper2 ${var.environment} - managed by helper2.ai deploy/k8s/terraform"
}

resource "kubernetes_secret_v1" "cloudflared_token" {
  metadata {
    name      = "cloudflared-token"
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }
  data = {
    token = module.edge.tunnel_token
  }
}

# Prod only: www.helper2.ai -> https://helper2.ai, keeping path and query, at the edge.
resource "cloudflare_ruleset" "www_redirect" {
  count = local.env.apex == null ? 0 : 1

  zone_id = module.edge.zone_ids[local.env.apex]
  name    = "helper2 www to apex"
  kind    = "zone"
  phase   = "http_request_dynamic_redirect"

  rules = [{
    description = "www.${local.env.apex} -> ${local.env.apex}"
    expression  = "(http.host eq \"www.${local.env.apex}\")"
    action      = "redirect"
    action_parameters = {
      from_value = {
        status_code           = 301
        preserve_query_string = true
        target_url = {
          expression = "concat(\"https://${local.env.apex}\", http.request.uri.path)"
        }
      }
    }
  }]
}
