package ai.searchavo.examples.config;

import ai.searchavo.examples.internal.ExamplesBackOfficeAdapters;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.NamedInterface;

@NamedInterface("backoffice")
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(ExamplesBackOfficeAdapters.class)
public @interface EnableExamplesBackOfficeAdapters {}
