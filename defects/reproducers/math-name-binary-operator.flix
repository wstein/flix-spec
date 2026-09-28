// A user-defined operator named with a math symbol, used infix.
//
// Parser2 accepts it: TokenKind.NameMath is a binary operator (TokenKind.scala:454) and the
// expression closes as Expr.Binary with the symbol under Operator. Weeder2.visitBinaryExpr then
// matches the operator token against a fixed set that does not include NameMath, and falls through
// to `throw InternalCompilerException("Expr.Binary operator not recognized")`.
def ⊆(x: Bool, y: Bool): Bool = x and y

def main(): Bool = true ⊆ false
