def f(): Unit = #{
    Stratum(pd; 0) :- PositiveDependencyEdge(pd, _).
    Reach(x, z; cons(z, p)) :- Reach(x, y; p), Edge(y, u, f, z).
}
