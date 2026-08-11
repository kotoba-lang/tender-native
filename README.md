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
- `:string` parameters are canonical UTF-8 copied into the bounded native
  arena before guest entry. Selected `:string` results are independently
  validated and copied from that arena into the structured report before the
  process exits; pair handles never escape as host strings.
- Scalar `:record` parameters and selected results use the published aggregate
  ABI's declaration-order pair chain. The host accepts exactly the declared
  keyword keys, supports only unique `:i64`/`:bool` fields (1–128), and the
  loader requires the exact chain length and zero terminator before copying
  field words into evidence. Pair handles never escape to callers.
- `:option-i64` and `:result-i64` use the same canonical tagged vectors as the
  reference and restricted-ESM hosts (`[false]`/`[true value]` and
  `[ok? value]`). The loader materializes and inspects the established
  `pair(tag,payload)` representation; tags must be boolean words and option
  none must carry payload zero.
- Scalar variants keep the canonical KIR host value
  `[type case-keyword payload]`. Qualified descriptors with 1--32 unique cases
  and only `:i64`/`:bool` payloads lower to a bounded token containing case
  count, declaration ordinal, payload kind, and word. Results are copied from
  the pair arena as ordinal/word evidence, then reconstructed only after the
  sealed descriptor validates the ordinal and boolean word.

- The session boundary. `prepare` verifies a signed artifact once and stages
  the measured loader and its code; `invoke` runs one export against that
  staging; `close!` removes it. `execute` is those three composed, and stays
  the right call for a host that runs one entry once.

  What a session amortizes is time-invariant: the Ed25519 signature, the
  `verify-artifact!` structural check, the loader measurement, and the
  target-profile agreement. What it does not amortize is everything that can
  change between two calls -- expiry, not-before, revoked signers, revoked
  artifacts, and capability admission against that call's policy all run per
  `invoke`. Measured on an M4, verification of a 19k-word module costs
  1.7--3.6 s while the loader process costs about 2 ms over a bare spawn, so
  the split is the difference between native being an execution path and
  native being a conformance target.

- Locating the reviewed loader source. `measure-runtime` takes
  `:loader-source-dir`, falls back to `KOTOBA_LOADER_SOURCE_DIR`, and only
  then to `tools/` under the working directory. Naming a directory cannot
  substitute a different loader: the source digest must still be the reviewed
  one for the target profile.

## Does not own

- compile
- decide grants
- require Rust in the core path
- expose nested aggregate, vector, parametric option/result, non-scalar
  variant, or document
  handles as host
  values. Those boundaries remain explicitly rejected until each has a bounded
  copy/validation protocol.

## Depends on

- `kotoba-lang/kotoba-kir`
- `kotoba-lang/artifact`
- `kotoba-lang/kotoba-native`

## Test

```bash
clojure -M:test
```
