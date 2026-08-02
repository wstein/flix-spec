# Versioning the Maven package

`flix-spec` publishes a Maven artifact (`io.github.wstein:flix-spec`) bundling `pin.json`, `ast/`,
`schemas/`, `fixtures/` and `corpus/corpus.json`, hosted on GitHub Pages
(`https://wstein.github.io/flix-spec/maven/`).

## The scheme

```
<flixMajor>.<flixMinor>.<revision>[-SNAPSHOT]
```

Current: `0.75.1`, derived from `flix/flix` v0.75.1.

- **`flixMajor.flixMinor`** track the upstream Flix line. `0.75.x` is derived from Flix 0.75.x.
- **`revision`** is this repository's own counter within that line. It advances on every published
  build, whether the cause was an upstream patch release or a change here.
- **`-SNAPSHOT`** marks a floating build from `main`. Tagged releases omit it and are immutable
  once published; `pages.yml` refuses to republish an existing release path.

Plain semver, no decoration. It orders correctly in every ecosystem, and `latest.release` and
version ranges behave normally — none of which was true of the previous
`0.1.0-flix0.75.1` form, where the `-flix…` pre-release identifier sorted *below* a `0.1.0`
that would never exist.

## Why the pin is not in the version

It used to be, and that was a category error.

**A version string can advertise a pin. It can never enforce one.** This repository had a consumer
depending on fixtures derived from Flix `318bb51a` while its own tests read a checkout of a
different Flix entirely — a mismatch no naming convention detects, because the coordinate describes
the artifact and says nothing about the consumer.

Enforcement is a comparison someone makes. `flix-jetbrains-plugin` reads `pin.json` out of the
artifact and fails when it disagrees with the Flix that repository tests against. That is the lock.
Everything below is advertisement, and none of it distorts ordering:

| Where | What | Read by |
| --- | --- | --- |
| `FLIX-PIN-<tag>` | a marker file whose *name* is the pin | `jar tf`, `grep`, a one-line assertion |
| POM `<properties>` | `flix.tag`, `flix.commit`, `flix.treeHash`, `flix.oracleSha256` | tooling, without downloading the jar |
| `pin.json` | the authoritative record, including the oracle digest | anything that needs the truth |

## What a version bump does and does not tell you

Because major and minor belong to the upstream line, **this scheme cannot signal a breaking change
to flix-spec's own schemas** while Flix stands still. That is deliberate, and it is covered
elsewhere: every generated artifact carries its own `schemaVersion` — `treekind.json`,
`tokenkind.json`, `coverage.json`, `reachability.json`, every projected tree, every JSON Schema.
Compatibility is declared in-band, where it is checked, rather than inferred from a coordinate.

A consumer that cares about schema compatibility should assert `schemaVersion`, not a version range.

## Worked examples

| Event | Version |
| --- | --- |
| Pinned to Flix v0.75.1 | `0.75.1` |
| Fixtures regenerated, pin unchanged | `0.75.2` |
| Pin moves to Flix v0.75.2 | `0.75.3` |
| Pin moves to Flix v0.76.0 | `0.76.0` |
| Build from `main` between releases | `0.76.0-SNAPSHOT` |

The base version does not identify which upstream *patch* a build came from — `0.75.1` and `0.75.2`
may share a pin or not. Read `pin.json`, or the POM properties, or the marker file. That is the
trade for a version that orders cleanly.

## Bumping

`version` in `gradle.properties` is hand-maintained. On a pin bump, set `flixMajor.flixMinor` from
the new upstream tag and reset `revision` to `0` if the line changed, or increment it if it did not.
See [`PIN-BUMP.md`](PIN-BUMP.md).
