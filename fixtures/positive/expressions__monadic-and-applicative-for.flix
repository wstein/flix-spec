def f(): Unit = forM (x <- xs; y = 1) yield x + y
