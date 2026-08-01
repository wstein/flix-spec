def f(): Unit = query db select (x, y) from Edge(x, y) where x < y
