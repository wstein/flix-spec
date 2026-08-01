def f(): Int32 = match x {
    case Some(y) if y > 0 => y
    case None            => 0
}
