# flutter_reactive_ble Fork — Execution Plan (branch cleanup + iOS fix landing)

**Repo:** `Feo-Elektronik-GmbH/flutter_reactive_ble` (`origin`)
**Consumer:** `truma` app (ticket #44134) — pins `flutter_reactive_ble` at git `ref: v5.4.2`
**Status:** IN PROGRESS — Phase 0 ✅ and Phase 1 ✅ done (2026-07-03). Paused at the Phase 2 manual test gate. Phases 3–7 pending.
**Last updated:** 2026-07-03

> **Execution log**
> - **Phase 0 ✅** `ticket/44134` pushed to `origin/ticket/44134` (tracking set). Verified `5cbb2c8` now on origin — single-copy risk removed.
> - **Phase 1 ✅** Cherry-picked `890c1e0` onto `ticket/44134` → new commit **`aa79446`** (with `-x` provenance). Applied cleanly via 3-way merge, **no manual conflict**. Our fork's `print("resolve qualifiedCharacteristic")` debug line was preserved; only difference from upstream context was that print (NOT `5cbb2c8` — our iOS fix does not touch `Central.swift`, only `CharacteristicInstance.swift`). `aa79446` is **local-only by request** (not pushed) until the test gate passes; its source `890c1e0` is preserved on `upstream/master`, so risk is minimal.
> - Branch head now: `aa79446` (guard) → `5cbb2c8` (iOS fix) → `1843c22` (package tip).
> - **Approved add-on:** delete stray tags `list` and `push` (mistyped-command artifacts) during Phase 5.
> - **NEXT:** Phase 2 — manual hardware test (see below). Do not proceed to Phase 3 until it passes.

> Companion analyses (app repo, may be untracked on branch ticket/44133 — see §7 caution):
> `app/docs/features/ticket/44134/flutter_reactive_ble_upstream_merge_analysis.md`
> `app/docs/features/ticket/44134/flutter_reactive_ble_branch_audit.md`

---

## 1. Goal

Two independent workstreams, both approved:
- **A — Land the iOS fix on production.** The iOS read/notify hang fix (`5cbb2c8`) lives only on local `ticket/44134`. Add the upstream index-out-of-range crash guard (`890c1e0`) as a required companion, test, merge to `package`, tag, repin the app.
- **B — Clean up the fork's branches.** Keep 3, delete the rest (one is archived to a tag first).

**Do NOT** rebase `package` onto upstream or wholesale-merge upstream — `package` is a 239-commit product fork and upstream deleted its `package` branch. Only the surgical `890c1e0` cherry-pick is wanted from the 2026 upstream gap. (SPM `7ba36e5` and protobuf-6 `b4bc2d3` are explicitly **deferred**.)

---

## 2. Verified current state (facts)

| Ref | Meaning | Key SHA / note |
|---|---|---|
| `origin/package` | production line | tip `1843c22`; pubspecs `v5.4.3`; carries tags `v5.4.2` (app pins) + `v5.4.3` |
| `ticket/44134` | **current working/testing branch** | `= package + 5cbb2c8` (iOS fix). **LOCAL ONLY — not on origin.** |
| `5cbb2c8` | iOS fix: symmetric numeric `instanceId` + real peripheral id | restores contract broken by fork commit `aca4d7d` |
| `890c1e0` | upstream crash guard in `Central.swift resolve(characteristic:)` | on `upstream/master`; **required companion** to `5cbb2c8` (which re-activates the trapping `[Int(...) ?? 0]` subscript) |
| `upstream/master` | live PhilipsHue upstream (v5.5.0, May 2026) | the real, current upstream reference (remote already fetched) |
| `PhilipsHue-package` | old v5.4.1 state | **already 100% inside `package`** (merged via `24d1b59`); nothing to merge |

**Branch merged into `package` after testing = `ticket/44134`** (NOT `PhilipsHue-package`).

---

## 3. Final branch decisions

**KEEP**
- `package` — production (and the branch the app tracks).
- `ticket/44134` — current work; retire only after #44134 closes and its fix is on `package`.
- `master` — kept per prior decision (intended as an upstream baseline). *Note: `master` is a 2023 branch with non-upstream commits (ben91187/lukaswoelfle/etc.), 43 behind upstream — it is not a pristine baseline; the live `upstream/master` remote is the real current reference. If you later decide to drop `master`, repoint origin's default to `package` first (it is currently the default).*

**DELETE** (all fully contained in `package`, superseded, redundant, or throwaway)
- `package_connection_without_gatt_server` — **archive to a tag first** (unique 2024 spike, not preserved elsewhere).
- `PhilipsHue-master` — stale 2025 upstream snapshot; superseded by the live `upstream/master`.
- `PhilipsHue-package` — old v5.4.1, already merged into `package`; misleadingly named.
- `package_added_connection_state` — feature branch fully merged.
- `package_test` — only pubspec/lock dependency experiments, no source.
- `package_feo_test` — 2023 iOS-only "ServiceChanged" prototype, superseded by `didModifyServices`.
- `add-connected-devices` — byte-identical mirror of `upstream/add-connected-devices` (commits persist in the `upstream` remote).
- stray `refs/heads/HEAD` — accidental branch literally named `HEAD`.

**End state:** `master`, `package`, `ticket/44134` (until #44134 closes) + tags `v5.4.2`/`v5.4.3`/(new)`v5.4.4` + `archive/inet-box-no-gatt-auto-connect`.

---

## 4. Execution steps (in order)

All commands run in `/Users/maximiliangerhard/projects/truma/flutter_reactive_ble`.

### Phase 0 — Backup the only-local fix ✅ DONE (2026-07-03) *non-destructive*
```
git push -u origin ticket/44134       # done → origin/ticket/44134 created
git branch -r --contains 5cbb2c8      # verified: origin/ticket/44134
```

### Phase 1 — Add the upstream crash guard to the ticket branch ✅ DONE (2026-07-03)
```
git checkout ticket/44134             # already on it
git cherry-pick -x 890c1e0            # → aa79446, auto-merged clean, NO conflict
git log --oneline -3                  # aa79446 (guard), 5cbb2c8 (fix), 1843c22 (tip)
```
Outcome: applied via 3-way merge with no manual resolution. Our fork's
`print("resolve qualifiedCharacteristic")` debug line preserved; that print (not our
`5cbb2c8`) was the only context difference vs upstream. `aa79446` kept **local-only**
(not pushed) by request until the test gate passes.

### Phase 2 — TEST (manual gate) 🚦
Test on `ticket/44134` (app builds against this fork via its local-path override):
- iOS **read + notify + write** against the iNet Box's dense/overlapping-UUID service tree (the `resolve` path).
- Android `discoverServices` vs iOS `discoverAllServices`.
- Peripheral path: `startGattServer`/`startAdvertising`/`writeLocalCharacteristic` round-trip via `getCentralDataStream`.
**Do not proceed past here until tests pass.**

### Phase 3 — Land on production + tag (only after tests pass)
```
git checkout package
git merge --ff-only ticket/44134
git merge-base --is-ancestor 5cbb2c8 package && echo "fix on package OK"
git push origin package
git tag v5.4.4 package                # tag ONLY after test sign-off
git push origin v5.4.4
```
*(Optional: bump the three `packages/*/pubspec.yaml` `version:` fields 5.4.3 → 5.4.4 in a commit before tagging, for cleanliness.)*

### Phase 4 — Repin the app (separate, coordinated — NOT this repo)
On the **app's** `ticket/44134` branch (coordinate with whoever owns the app repo):
- `pubspec.yaml`: `flutter_reactive_ble` `ref: v5.4.2` → `ref: v5.4.4`.
- Remove the three local `dependency_overrides` (`flutter_reactive_ble`, `reactive_ble_mobile`, `reactive_ble_platform_interface`).
- `flutter pub get`; verify `pubspec.lock` resolves the git ref v5.4.4 (not a `path:` source).

### Phase 5 — Branch cleanup (destructive — one at a time, confirm each)
Remote deletions are recoverable only via reflog/re-push within GitHub's retention window.
```
# archive the unique 2024 spike BEFORE deleting it:
git tag archive/inet-box-no-gatt-auto-connect origin/package_connection_without_gatt_server
git push origin archive/inet-box-no-gatt-auto-connect

git push origin --delete package_connection_without_gatt_server
git push origin --delete PhilipsHue-master
git push origin --delete PhilipsHue-package
git push origin --delete package_added_connection_state
git push origin --delete package_test
git push origin --delete package_feo_test
git push origin --delete add-connected-devices        # commits persist in upstream/add-connected-devices

# stray junk tags (mistyped `git tag list` / `git tag push` artifacts) — APPROVED for deletion:
git tag -d list push
git push origin --delete list push
```
**Keep `master`.**

### Phase 6 — Remove the stray `HEAD` branch (careful)
```
git push origin :refs/heads/HEAD      # deletes the branch literally named HEAD, not the symbolic HEAD
git ls-remote --heads origin          # verify HEAD gone; master/package/ticket/44134 remain
```

### Phase 7 — Retire `ticket/44134` (last)
Only after Phase 3 confirms `5cbb2c8` is on `origin/package` AND ticket #44134 is closed:
```
git push origin --delete ticket/44134
git branch -D ticket/44134
```

---

## 5. Rollback / safety notes
- Nothing before Phase 3 is destructive. The only single-copy artifact is `5cbb2c8` — Phase 0 removes that risk.
- `git merge --ff-only` (Phase 3) refuses if `ticket/44134` is not a clean fast-forward of `package` — a safety check, not an error to force past.
- Remote branch deletes: confirm each; never batch. Recoverable via `git reflog` / re-push while within retention.
- **Do not touch tags** `v5.4.2` / `v5.4.3` — the app pins `v5.4.2`.

## 6. Explicitly deferred / not doing
- SPM migration (upstream `7ba36e5`) — re-implement later, timed to the Flutter 3.44 upgrade; never cherry-pick.
- protobuf 3 → 6 (upstream `b4bc2d3`) — only if a concrete need appears and no app dep caps protobuf `<6`.
- Rebasing `package` onto upstream / abandoning the fork — rejected (239-commit product fork; upstream has no package branch).

## 7. Caution — companion docs location
The two analysis docs (see header) were written into the **app** repo under `docs/features/ticket/44134/` while it was checked out on `ticket/44133`. If an agent runs `git add -A` there, they could be committed to the wrong branch. Move them onto the app's `ticket/44134` branch (or copy into this fork's `docs/`) if you want them version-controlled with the right ticket.
