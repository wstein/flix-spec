package flix.spec

/** A minimal, dependency-free JSON reader.
  *
  * [[TreeKindExtractor]]'s own comment explains why this repository does not pull in a JSON library for its own
  * generated, fixed-shape files: a full parser would mean either a new dependency or relying on one bundled inside the
  * oracle jar, and both are worse trades than a few hundred lines owned here. That reasoning covers flat scalar lookups
  * fine, but the schema validators and the conformance comparator (implementation plan section 7, Phase 3) need to walk
  * arbitrarily nested trees -- `fixtures/expected&#47;*.json`'s `children` arrays recurse to whatever depth the source
  * parses to -- which a handful of regexes cannot do honestly. This is the one real parser the plan's "no regex over
  * structure, JVM only" rule implies once the scripts it used to permit as an exception (Python, read via `json.load`)
  * are gone too.
  *
  * Read-only on purpose: every consumer of this file (schema validators, the conformance comparator) only reads
  * committed or generated JSON. Anything this repository writes is still hand-built with a `StringBuilder`, matching
  * [[TreeKindExtractor]] and [[ProjectionExtractor]], so output formatting stays exactly as deliberate as it already
  * was.
  */
sealed trait Json {
  import Json._

  def apply(key: String): Json = this match {
    case JObject(fields) => fields.getOrElse(key, JNull)
    case _               => JNull
  }

  def get(key: String): Option[Json] = this match {
    case JObject(fields) => fields.get(key)
    case _               => None
  }

  def asObject: Map[String, Json] = this match {
    case JObject(fields) => fields
    case other           => throw new JsonException(s"expected object, got $other")
  }

  def asArray: List[Json] = this match {
    case JArray(items) => items
    case other         => throw new JsonException(s"expected array, got $other")
  }

  def asString: String = this match {
    case JString(value) => value
    case other          => throw new JsonException(s"expected string, got $other")
  }

  def asInt: Int = this match {
    case JNumber(value) => value.toIntExact
    case other          => throw new JsonException(s"expected integer, got $other")
  }

  def isNull: Boolean = this == JNull
}

object Json {
  final class JsonException(message: String) extends RuntimeException(message)

  case class JObject(fields: Map[String, Json]) extends Json
  case class JArray(items: List[Json]) extends Json
  case class JString(value: String) extends Json
  case class JNumber(value: BigDecimal) extends Json
  case class JBool(value: Boolean) extends Json
  case object JNull extends Json

  def parseFile(path: java.nio.file.Path): Json =
    parse(java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8))

  def parse(text: String): Json = {
    val p = new Parser(text)
    p.skipWhitespace()
    val v = p.parseValue()
    p.skipWhitespace()
    if (!p.atEnd) throw new JsonException(s"trailing content at offset ${p.pos}")
    v
  }

  private final class Parser(text: String) {
    var pos: Int = 0

    def atEnd: Boolean = pos >= text.length
    private def peek: Char = text(pos)
    private def advance(): Char = { val c = text(pos); pos += 1; c }

    def skipWhitespace(): Unit =
      while (!atEnd && peek.isWhitespace) pos += 1

    private def expect(c: Char): Unit = {
      if (atEnd || peek != c) throw new JsonException(s"expected '$c' at offset $pos")
      pos += 1
    }

    def parseValue(): Json = {
      skipWhitespace()
      if (atEnd) throw new JsonException("unexpected end of input")
      peek match {
        case '{'                        => parseObject()
        case '['                        => parseArray()
        case '"'                        => JString(parseStringLiteral())
        case 't'                        => parseLiteral("true", JBool(true))
        case 'f'                        => parseLiteral("false", JBool(false))
        case 'n'                        => parseLiteral("null", JNull)
        case c if c == '-' || c.isDigit => parseNumber()
        case c                          => throw new JsonException(s"unexpected character '$c' at offset $pos")
      }
    }

    private def parseLiteral(literal: String, value: Json): Json = {
      if (pos + literal.length > text.length || text.substring(pos, pos + literal.length) != literal)
        throw new JsonException(s"expected '$literal' at offset $pos")
      pos += literal.length
      value
    }

    private def parseObject(): JObject = {
      expect('{')
      skipWhitespace()
      var fields = Map.empty[String, Json]
      if (!atEnd && peek == '}') { pos += 1; return JObject(fields) }
      while (true) {
        skipWhitespace()
        val key = parseStringLiteral()
        skipWhitespace()
        expect(':')
        val value = parseValue()
        fields += key -> value
        skipWhitespace()
        if (!atEnd && peek == ',') { pos += 1 }
        else { expect('}'); return JObject(fields) }
      }
      JObject(fields) // unreachable
    }

    private def parseArray(): JArray = {
      expect('[')
      skipWhitespace()
      val items = List.newBuilder[Json]
      if (!atEnd && peek == ']') { pos += 1; return JArray(items.result()) }
      while (true) {
        items += parseValue()
        skipWhitespace()
        if (!atEnd && peek == ',') { pos += 1 }
        else { expect(']'); return JArray(items.result()) }
      }
      JArray(items.result()) // unreachable
    }

    private def parseStringLiteral(): String = {
      expect('"')
      val sb = new StringBuilder
      while (peek != '"') {
        val c = advance()
        if (c == '\\') {
          advance() match {
            case '"'  => sb.append('"')
            case '\\' => sb.append('\\')
            case '/'  => sb.append('/')
            case 'b'  => sb.append('\b')
            case 'f'  => sb.append('\f')
            case 'n'  => sb.append('\n')
            case 'r'  => sb.append('\r')
            case 't'  => sb.append('\t')
            case 'u' =>
              val hex = text.substring(pos, pos + 4)
              pos += 4
              sb.append(Integer.parseInt(hex, 16).toChar)
            case other => throw new JsonException(s"invalid escape '\\$other'")
          }
        } else sb.append(c)
      }
      pos += 1 // closing quote
      sb.toString
    }

    private def parseNumber(): JNumber = {
      val start = pos
      if (!atEnd && peek == '-') pos += 1
      while (!atEnd && peek.isDigit) pos += 1
      if (!atEnd && peek == '.') { pos += 1; while (!atEnd && peek.isDigit) pos += 1 }
      if (!atEnd && (peek == 'e' || peek == 'E')) {
        pos += 1
        if (!atEnd && (peek == '+' || peek == '-')) pos += 1
        while (!atEnd && peek.isDigit) pos += 1
      }
      JNumber(BigDecimal(text.substring(start, pos)))
    }
  }
}
