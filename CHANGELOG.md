# Changelog

## 0.1.0

First release.

- Added the **Rust Compiler Reader** tool window with collapsible Macro Expansion, MIR and Assembly views.
- Locate the current function, impl method, closure, type, trait, module or `macro_rules!` from the caret.
- Macro expansion via `cargo-expand` or `RUSTC_BOOTSTRAP=1 cargo rustc -Zunpretty=expanded`; optional rustfmt formatting.
- MIR extraction for free functions, impl methods and closures from `--emit=mir` output.
- Assembly extraction from `--emit=asm` output with v0/legacy symbol matching; optional `cargo-show-asm` backend.
- Cargo metadata driven package/target resolution with workspace support and remembered target selection.
- Background execution, cancellation, timeout, serial Cargo runs, artifact freshness checks and in-memory result cache.
- Project settings persisted in `.idea/rust-compiler-reader.xml`.
- Build output panel with clickable `path:line:column` links.
- Copy command, Open in Editor, Save, line numbers, folding and find bar.
