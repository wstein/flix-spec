def f(): Unit = try g() catch {
    case e: Exception => 0
}
