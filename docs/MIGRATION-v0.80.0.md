# Flix v0.80.0 parser migration

Flix v0.80.0 adds `KeywordVal` and `Decl.Val`. The parser accepts module-level
`val` declarations and reserves `val` as a keyword; `let` remains an expression
keyword. The fixtures describe parser acceptance, not successful compilation.
In particular, a reference to a module `val` reaches an internal compiler error
in the v0.80.0 typer: `val expressions are not yet supported past kinding`
(`ConstraintGen.scala`, line 52). A parse-positive fixture is therefore not a
claim that this program compiles.

Historical release profiles retain their own oracle identities and snapshots;
the 0.79 profile and its source probes remain unchanged. The 0.80 profile
records the same bounded parser probes against the 0.80 release asset.

The release corpus contains 895 Flix files; 894 parse without errors and all
894 are lossless under the token-accounting check. The remaining input is the
unchanged, intentionally truncated `main/test/flix/resiliency/ford-fulkerson-prefix.flix`.
Both the 0.79 and 0.80 parsers report two `UnexpectedToken` diagnostics for it.

The 168 fixtures cover 187 of 193 tree kinds and 159 of 160 token kinds. The
six uncovered tree kinds and `Eof` are documented as structurally unattachable
in `ast/unattachable.json`. `Decl.Val` and `KeywordVal` are covered by fixtures
but do not occur in the release corpus. The existing transparency rules are
unchanged; normalization removes 2,316 of 4,750 fixture nodes.

For a separate compiler check, pure lambdas and local definitions passed where
an IO function is expected are rejected by 0.79 without its experimental flag
and accepted by 0.80. The reverse direction, an IO function passed where a pure
function is expected, remains rejected by both releases. These observations are
about type checking and are outside the parser fixture contract.
