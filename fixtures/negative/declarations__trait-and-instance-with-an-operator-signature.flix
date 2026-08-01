trait T[a] {
    pub def <=>(x: a, y: a): Bool
}

instance T[Int32] {
    pub def <=>(x: Int32, y: Int32): Bool = true
}
