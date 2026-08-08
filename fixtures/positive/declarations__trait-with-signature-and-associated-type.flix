pub trait Order[a] with Eq[a] {
    type Elm: Type
    pub def compare(x: a, y: a): Comparison
}
