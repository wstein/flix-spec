def f(): Unit = {
    unsafe IO as r { g() };
    throw new Exception()
}
