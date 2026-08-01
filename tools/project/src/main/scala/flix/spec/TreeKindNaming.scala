package flix.spec

/** Canonical naming for `SyntaxTree.TreeKind` values.
  *
  * Shared by [[TreeKindExtractor]] (which builds `ast/treekind.json`) and [[ProjectionExtractor]] (which emits the
  * `kind` field of a projected tree) so the vocabulary and its consumers cannot drift apart.
  *
  * Bare names are not usable as identifiers. `SyntaxTree.TreeKind` has no `toString` override, so a nested case object
  * prints its simple name only, and 13 simple names are reused across sub-traits -- 28 leaf positions collapse to 13
  * strings. `Expr.Apply` and `Type.Apply` both print as `"Apply"`.
  *
  * Qualification follows the **type hierarchy**, not lexical nesting. The two disagree for exactly one kind at the
  * current pin: `case object DerivationList extends Type` is declared at `TreeKind` top level (`SyntaxTree.scala:98`)
  * but extends `Type`, so nesting says `DerivationList` while the hierarchy says `Type.DerivationList`.
  */
object TreeKindNaming {

  val TreeKindClass = "ca.uwaterloo.flix.language.ast.SyntaxTree$TreeKind"

  /** Last `$`-segment of a binary name: `...SyntaxTree$TreeKind$Expr$Apply$` -> `Apply`. */
  def simpleName(binaryName: String): String =
    binaryName.stripSuffix("$").split('$').last

  /** The sub-trait a kind belongs to (`Decl`, `Expr`, ...), or `TreeKind` for top-level kinds. */
  def parentOf(c: Class[_], treeKind: Class[_]): String =
    c.getInterfaces
      .find(i => i != treeKind && treeKind.isAssignableFrom(i))
      .map(i => simpleName(i.getName))
      .getOrElse("TreeKind")

  /** Sub-trait-qualified name, e.g. `Expr.Apply`, `Type.DerivationList`, `ErrorTree`. */
  def qualifiedName(parent: String, binaryName: String): String =
    if (parent == "TreeKind") simpleName(binaryName) else s"$parent.${simpleName(binaryName)}"

  /** Qualified name for a live `TreeKind` value, resolved from its runtime class. */
  def qualifiedNameOf(kind: AnyRef): String = {
    val c = kind.getClass
    val treeKind = c.getClassLoader.loadClass(TreeKindClass)
    qualifiedName(parentOf(c, treeKind), c.getName)
  }

  /** `case-object` or `case-class`, decided by the presence of Scala's `MODULE$` field -- never by matching against a
    * hardcoded name.
    */
  def formOf(c: Class[_]): String =
    if (c.getFields.exists(_.getName == "MODULE$")) "case-object" else "case-class"
}
