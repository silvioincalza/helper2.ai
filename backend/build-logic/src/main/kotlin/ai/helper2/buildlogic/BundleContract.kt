package ai.helper2.buildlogic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.swagger.v3.core.util.Yaml
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * One contract file for an app that serves several (ADR 0003): joins the entry files (public.yaml,
 * admin.yaml) and the parts of common.yaml they reference into one file, as it would have been
 * written by hand. Names stay names: a schema of common.yaml becomes `#/components/schemas/<name>`,
 * a path item of common.yaml is copied in. An operation or a schema that two files define
 * differently stops the build: there is one meaning per name.
 */
@CacheableTask
abstract class BundleContract : DefaultTask() {

  /** The contract files to join, in order; the first one gives the info block. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val entries: ConfigurableFileCollection

  /** Every file the entries reference, so a change to common.yaml reruns the task. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val referenced: ConfigurableFileCollection

  @get:OutputFile abstract val bundle: RegularFileProperty

  private val yaml = Yaml.mapper()

  @TaskAction
  fun join() {
    val files = entries.files.toList()
    val joined = local(files.first())
    files.drop(1).forEach { add(joined, local(it)) }
    bundle.get().asFile.apply {
      parentFile.mkdirs()
      writeText(yaml.writerWithDefaultPrettyPrinter().writeValueAsString(joined))
    }
  }

  /** The entry file with every reference into another file made local. */
  private fun local(entry: File): ObjectNode {
    val doc = yaml.readTree(entry) as ObjectNode
    val others = mutableMapOf<String, ObjectNode>()
    fun other(name: String) =
      others.getOrPut(name) { yaml.readTree(entry.resolveSibling(name)) as ObjectNode }
    // Path items of another file: copied in, with the components they name in that file.
    val fromPaths = mutableListOf<String>()
    val paths = doc.with("paths")
    paths.properties().toList().forEach { (path, item) ->
      val ref = item.path("\$ref").asText("")
      if (ref.contains('#')) {
        val (file, pointer) = ref.split('#', limit = 2)
        val copy = other(file.removePrefix("./")).at(pointer).deepCopy<JsonNode>()
        paths.set<JsonNode>(path, copy)
        fromPaths += externalRefs(copy, file)
      }
    }
    // Components another file names: copied in, then every reference points inside this file.
    val pending = ArrayDeque(externalRefs(doc) + fromPaths)
    val copied = mutableSetOf<String>()
    while (pending.isNotEmpty()) {
      val ref = pending.removeFirst()
      if (!copied.add(ref)) continue
      val (file, pointer) = ref.split('#', limit = 2)
      val node = other(file.removePrefix("./")).at(pointer).deepCopy<JsonNode>()
      val (_, _, section, name) = pointer.split('/')
      doc.with("components").with(section).set<JsonNode>(name, node)
      externalRefs(node, file).forEach(pending::addLast)
    }
    makeLocal(doc)
    return doc
  }

  /** The references to another file under [node]; [from] qualifies the other file's own `#/...`. */
  private fun externalRefs(node: JsonNode, from: String? = null): List<String> {
    val found = mutableListOf<String>()
    fun walk(n: JsonNode) {
      if (n.isObject) {
        n.properties().forEach { (key, value) ->
          if (key == "\$ref" && value.isTextual) {
            val ref = value.asText()
            when {
              !ref.startsWith("#") -> found += ref
              from != null -> found += from + ref
            }
          } else {
            walk(value)
          }
        }
      } else if (n.isArray) {
        n.forEach(::walk)
      }
    }
    walk(node)
    return found
  }

  private fun makeLocal(n: JsonNode) {
    if (n is ObjectNode) {
      val ref = n.get("\$ref")
      if (ref != null && ref.isTextual && !ref.asText().startsWith("#")) {
        n.set<JsonNode>("\$ref", TextNode("#" + ref.asText().substringAfter('#')))
      }
      n.properties().forEach { (_, value) -> makeLocal(value) }
    } else if (n is ArrayNode) {
      n.forEach(::makeLocal)
    }
  }

  private fun add(into: ObjectNode, from: ObjectNode) {
    val paths = into.with("paths")
    from.path("paths").properties().forEach { (path, item) ->
      val known = paths.get(path) as ObjectNode?
      if (known == null) {
        paths.set<JsonNode>(path, item)
        return@forEach
      }
      // One path, operations from both files (admin.yaml has GET /me, public.yaml also DELETE).
      item.properties().forEach { (key, operation) ->
        same("$key $path", known.get(key), operation)
        if (known.get(key) == null) known.set<JsonNode>(key, operation)
      }
    }
    from.path("components").properties().forEach { (section, entries) ->
      val target = into.with("components").with(section)
      entries.properties().forEach { (name, node) ->
        same("$section $name", target.get(name), node)
        if (target.get(name) == null) target.set<JsonNode>(name, node)
      }
    }
    val tags = into.withArray("tags")
    from.path("tags").forEach { tag ->
      if (tags.none { it.path("name") == tag.path("name") }) tags.add(tag)
    }
  }

  private fun same(what: String, before: JsonNode?, now: JsonNode) {
    check(before == null || before == now) { "The contract files define $what differently." }
  }
}
