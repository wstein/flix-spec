# Executable consumer example

This is an integration demonstration, **not an independent Flix parser**. It
renames `Root` in the reference's raw projections, then maps `ExampleRoot` back.
It declares all four capabilities and exercises all four comparison lanes.

With Java 21, Bash and jq installed, and the published data jar extracted:

```sh
bash identity-adapter.sh /path/to/spec output projection-map.json
java -jar /path/to/flix-spec-runner.jar --spec-root /path/to/spec \
  --source-root /path/to/spec --actual output --map projection-map.json \
  --report report.json
```

For a real adapter, replace the transformation with your parser, preserve the
fixture source paths, emit projection schema version 2, and write a map of your
own vocabulary. Declare only capabilities you implement. Do not copy the
reference's expected output as an implementation or generate accepted differences
without reviewing them. `tools/runner/acceptance.sh` executes this example outside
the checkout using locally published artifacts.
