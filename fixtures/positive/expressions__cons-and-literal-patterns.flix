def f(): Int32 = match xs {
    case 1 :: rest => 1
    case -2 :: rest => 2
    case Nil => 0
}
