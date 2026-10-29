# jenkins-atom-tap-report

TAP / CTest report — port of **tap / xunit 的 TAP 部分** (约 8,000（tap）) as an atom.

JUnit XML has its own atom; prove, bats, node-tap and CTest write TAP or CTest XML, which used to be
invisible in a build.

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh
```
