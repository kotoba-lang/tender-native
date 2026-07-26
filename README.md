# tender-native

Native tender — loads and runs kotoba-native artifacts under a capability gate.

**Tier**: `T3`  **Role**: `runtime`

Split out of the overloaded core repos by ADR-2607266000 so that each
responsibility has exactly one owner and the dependency direction is
checkable from outside.

## Owns

- `kototama.native.executor (native artifact execution host)`

## Does not own

- compile
- decide grants
- require Rust in the core path

## Depends on

- `kotoba-lang/kotoba-kir`
- `kotoba-lang/artifact`
- `kotoba-lang/kotoba-native`

## Test

```bash
clojure -M:test
```
