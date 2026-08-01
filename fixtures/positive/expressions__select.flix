def f(): Unit = select {
    case x <- Channel.recv(c) => x
    case _ => 0
}
