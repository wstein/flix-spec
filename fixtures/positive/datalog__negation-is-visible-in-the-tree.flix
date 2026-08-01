def f(): Unit = #{
    P(x) :- Q(x).
    P(x) :- not Q(x).
    P(x) :- fix Q(x).
}
