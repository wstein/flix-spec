def f(): Unit = region rc {
    spawn g() @ rc;
    par (a <- h()) yield a
}
