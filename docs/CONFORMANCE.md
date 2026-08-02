# Conformance checking

How an independent parser checks that it agrees with the Flix reference compiler, using this
repository's fixtures.

## The split

The work divides in two, and the division is the point. Four repositories re-deriving the same
comparison four times is the duplication `flix-spec` exists to end.

| Side | Who | What |
| --- | --- | --- |
| Produce | the consumer | Parse each fixture, emit a canonical projected tree per `schemas/projection.schema.json` |
| Compare | `flix-spec` | [`Conformance`](../tools/project/src/main/scala/flix/spec/Conformance.scala) diffs those trees against `fixtures/expected/` |

```mermaid
flowchart LR
    FIX["fixtures/*.flix"]
    EXP["fixtures/expected/*.json<br/>generated from the pinned oracle"]
    CONS["consumer parser"]
    ACT["consumer projected trees"]
    MAP["ast/projection/&lt;consumer&gt;.json"]
    REP["conformance report<br/>divergence count"]

    FIX --> EXP
    FIX --> CONS --> ACT
    ACT --> REP
    EXP --> REP
    MAP -.->|"vocabulary + transparency"| REP
```

## What is compared

Per [`PROJECTION.md`](PROJECTION.md) section 3:

- **gated**: node kind, child order, nesting;
- **not compared**: spans and tokens. Token vocabularies differ legitimately between parsers and
  spans are advisory, so comparing either would report differences that are not disagreements
  about structure.

## Transparency, and why it is symmetric

Parsers disagree about wrapper nodes far more than about structure. At the current pin **412 of
3572 canonical nodes (11.5%) are single-child wrappers**, `Type.Type` alone accounting for 247. A
parser that was not the reference's parent will not reproduce them, and that is not a defect.

So the projection map declares transparency on both sides:

- `elide` — canonical kinds the consumer does not produce (`Type.Type`, an empty `Doc`, a
  single-child `QName`);
- `ignored` — the consumer's own wrappers with no counterpart in the reference.

A transparent node is **dropped when empty** and **replaced by its child when it has exactly one**.
With two or more children it is *kept*: splicing its children into the parent would discard real
structure and let a genuine disagreement pass as a mapping decision.

Handling only one side is worse than handling neither — the other side's wrapper then faces a real
node and reports as a disagreement.

Each rule was added because measurement demanded it, not by design. Against `tree-sitter-flix`:

| Change | Divergences |
| --- | ---: |
| naive kind comparison | 233 |
| elide *empty* wrappers, not just single-child ones | 196 |
| make transparency symmetric (splice, never force a match) | 142 |
| elide single-child `QName` | 80 |

Those four are measured on the 110 fixtures the first adapter could read; the baseline below covers
all 113.

## Lexical consumers

A syntax highlighter or TextMate grammar has no parse tree, so it can never consume
`fixtures/expected/`. Its contract is [`ast/tokenkind.json`](../ast/tokenkind.json) — the 160
`TokenKind`s the reference lexer defines, 159 case objects plus `Err`, reflected from the pinned
jar and pinned by digest in `pin.json`.

That exists so lexical consumers stop scraping `Lexer.scala` as text. Text scraping cannot be
checked against a digest, breaks silently when upstream reformats, and has already produced a
committed lexicon in this ecosystem provenanced to a *fork* rather than to the pin.

`TokenKind` is a flat hierarchy, so unlike `TreeKind` its names need no qualification and cannot
collide.

## Unmapped is not divergent

A native node that is neither mapped nor ignored is counted as **unmapped**, never as a
divergence. "We have not mapped this yet" is a different fact from "we disagree with the
reference", and collapsing the two would make an incomplete map look like a broken parser.

## Running it

```sh
# Identity: the expectations must agree with themselves.
./gradlew :tools:project:conformance --args="--actual fixtures/expected"

# A consumer, with its vocabulary map and a ratchet.
./gradlew :tools:project:conformance --args="\
    --actual path/to/consumer/output \
    --map ast/projection/tree-sitter-flix.json \
    --report build/conformance.json \
    --baseline 85"
```

Exit status is non-zero when divergences exceed `--baseline`, so the ratchet is the gate. Lower the
baseline as divergences are fixed; never raise it without saying why.

## Measured baselines

| Consumer | Fixtures agreeing | Divergences | Nodes compared | Measured against |
| --- | --- | --- | --- | --- |
| `tree-sitter-flix` | 74 / 113 | 85 | 685 | `8875cfb4`, tree-sitter CLI 0.26.11 |

Reproducing this needs the `tree-sitter` CLI and a built grammar, so it is **not** part of CI here;
the consumer repository is the right home for that job. What CI does verify is that the comparison
itself is sound: `fixtures/expected` must agree with itself at zero divergences, and a deliberately
mutated copy must be detected.

All 113 fixtures are compared, including the negative ones.

An earlier baseline reported 110 and blamed the grammar for three missing outputs. That was wrong,
and the way it was wrong is worth recording: when a parse contains an `ERROR` node, the
`tree-sitter` CLI appends a plain-text timing line *after* `</sources>`, which is not XML and
breaks a strict parser. The three affected files were exactly the three negative fixtures — the
only ones that produce an `ERROR`. Nothing was wrong with the grammar; the adapter had to truncate
at the closing tag. A consumer-side adapter that silently drops the inputs it cannot read will
always flatter its own parser, so count what was skipped and why.

## Writing a projection map

Start empty and let the report drive it: every run lists `unmappedNames` in frequency order, so the
next most valuable mapping is always the top of that list. Map what is genuinely the same node;
declare wrappers transparent; leave the rest unmapped rather than guessing, because a wrong mapping
reports as agreement and is worse than a gap.

`mappings` values are validated against `ast/treekind.json`, so a typo or a stale kind name fails
immediately instead of silently never matching.
