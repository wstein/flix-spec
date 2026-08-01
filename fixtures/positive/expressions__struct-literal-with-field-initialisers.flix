def f(): Unit = region rc { new Counter @ rc { count = 0, step = 1 } }
