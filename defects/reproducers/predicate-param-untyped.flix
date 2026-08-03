// Reproducer for FLIX-0001: Predicate.ParamUntyped is dead by assignment.
//
// `#(A)` is a predicate parameter list whose single parameter carries no type, which is precisely
// the shape TreeKind.Predicate.ParamUntyped names. Parser2.param() initialises `var kind` to
// ParamUntyped and then overwrites it with Predicate.Param unconditionally, before any branch, so
// close(mark, kind) can only ever receive Predicate.Param.
//
// The file parses without diagnostics on purpose: the defect is not a parse failure, it is a tree
// that names the wrong kind. Weeder2 still pattern-matches on ParamUntyped, so the producer and the
// consumer of this kind disagree about whether it can occur.
def f(): Unit =
    let _p = #(A) -> #{ A(1). };
    ()
