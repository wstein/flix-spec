package flix.spec

/** The token-accounting invariant: a tree must account for every non-whitespace character of its source.
  *
  * This is an **oracle-free** property, and there are very few of those here. Every other check compares a consumer
  * against expectations derived from the reference, so it inherits the reference's defects by construction. This one
  * compares a tree against its *input*, so it needs no independent specification and can say something about the
  * reference itself.
  *
  * The rule, stated exactly: concatenating every token's `text`, in order, must reproduce the source file -- ignoring
  * whitespace and the `$` escape marker, neither of which belongs to any token.
  *
  * It is a **token-accounting** invariant, not byte-exact round-tripping, and the distinction matters when quoting the
  * result: two things are normalised away, and both are the lexer's own behaviour rather than conveniences granted to
  * make the check pass.
  *
  * The precision of the second was measured, not designed. A broader form of the `$` rule held on every fixture and
  * failed on six cleanly-parsed corpus files, every one an escaped Java name -- which is the argument for having a
  * corpus at all, since no curated suite was going to produce that.
  *
  * Three call sites need this and previously carried it as three copies of the same regex pair: [[LosslessCheck]] over
  * the committed fixtures, [[ReachabilityRun]] over the whole corpus, and [[Conformance]]'s source-invariants lane over
  * a consumer's own output. A normalisation rule that drifts between call sites would make the three disagree about
  * what "lossless" means while all three reported success.
  */
object TokenAccounting {

  /** Normalises a source or a reconstruction for comparison.
    *
    * Two things are removed, and only two:
    *
    *   - **whitespace**, which Flix does not emit as tokens at all, so byte-exact reconstruction is impossible by
    *     construction;
    *   - the **`$` escape marker** before a name. `Lexer.scala:519-521` moves past it explicitly ("Don't include the $
    *     sign in the name"), so in `x.$and(y)` the token spans `and` and the `$` belongs to no token. It is a marker,
    *     like whitespace, not content.
    *
    * The `$` rule is deliberately narrow -- only when followed by a name character -- so string interpolation
    * (`${expr}`, where `$` precedes `{`) still has to round-trip, and a genuinely dropped `$` inside a string literal
    * is still caught.
    */
  def squeeze(s: String): String =
    s.replaceAll("\\s+", "").replaceAll("\\$(?=[A-Za-z_])", "")

  /** Appends every token leaf's `text`, in order. A node is a token leaf exactly when it carries no `kind`. */
  def appendTokenText(node: Json, sb: StringBuilder): Unit =
    node.get("kind") match {
      case Some(_) => node.get("children").map(_.asArray).getOrElse(Nil).foreach(appendTokenText(_, sb))
      case None    => node.get("text").foreach(t => sb.append(t.asString))
    }

  /** The normalised text a projected tree accounts for. */
  def reconstruct(tree: Json): String = {
    val sb = new StringBuilder
    appendTokenText(tree, sb)
    squeeze(sb.toString)
  }

  /** Whether a projected tree carries any token text at all.
    *
    * Consumers are not required to emit tokens -- `docs/PROJECTION.md` gates kind, child order and nesting, and leaves
    * spans and tokens uncompared, so a purely structural adapter legitimately emits neither. Such output cannot be
    * checked for token accounting, and reporting that as a *failure* would penalise a consumer for a choice the
    * contract permits. Reporting it as a pass would be worse: it would claim a property nothing established.
    */
  def carriesTokens(tree: Json): Boolean = {
    def walk(n: Json): Boolean = n.get("kind") match {
      case Some(_) => n.get("children").map(_.asArray).getOrElse(Nil).exists(walk)
      case None    => n.get("text").isDefined
    }
    walk(tree)
  }

  /** A human-readable account of where two normalised strings first differ, with surrounding context. */
  def describeDivergence(fromTree: String, fromSource: String, window: Int = 30): String = {
    val i = fromTree.zip(fromSource).indexWhere { case (a, b) => a != b }
    val at = if (i < 0) math.min(fromTree.length, fromSource.length) else i
    def slice(s: String) = s.slice(math.max(0, at - window), at + window)
    s"    tree  (${fromTree.length} chars): …${slice(fromTree)}…\n" +
      s"    source(${fromSource.length} chars): …${slice(fromSource)}…"
  }
}
