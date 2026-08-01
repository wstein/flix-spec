pub lawful trait Order[a] with Eq[a] {
    type Elm: Type
    pub def compare(x: a, y: a): Comparison
    law reflexive: forall(x: a) Order.compare(x, x) == Comparison.EqualTo
}
