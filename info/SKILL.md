---
description: ZoeVIP LSPosed module — structure, adapters, build/install. Use when working on ZoeVIP, afusekt-lsp, WebDAV sync, or adding a new adapted app.
---

# ZoeVIP

## Read first

1. `afusekt-lsp/info/MODULE.md` — module overview
2. `afusekt-lsp/info/adapters/INDEX.md` — adapted apps list
3. `afusekt-lsp/info/adapters/<id>.md` — per-app hooks and notes

## Quick facts

- App id: `com.zoevip.lsp`
- Project: `afusekt-lsp/`
- APK out: `afusekt-lsp/output/zoevip-lsp.apk`
- Icon source: `afusekt-lsp/branding/zoevip-icon.png`
- Xposed entry: `com.afusekt.lsp.MainHook`

## When adding an adapter

Update INDEX + new adapter md, `xposed_scope`, **`META-INF/xposed/scope.list`**, MainHook routing, and MainActivity list. Keep hooks isolated per app.
