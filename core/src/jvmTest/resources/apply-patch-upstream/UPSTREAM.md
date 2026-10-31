# Codex apply_patch compatibility fixtures

Source: https://github.com/openai/codex/tree/23934d31b15b1127c83535f84854e5009f7cd51d/codex-rs/apply-patch/tests/fixtures/scenarios

Pinned commit: `23934d31b15b1127c83535f84854e5009f7cd51d` (2026-10-29 download).
Original license and attribution are included in LICENSE and NOTICE.
These fixture bytes are copied without newline conversion.

`PatchCompatibilityTest` replays each fixture through the local tool. The deliberate
exception is scenario 015: Codex CLI leaves its earlier Add in place after a later
missing Update fails; Mederi preflights the entire patch, leaving the input unchanged.
All other scenarios must match the upstream expected filesystem byte for byte.

PatchParser/PatchApplier are Kotlin adaptations of the pinned parser,
streaming_parser, seek_sequence, file_update and text_file implementations.
Environment ID preambles route Codex remote execution and are not accepted by this
local project-scoped tool. Canonical duplicate targets and symlink writes are
also rejected by Mederi's filesystem executor rather than following the CLI.
