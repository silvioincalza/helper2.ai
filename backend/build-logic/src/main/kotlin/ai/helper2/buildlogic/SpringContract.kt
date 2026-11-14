package ai.helper2.buildlogic

import java.io.File
import org.openapitools.codegen.CodegenConstants
import org.openapitools.codegen.DefaultGenerator
import org.openapitools.codegen.config.CodegenConfigurator
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

const val SHARED_API_PACKAGE = "ai.helper2.api.common"

private const val GENERATOR = "spring"

private const val MODEL_SUFFIX = "Dto"

private val springOptions =
  mapOf(
    "useSpringBoot4" to "true",
    "useJackson3" to "true",
    "interfaceOnly" to "true",
    "useTags" to "true",
    "skipDefaultInterface" to "true",
    "openApiNullable" to "false",
    "useBeanValidation" to "true",
    "performBeanValidation" to "false",
    "documentationProvider" to "none",
    "annotationLibrary" to "none",
    "dateLibrary" to "java8",
    "sourceFolder" to "src/main/java",
    "useOptional" to "false",
    "containerDefaultToNull" to "false",
    "generateJsonIncludeAnnotations" to "false",
    "generateJsonSetterNullsAnnotations" to "false",
  )

fun GenerateTask.springContract(spec: File, output: File, apiPackage: String) {
  generatorName.set(GENERATOR)
  inputSpec.set(spec.path)
  outputDir.set(output.path)
  this.apiPackage.set(apiPackage)
  modelPackage.set("$apiPackage.model")
  modelNameSuffix.set(MODEL_SUFFIX)
  cleanupOutput.set(true)
  configOptions.set(springOptions)
}

fun sharedModelClasses(common: File, scratch: File): Map<String, String> {
  val input =
    CodegenConfigurator()
      .setGeneratorName(GENERATOR)
      .setInputSpec(common.path)
      .setOutputDir(scratch.path)
      .setApiPackage(SHARED_API_PACKAGE)
      .setModelPackage("$SHARED_API_PACKAGE.model")
      .setModelNameSuffix(MODEL_SUFFIX)
      .setAdditionalProperties(springOptions)
      .toClientOptInput()
  DefaultGenerator()
    .apply { setGeneratorPropertyDefault(CodegenConstants.MODELS, "true") }
    .opts(input)
    .generate()
  return input.openAPI.components.schemas.keys.associateWith { name ->
    "$SHARED_API_PACKAGE.model.${input.config.toModelName(name)}"
  }
}
