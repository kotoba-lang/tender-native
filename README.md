# tender-native

Native tender — loads and runs kotoba-native artifacts under a capability gate.

**Tier**: `T3`  **Role**: `runtime`

Split out of the overloaded core repos by ADR-2607266000 so that each
responsibility has exactly one owner and the dependency direction is
checkable from outside.

## Owns

- `kototama.native.executor (native artifact execution host)`
- The selected export's typed host boundary. `:bool` parameters accept host
  booleans and lower to native 0/1 words; `:bool` results return host booleans.
  `:i64` remains distinct and accepts integers only.

## Does not own

- compile
- decide grants
- require Rust in the core path
- pretend arena-owned native string handles are host strings. The current
  process-per-call loader rejects string parameters/results explicitly until it
  can copy their bytes before process exit.

## Depends on

- `kotoba-lang/kotoba-kir`
- `kotoba-lang/artifact`
- `kotoba-lang/kotoba-native`

## Test

```bash
clojure -M:test
```
