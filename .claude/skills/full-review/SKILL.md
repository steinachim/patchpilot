---
name: full-review
description: Full review of the Patch Pilot app and its documentation (code, tests, build, CI, docs, catalogs, store texts), ranked by severity with mitigation options. Use when asked for a full, whole-app or senior-level review or audit. For a single diff use /code-review instead.
---

# Full review

## 0. Before reading

- Find the previous full review and what came of it, so fixed findings are not raised again. Line numbers in it are stale; re-check them.
- Standing exclusions, decided and not to be raised:
  - Undo for destructive edits.
  - Cinzel Bold legibility in the Steampunk theme.
  - A Cancel control for scans: the back arrow is one.
  - Authentication between app and instrument, and of the user.
  - Hardcoded English in `core/`, `devices/` and the debug menu. Localisation is a `devices/`-layer project, not a cleanup.

## 1. Coverage ledger

- Build it from `git ls-files`: main Kotlin, tests, Gradle and CI files, manifest and resources, `devices/` catalogs and schema, every Markdown file, fastlane texts.
- Mark each file or area read, partly read (which lines) or not read.
- Report every unread or partly read item as an open risk. Never write "read in full" for an area the ledger does not back.

## 2. Passes

Run each pass on its own. Check every finding in the code before it goes into the report.

### A. Per file

- Correctness: bounds, nullability, off-by-one, error paths.
- Coroutines: `CancellationException` rethrown before any other catch; `NonCancellable` where a sequence must not split; blocking I/O off the main thread; every scope has an owner.
- Error model: the `InstrumentException` subtype fits the condition. `NotSupported` means "never on this device", not "not in this state".
- Dead code, production API only tests use, unused imports and parameters.
- Comments and KDoc against the code beside them.

### B. User flows

Trace each one through ViewModel, coordinator, listings, screens and navigation. For each, note what state is published, what the screen shows, and whether any state is a dead end.

- Rotation (Activity recreation) and process death during an edit, a listing, a device report, the regression run, a destination pick, an open dialog.
- Back, Home and screen lock during each long operation.
- USB unplug in every `ConnectionState`; replug of the same device and of another one.
- The resume rebuild (`forceReconnect`) and the pause cancellation.
- Listing: cache hit, cache miss, a listing with failed slots, refresh, an edit and the reload after it.
- Permission denied, the advisory gate, the unknown-device gate, demo mode, two instruments attached.

### C. Cross-family matrix

One table: rows are concerns, columns are Nord, Pro-800, Motif XS, Axe-Fx III, Subsequent 37 and demo mode. A difference with no protocol reason behind it is a finding.

- Occupied, empty, refused and wrong-slot errors, and their wording.
- Name rules (`nameProblem`, reserved names) and cleaning of names the instrument supplies.
- Locking, and what runs outside the app's operation mutex.
- `NonCancellable` sections and rollback.
- Read-back verification.
- Firmware and model advisories; notices.
- Reporter shape and report identity.
- File layout: Instrument, Editor, Reporter, SysEx, Family.
- Fakes, fixtures and test factories.

### D. Docs against code

- Every Markdown file, KDoc, `strings.xml` comments, the schema, the fastlane texts, test KDoc.
- Every symbol a doc names exists: grep each backticked identifier.
- Counts (families, models, presets) and versions agree with the code and the catalogs.
- No history narratives. No private references:
  - `git grep -niE "reverse.?engineer|reference (implementation|cli)|§[0-9]|memory note" -- ':!.claude'`
  - Every `github.com/` URL points at a public repository, and no file names a private project or its tools.
- `docs/HARDWARE_TEST_PLAN.md`: results that contradict PROTOCOLS or code comments, and open items recorded in rows marked as passed.

### E. Build, CI and tests

- Gradle: tasks writing into `src/`, stale version comments, dependency hygiene, reproducibility notes.
- Workflows: `${{ }}` interpolated into `run:` scripts, permissions, triggers, whether a release is gated on tests and lint.
- Run `./gradlew spotlessCheck --rerun-tasks lintRelease` rather than assuming CI ran them; CI does not build feature branches.
- Tests: real-time waits, `runBlocking` inside `runTest`, duplicated helpers and fakes, test names that claim more than the test checks, risky paths from pass B with no test.

## 3. Second look

For a full review, run passes B–E again with independent read-only reviewers, one per pass, each given this checklist and the draft report. Verify every finding they return before adding it.

## 4. Report

- Severity: **High**, a user-visible malfunction on a plausible path. **Medium**, a real defect with limited reach, or debt that makes the next change risky. **Low**, robustness or consistency. **Nit**, hygiene.
- The coverage ledger first.
- Tables with ID, file:line, finding, and mitigation options with the recommended one first. Mark anything not verified in code or on hardware.
- A proposed batch order last. Every batch after the first needs its own go-ahead.

## 5. After each fix batch

- Review the batch's own diff with passes A and D.
- Grep docs, KDoc and comments for every identifier the batch removed or renamed.
- Re-read the comments and imports of every file the batch touched.
- `./gradlew spotlessCheck --rerun-tasks testDebugUnitTest`.
