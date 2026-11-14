package ai.helper2.catalog.internal;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "helper2.catalog.reconcile-by-projection=false")
class ResourceIdentityWithSqlReconciliationIT extends ResourceIdentityIT {}
