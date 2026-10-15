# Scenario fixtures

Signature tests stay off until a directory exists at `src/test/resources/jfr-fixtures/scenarios/<id>/`. `SignatureTest` uses `@EnabledIf` on that directory. The golden `auth-cache` chunks remain the sanity recording: promotion pressure is expected there, and the other signatures are the negative case once a scenario fixture is added.

On a kind cluster with Jafra installed, record 5–10 minutes at `jafra.io/profile=medium` and copy 1–3 chunks under 5 MB into the scenario directory with an `expected.yaml` whose `id` matches `signatures.yaml`. Do not commit a fixture that is only a renamed golden chunk.
