package spike

import ca.uwaterloo.flix.language.ast.SyntaxTree.TreeKind

import scala.reflect.runtime.{universe => ru}

/** Phase 0 spike: enumerates every TreeKind via Scala reflection over the sealed hierarchy (Route B, flix-spec plan
  * section 4), rather than by parsing SyntaxTree.scala as source text (Route A).
  */
object ListKinds {

  def allLeafSymbols(sym: ru.ClassSymbol): List[ru.ClassSymbol] = {
    if (sym.isSealed) {
      sym.knownDirectSubclasses.toList.flatMap(s => allLeafSymbols(s.asClass))
    } else {
      List(sym)
    }
  }

  def main(args: Array[String]): Unit = {
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val root = ru.typeOf[TreeKind].typeSymbol.asClass

    val leaves = allLeafSymbols(root).distinct
    val names = leaves.map(_.name.toString).sorted

    println(s"total: ${names.length}")
    println(s"case-object-like leaves (excluding ErrorTree): ${names.count(_ != "ErrorTree")}")
    names.foreach(n => println(s"  $n"))
  }
}
