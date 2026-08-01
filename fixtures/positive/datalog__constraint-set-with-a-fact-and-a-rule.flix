def f(): Unit =
    let pr = #{
        Reachable(origin).
        Reachable(y) :- Reachable(x), Edge(x, y).
    };
    pr
