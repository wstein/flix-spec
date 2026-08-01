package flix.spec

import ca.uwaterloo.flix.language.ast.SyntaxTree.TreeKind

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.reflect.runtime.{universe => ru}

case class TreeKindInfo(
    name: String,
    `extends`: String,
    form: String
)

object TreeKindExtractor {

  def extractTreeKinds(): List[TreeKindInfo] = {
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val rootSym = ru.typeOf[TreeKind].typeSymbol.asClass

    def collectLeaves(sym: ru.ClassSymbol, parentPath: String): List[TreeKindInfo] = {
      if (sym.isSealed) {
        val children = sym.knownDirectSubclasses.toList.map(_.asClass)
        children.flatMap { child =>
          val rawName = child.name.toString
          val currentParent = if (parentPath == "TreeKind") "" else parentPath
          val newPath = if (currentParent.isEmpty) rawName else s"$currentParent.$rawName"
          if (child.isSealed) {
            collectLeaves(child, newPath)
          } else {
            val extendsName = if (currentParent.isEmpty) "TreeKind" else currentParent
            val form = if (rawName == "ErrorTree") "case-class" else "case-object"
            List(TreeKindInfo(newPath, extendsName, form))
          }
        }
      } else {
        val rawName = sym.name.toString
        val extendsName = if (parentPath == "TreeKind" || parentPath.isEmpty) "TreeKind" else parentPath
        val name =
          if (extendsName != "TreeKind" && !rawName.startsWith(s"$extendsName.")) s"$extendsName.$rawName" else rawName
        val form = if (rawName == "ErrorTree") "case-class" else "case-object"
        List(TreeKindInfo(name, extendsName, form))
      }
    }

    val kinds = collectLeaves(rootSym, "TreeKind").distinctBy(_.name).sortBy(_.name)

    // Self-assertion checks per implementation plan section 3.3 / 4.2
    require(kinds.length == 192, s"Expected exactly 192 TreeKind entries, got ${kinds.length}")
    require(kinds.map(_.name).distinct.length == 192, "Duplicate TreeKind names detected")

    kinds
  }

  def calculateDigest(kinds: List[TreeKindInfo]): String = {
    val sortedNames = kinds.map(_.name).sorted.mkString("\n")
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(sortedNames.getBytes(StandardCharsets.UTF_8))
    hash.map("%02x".format(_)).mkString
  }

  def formatJson(kinds: List[TreeKindInfo], digest: String): String = {
    val sb = new StringBuilder
    sb.append("{\n")
    sb.append("  \"schemaVersion\": 1,\n")
    sb.append(s"  \"treeKindCount\": ${kinds.length},\n")
    sb.append(s"  \"treeKindDigest\": \"$digest\",\n")
    sb.append("  \"kinds\": [\n")
    kinds.zipWithIndex.foreach { case (k, idx) =>
      val comma = if (idx < kinds.length - 1) "," else ""
      sb.append("    {\n")
      sb.append(s"      \"name\": \"${k.name}\",\n")
      sb.append(s"      \"extends\": \"${k.`extends`}\",\n")
      sb.append(s"      \"form\": \"${k.form}\"\n")
      sb.append(s"    }$comma\n")
    }
    sb.append("  ]\n")
    sb.append("}\n")
    sb.toString
  }

  def main(args: Array[String]): Unit = {
    val kinds = extractTreeKinds()
    val digest = calculateDigest(kinds)
    val json = formatJson(kinds, digest)

    if (args.nonEmpty) {
      val path = java.nio.file.Paths.get(args(0)).toAbsolutePath
      val parent = path.getParent
      if (parent != null) {
        java.nio.file.Files.createDirectories(parent)
      }
      java.nio.file.Files.writeString(path, json, StandardCharsets.UTF_8)
      println(s"Wrote ${kinds.length} TreeKinds to $path with digest $digest")
    } else {
      print(json)
    }
  }
}
