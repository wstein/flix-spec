# Versioning the Maven package

`flix-spec` publishes a Maven artifact (`io.github.wstein:flix-spec`) bundling `pin.json`, `ast/`,
`schemas/`, `fixtures/` and `corpus/corpus.json`, hosted on GitHub Pages
(`https://wstein.github.io/flix-spec/maven/`). This records the versioning scheme and why the
obvious alternatives don't hold up.

## The scheme

```
<schemaMajor>.<schemaMinor>.<toolPatch>-flix<upstream-version>[-SNAPSHOT]
```

Example: `0.1.0-flix0.75.1`.

- `schemaMajor.schemaMinor.toolPatch` (`gradle.properties`' `version`, hand-maintained) is ordinary
  semver over **this repository's own generated shape**: major on a breaking schema change (a
  `TreeKind` removed or reparented, `pin.json.schemaVersion` bumped), minor on additive change (new
  kinds, new fixtures), patch on any regeneration that changes bytes without changing shape (an
  extractor bugfix, a pin bump with no schema change).
- `-flix<upstream-version>` (computed from `pin.json.upstream.tag`) is a semver **pre-release
  identifier**, deliberately not build metadata. Per [semver.org rule
  10](https://semver.org/#spec-item-10), build metadata (`+...`) is precedence-ignored — a resolver
  would treat `1.0.0+flix0.75.1` and `1.0.0+flix0.76.0` as the *same* version. Pre-release
  identifiers participate in ordering (rule 11), so the flix pin actually changes the version
  consumers see and compare.
- `-SNAPSHOT` marks a floating, mutable build (`main`-branch CI, via `-PflixSpec.snapshot=true`).
  Tagged releases omit it and must be immutable once published.

## Why not the plan's original `v<n>+flix0.75.1`

An earlier draft proposed encoding the pin as build metadata on a release-asset tag. That was fine
when the target was a GitHub Releases zip — tags don't have resolver semantics. It stops being fine
the moment the same string becomes an actual Maven coordinate: rule 10 above means two different
flix pins would compare equal, and a dependency graph containing both (transitively, from two
different consumers) could silently coalesce or arbitrarily pick one.

## Why not bare Debian-style `<upstream>-<revision>`

`0.75.1-1`, `0.75.1-2`, ... is legible and has real precedent (Debian, Homebrew formula revisions),
but the counter carries no signal about *what kind* of change happened. This repository's revisions
are not packaging-only touch-ups the way Debian's are — they can be schema-breaking. Folding
`schemaMajor.schemaMinor` into the base version keeps that distinction visible without a second,
uncorrelated counter.

## The immutability constraint

GitHub Pages is not Maven Central: nothing at the transport layer stops a second publish from
overwriting a release version's files with different bytes. Every pin bump changes generated
content (`ast/treekind.json`, `fixtures/expected/`) even when no Scala code changed, so **every pin
bump must move the version** — there is no "just a metadata refresh" case. CI (`pages.yml`) enforces
this directly: it refuses to publish a non-`SNAPSHOT` version whose path already exists in the
published repository, because nothing else will catch a repeat.

## Not decided here

- Exact criteria for `schemaMajor` vs `schemaMinor` beyond "`schemaVersion` bump = major" (e.g.
  whether a coverage-only change ever counts as breaking).
- Retention policy for the accumulated snapshot history on the `gh-pages` branch.
