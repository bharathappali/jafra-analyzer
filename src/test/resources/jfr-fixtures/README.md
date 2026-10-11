# Golden JFR fixtures

Curated `.jfr` / `.meta` pairs under `chunks/` (~2.1 MiB, 15 chunk ids) copied from
`do_not_commit/chunks/` for CI. Workload identity:

- `namespace`: `default`
- `pod`: `auth-cache-84cd9fbc49-fttcq`
- `container`: `auth-cache`

Regenerate with:

```bash
./jafra-analyzer/scripts/extract-jfr-golden.sh /path/to/do_not_commit/chunks
```

Optional full corpus: copy `do_not_commit/chunks` to `jafra-analyzer/test-data/chunks/`
(gitignored) for local experiments.
