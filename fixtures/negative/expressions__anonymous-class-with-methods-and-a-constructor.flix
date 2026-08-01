def f(): Unit = new Runnable {
    def new(): Runnable = ???
    def run(): Unit \ IO = println("x")
}
