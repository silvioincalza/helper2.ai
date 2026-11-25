package ai.searchavo.catalog.internal;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "searchavo.catalog.reconcile-by-projection=false")
class ResourceIdentityWithSqlReconciliationIT extends ResourceIdentityIT {}
