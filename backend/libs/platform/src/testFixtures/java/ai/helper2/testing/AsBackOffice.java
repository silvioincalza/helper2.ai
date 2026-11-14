package ai.helper2.testing;

import ai.helper2.TestcontainersConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@TestPropertySource(properties = TestcontainersConfiguration.CLIENT + "=h2_backoffice")
public @interface AsBackOffice {}
