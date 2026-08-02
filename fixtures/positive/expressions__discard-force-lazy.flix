def f(): Unit = {
    let a = lazy 1;
    let b = force a;
    discard b
}
