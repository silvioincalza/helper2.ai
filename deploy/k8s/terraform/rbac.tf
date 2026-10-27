# The identity the GitHub Actions deploy job authenticates as, one per environment. Its RBAC
# stops at this namespace: inside it helm can do its job, outside it the token is worth nothing.
# Same shape as the incalza.me site (incalzame/resume infra/terraform/rbac.tf).
#
# CI never gets a credential that can create namespaces or RBAC: this stack runs from the
# workstation (`make ENV=<env> bootstrap`). The token is read at job time from OpenBao
# (helper2/kv ci/<env>, written by `make ENV=<env> credentials`), never stored in GitHub, and
# only a job in that GitHub environment can read it (prod: after the required approval).
resource "kubernetes_service_account_v1" "deployer" {
  metadata {
    name      = "deployer"
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }
}

# Long-lived by design (see above). Rotate: `terraform taint kubernetes_secret_v1.deployer_token`,
# `make ENV=<env> bootstrap`, then `make ENV=<env> credentials`.
resource "kubernetes_secret_v1" "deployer_token" {
  metadata {
    name      = "deployer-token"
    namespace = kubernetes_namespace_v1.app.metadata[0].name
    annotations = {
      "kubernetes.io/service-account.name" = kubernetes_service_account_v1.deployer.metadata[0].name
    }
  }
  type                           = "kubernetes.io/service-account-token"
  wait_for_service_account_token = true
}

# Everything the chart (../../helm/helper2) creates, in this namespace, and nothing else.
resource "kubernetes_role_v1" "deployer" {
  metadata {
    name      = "deployer"
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }
  # secrets: the app's env Secret and helm's own release records.
  rule {
    api_groups = [""]
    resources  = ["services", "configmaps", "secrets", "serviceaccounts", "persistentvolumeclaims"]
    verbs      = ["get", "list", "watch", "create", "update", "patch", "delete"]
  }
  # read-only: helm --wait and `make status` watch pods; logs and events help a failed rollout.
  rule {
    api_groups = [""]
    resources  = ["pods", "pods/log", "events"]
    verbs      = ["get", "list", "watch"]
  }
  rule {
    api_groups = ["apps"]
    resources  = ["deployments", "statefulsets", "replicasets"]
    verbs      = ["get", "list", "watch", "create", "update", "patch", "delete"]
  }
  # prod's nightly pg_dump (backup.enabled).
  rule {
    api_groups = ["batch"]
    resources  = ["cronjobs", "jobs"]
    verbs      = ["get", "list", "watch", "create", "update", "patch", "delete"]
  }
  rule {
    api_groups = ["networking.k8s.io"]
    resources  = ["networkpolicies"]
    verbs      = ["get", "list", "watch", "create", "update", "patch", "delete"]
  }
  # The app's PodDisruptionBudget (app.replicas > 1).
  rule {
    api_groups = ["policy"]
    resources  = ["poddisruptionbudgets"]
    verbs      = ["get", "list", "watch", "create", "update", "patch", "delete"]
  }
}

resource "kubernetes_role_binding_v1" "deployer" {
  metadata {
    name      = "deployer"
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }
  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "Role"
    name      = kubernetes_role_v1.deployer.metadata[0].name
  }
  subject {
    kind      = "ServiceAccount"
    name      = kubernetes_service_account_v1.deployer.metadata[0].name
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }
}

# Prod only: promoting with an empty tag deploys what staging runs. The prod deployer may read that
# one Deployment in staging (its image), nothing else there — not helm's release Secrets, which
# would expose all of staging's secrets (RBAC cannot narrow Secrets to helm's).
resource "kubernetes_role_v1" "read_staging_image" {
  count = var.environment == "prod" ? 1 : 0
  metadata {
    name      = "prod-deployer-read-image"
    namespace = "helper2-staging"
  }
  rule {
    api_groups     = ["apps"]
    resources      = ["deployments"]
    resource_names = ["helper2"]
    verbs          = ["get"]
  }
}

resource "kubernetes_role_binding_v1" "read_staging_image" {
  count = var.environment == "prod" ? 1 : 0
  metadata {
    name      = "prod-deployer-read-image"
    namespace = "helper2-staging"
  }
  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "Role"
    name      = kubernetes_role_v1.read_staging_image[0].metadata[0].name
  }
  subject {
    kind      = "ServiceAccount"
    name      = kubernetes_service_account_v1.deployer.metadata[0].name
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }
}
