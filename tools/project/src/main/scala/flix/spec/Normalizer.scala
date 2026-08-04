package flix.spec

/** Rewrites a projected tree into its canonical, normalised form by applying [[Transparency]]'s rules.
  *
  * **Oracle-free by construction.** This file must not import `ca.uwaterloo.flix.*`, and the rest of the repository
  * depends on that: normalisation is re-applied by [[NormalizationCheck]] and by [[Conformance]]'s recovery lane, both
  * of which run without the pinned jar on the classpath being meaningful. It reads JSON in and writes JSON out, and it
  * knows nothing about how that JSON was produced.
  *
  * Two rewrites, in one bottom-up pass:
  *
  *   - `elide` — a node with no children disappears; a node with exactly one child is replaced by that child; a node
  *     with two or more children is left alone. The third case is the important one: splicing a branching node into its
  *     parent would discard genuine structure and let a real disagreement pass as a normalisation decision.
  *   - `splice` — a node's children replace it in its parent's child list, at any arity. Reserved for the error
  *     vocabulary.
  *
  * Elision is applied repeatedly at each position, not once: `Type.Type` re-closes over itself at every level of the
  * type-precedence climb, so a chain of them collapses only if the rewrite keeps looking. This is the same fixed-point
  * shape [[Conformance]]'s comparison-time elision already uses, for the same reason.
  *
  * **What normalisation may never do is lose a token.** Both rules only ever remove a node; every token child is
  * re-attached to the surviving ancestor in its original order, so concatenating token text is invariant under
  * normalisation. `lossless` asserts exactly that over both trees, which is what makes the claim a measurement rather
  * than a comment.
  *
  * The root is never rewritten. It has no parent to be spliced into, and `Root` is not a wrapper in any case; guarding
  * it here means the rules can be stated without a "unless it is the root" clause everywhere else.
  */
object Normalizer {

  /** Applies `contract` to one projected tree.
    *
    * The argument is the `tree` member of a compilation unit, and the result has the same shape: a node object with
    * `kind`, `span` and `children`, whose token leaves are untouched.
    */
  def normalize(tree: Json, contract: Transparency.Contract): Json =
    tree.get("kind") match {
      case None => tree // a token leaf; nothing here is ever rewritten
      case Some(_) =>
        val rewritten = childrenOf(tree).flatMap(child => rewrite(normalize(child, contract), contract))
        tree match {
          case Json.JObject(fields) => Json.JObject(fields.updated("children", Json.JArray(rewritten)))
          case other                => other
        }
    }

  /** What one child becomes in its parent's child list: nothing, itself, or its own children.
    *
    * Recursive rather than single-step, so a chain collapses to a fixed point in one call: `Type.Type` re-closes over
    * itself at every level of the type-precedence climb, and a rule that fired once would leave the rest standing.
    */
  private def rewrite(child: Json, contract: Transparency.Contract): List[Json] =
    child.get("kind").map(_.asString) match {
      case Some(kind) if contract.splice.contains(kind) =>
        childrenOf(child).flatMap(rewrite(_, contract))
      case Some(kind) if contract.elide.contains(kind) =>
        val kids = childrenOf(child)
        // Two or more children is the case that must be left alone: splicing a branching node into its parent would
        // discard genuine structure rather than a wrapper.
        if (kids.length > 1) List(child) else kids.flatMap(rewrite(_, contract))
      case _ => List(child)
    }

  private def childrenOf(node: Json): List[Json] = node.get("children").map(_.asArray).getOrElse(Nil)

  // ---------------------------------------------------------------- rendering

  /** Renders a projected tree in exactly the form [[ProjectionExtractor.printTree]] emits.
    *
    * Byte-identical output is not a nicety here. `fixtures/raw` and `fixtures/expected` are both committed and both
    * under a diff gate, so a renderer that agreed semantically but disagreed about, say, key order would make every
    * regeneration a diff. `NormalizerTest` asserts the identity case -- rendering a parsed raw tree reproduces the
    * file's own bytes -- so the two writers cannot drift apart silently.
    */
  def render(node: Json): String = {
    val sb = new StringBuilder
    renderInto(node, sb)
    sb.toString
  }

  private def renderInto(node: Json, sb: StringBuilder): Unit =
    node.get("kind") match {
      case Some(kind) =>
        sb.append("{\"kind\":\"").append(ProjectionExtractor.esc(kind.asString)).append("\"")
        node.get("span").foreach { span =>
          sb.append(",\"span\":{\"start\":")
          renderPosition(span("start"), sb)
          sb.append(",\"end\":")
          renderPosition(span("end"), sb)
          sb.append("}")
        }
        sb.append(",\"children\":[")
        childrenOf(node).zipWithIndex.foreach { case (child, i) =>
          if (i > 0) sb.append(",")
          renderInto(child, sb)
        }
        sb.append("]}")
      case None =>
        sb.append("{\"token\":\"").append(ProjectionExtractor.esc(node("token").asString)).append("\"")
        sb.append(",\"text\":\"").append(ProjectionExtractor.esc(node("text").asString)).append("\",\"start\":")
        renderPosition(node("start"), sb)
        sb.append(",\"end\":")
        renderPosition(node("end"), sb)
        sb.append("}")
    }

  private def renderPosition(pos: Json, sb: StringBuilder): Unit =
    sb.append("{\"line\":").append(pos("line").asInt).append(",\"col\":").append(pos("col").asInt).append("}")

  /** Normalises a rendered tree and renders the result: the form [[ProjectionExtractor]] actually needs, since it
    * builds its raw tree as text.
    */
  def normalizeRendered(renderedTree: String, contract: Transparency.Contract): String =
    render(normalize(Json.parse(renderedTree), contract))
}
