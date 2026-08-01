def f(): Unit =
    inject map(g, pos), map(g, neg) into PositiveEdge/2, NegativeEdge/2
