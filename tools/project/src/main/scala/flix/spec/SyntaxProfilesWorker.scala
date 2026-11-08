package flix.spec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** A separate JVM's system classpath contains ONLY these classes and the selected official fat jar. Reflection bridges
  * the pre-0.76.1 Input/Source API; no current Flix classes are linked here.
  */
object SyntaxProfilesWorker {
  private val prefix = "ca.uwaterloo.flix."
  private def module(name: String): AnyRef = Class.forName(prefix + name + "$").getField("MODULE$").get(null)
  private def call(obj: AnyRef, method: String, args: AnyRef*): AnyRef = {
    val candidates = obj.getClass.getMethods.filter(m => m.getName == method && m.getParameterCount == args.size)
    require(candidates.length == 1, s"ambiguous/missing ${obj.getClass.getName}.$method/${args.size}")
    candidates.head.invoke(obj, args: _*)
  }
  private def make(name: String, args: AnyRef*): AnyRef = {
    val constructors = Class.forName(prefix + name).getConstructors.filter(_.getParameterCount == args.size)
    require(constructors.length == 1, s"ambiguous/missing constructor $name/${args.size}")
    constructors.head.newInstance(args: _*).asInstanceOf[AnyRef]
  }
  private def quote(s: String): String = "\"" + s.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case '\n'         => "\\n"
    case '\r'         => "\\r"
    case '\t'         => "\\t"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  } + "\""
  private def render(tree: AnyRef): String = {
    val kind = call(tree, "kind")
    val isToken = tree.getClass.getName == prefix + "language.ast.Token"
    if (isToken) {
      // Err carries an exception with a SourceLocation; its toString embeds the absolute source path.
      val tokenName = if (kind.getClass.getName == prefix + "language.ast.TokenKind$Err") "Err" else kind.toString
      s"""{"token":${quote(tokenName)},"text":${quote(call(tree, "text").toString)}}"""
    } else {
      val base = Class.forName(prefix + "language.ast.SyntaxTree$TreeKind")
      val parent = kind.getClass.getInterfaces
        .find(i => i != base && base.isAssignableFrom(i))
        .map(_.getName.stripSuffix("$").split('$').last + ".")
        .getOrElse("")
      val name = parent + kind.getClass.getName.stripSuffix("$").split('$').last
      val children = call(tree, "children").asInstanceOf[Array[AnyRef]]
      s"""{"kind":${quote(name)},"children":[${children.iterator.map(render).mkString(",")}]}"""
    }
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "expected one source path")
    val file = Paths.get(args(0)).toAbsolutePath.normalize()
    val flixClass = Class.forName(prefix + "api.Flix")
    val constructors = flixClass.getConstructors
    require(constructors.length == 1, "unexpected Flix constructors")
    val arity = constructors.head.getParameterCount
    require(Set(0, 2, 3).contains(arity), s"unexpected Flix constructor arity: $arity")
    val ctorArgs: Seq[AnyRef] = if (arity == 3) Seq(Nil, Nil, Map.empty) else Seq.fill(arity)(Nil)
    val flix = make("api.Flix", ctorArgs: _*)
    val pool =
      try make("util.ThreadPool", Int.box(1))
      catch { case _: ClassNotFoundException => new java.util.concurrent.ForkJoinPool(1) }
    call(flix, "threadPool_$eq", pool)
    try {
      val security = module("language.ast.shared.SecurityContext$Plain")
      val text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
      val source =
        try {
          val input = make("language.ast.shared.Input$VirtualFile", file, text, security)
          call(module("language.ast.shared.Source"), "fromString", input, text)
        } catch {
          case _: ClassNotFoundException =>
            val name = make("language.ast.shared.SourceName$PathName", file)
            call(
              module("language.ast.shared.Source"),
              "fromString",
              name,
              module("language.ast.shared.Origin$User"),
              security,
              text
            )
        }
      val sources = Map(source -> ())
      val rootClass = Class.forName(prefix + "language.ast.ReadAst$Root")
      val root =
        if (rootClass.getConstructors.head.getParameterCount == 2)
          make("language.ast.ReadAst$Root", sources, call(module("language.ast.shared.AvailableClasses"), "empty"))
        else make("language.ast.ReadAst$Root", sources)
      val changes = module("language.ast.ChangeSet$Everything")
      val lexed = call(module("language.phase.Lexer"), "run", root, Map.empty, changes, flix).asInstanceOf[Product]
      val parsed = call(
        module("language.phase.Parser2"),
        "run",
        lexed.productElement(0).asInstanceOf[AnyRef],
        call(module("language.ast.SyntaxTree"), "empty"),
        changes,
        flix
      ).asInstanceOf[Product]
      val units =
        call(parsed.productElement(0).asInstanceOf[AnyRef], "units").asInstanceOf[scala.collection.Map[AnyRef, AnyRef]]
      require(units.size == 1, s"expected exactly one unit, got ${units.size}")
      val diagnostics = (lexed.productElement(1).asInstanceOf[scala.collection.Iterable[AnyRef]] ++
        parsed.productElement(1).asInstanceOf[scala.collection.Iterable[AnyRef]])
        .map(_.getClass.getSimpleName)
        .toList
        .sorted
      println(
        s"""{"accepted":${diagnostics.isEmpty},"diagnostics":[${diagnostics.map(quote).mkString(",")}],"tree":${render(
            units.head._2
          )}}"""
      )
    } finally call(pool, "shutdown")
  }
}
