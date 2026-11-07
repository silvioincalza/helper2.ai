package ai.helper2.testing;

import ai.helper2.TestcontainersConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * A test of library code that only the back office runs (the Scout's and the catalog's writes,
 * retention, review decisions, settings), kept next to the public app's tests: it connects as the
 * back office's role and Redis user (ADR 0070), not as the public app's.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@TestPropertySource(properties = TestcontainersConfiguration.CLIENT + "=h2_backoffice")
public @interface AsBackOffice {}
