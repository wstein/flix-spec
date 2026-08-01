def map(f: a -> b, e: Exp[s]): Exp[s] = choose* e {
    case Exp.Var(x) => open_variant Exp.Var(f(x))
    case Exp.Cst(b) => open_variant Exp.Cst(b)
}
