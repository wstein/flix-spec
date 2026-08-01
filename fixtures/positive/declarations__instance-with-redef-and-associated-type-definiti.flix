instance Order[Option[a]] with Order[a] {
    type Elm[Option[a]] = a
    pub def compare(x: Option[a], y: Option[a]): Comparison = ???
    redef minimum(): Option[a] = None
}
