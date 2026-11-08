trait LawProfile[a] {
    pub def identity(x: a): a
    law identityLaw: forall (x: a) identity(x) == x
}
