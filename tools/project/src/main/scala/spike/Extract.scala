package spike

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{ChangeSet, SourcePosition, SyntaxTree, Token, TokenKind}
import ca.uwaterloo.flix.language.ast.shared.{AvailableClasses, Input, SecurityContext}
import ca.uwaterloo.flix.language.phase.{Lexer, Parser2, Reader}

import java.nio.file.Paths

/** Phase 0 spike: drives Reader -> Lexer -> Parser2 directly (no Flix.check()) and prints the resulting SyntaxTree as a
  * canonical projected-tree JSON, per flix-spec plan section 3.1 / phase 0 question 1-2.
  */
object Extract {

  def esc(s: String): String = {
    val sb = new StringBuilder
    for (c <- s) c match {
      case '"'           => sb.append("\\\"")
      case '\\'          => sb.append("\\\\")
      case '\n'          => sb.append("\\n")
      case '\r'          => sb.append("\\r")
      case '\t'          => sb.append("\\t")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c             => sb.append(c)
    }
    sb.toString
  }

  def kindName(kind: SyntaxTree.TreeKind): String = kind match {
    case SyntaxTree.TreeKind.ErrorTree(_) => "ErrorTree"
    case other                            => other.toString
  }

  def tokenKindName(kind: TokenKind): String = kind match {
    case TokenKind.Err(_) => "Err"
    case other            => other.toString
  }

  def printPos(p: SourcePosition): String =
    s"""{"line":${p.lineOneIndexed},"col":${p.colOneIndexed}}"""

  def printToken(t: Token): String =
    s"""{"token":"${esc(tokenKindName(t.kind))}","text":"${esc(t.text)}","start":${printPos(t.start)},"end":${printPos(
        t.end
      )}}"""

  def printTree(tree: SyntaxTree.Tree): String = {
    val children = tree.children.iterator
      .map {
        case tok: Token           => printToken(tok)
        case sub: SyntaxTree.Tree => printTree(sub)
        case other                => s"""{"unknown":"${esc(String.valueOf(other))}"}"""
      }
      .mkString(",")
    s"""{"kind":"${esc(kindName(tree.kind))}","span":{"start":${printPos(tree.loc.start)},"end":${printPos(
        tree.loc.end
      )}},"children":[$children]}"""
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      System.err.println("usage: Extract <path-to-flix-file>")
      sys.exit(1)
    }

    val path = Paths.get(args(0)).toAbsolutePath
    val inputs = List(Input.RealFile(path, SecurityContext.Plain))

    implicit val flix: Flix = new Flix()
    flix.threadPool = new java.util.concurrent.ForkJoinPool(1)

    val (afterReader, readerErrors) = Reader.run(inputs, AvailableClasses.empty)
    if (readerErrors.nonEmpty) {
      System.err.println(s"Reader errors: $readerErrors")
      sys.exit(1)
    }

    val (afterLexer, lexerErrors) = Lexer.run(afterReader, Map.empty, ChangeSet.Everything)
    if (lexerErrors.nonEmpty) {
      System.err.println(s"Lexer errors: $lexerErrors")
    }

    val (afterParser, parserErrors) = Parser2.run(afterLexer, SyntaxTree.empty, ChangeSet.Everything)
    if (parserErrors.nonEmpty) {
      System.err.println(s"Parser errors: $parserErrors")
    }

    val units = afterParser.units.toList.map { case (src, tree) =>
      s"""{"source":"${esc(src.name)}","tree":${printTree(tree)}}"""
    }

    println(s"""{"units":[${units.mkString(",")}]}""")
  }
}
