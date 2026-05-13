# ScriptRender Governance Guide

This guide defines how to decide whether a rendering change is AC-only or requires ABI/runtime work.

## 1) Fast triage

A change is **AC-only** when all of the following are true:

- It fits existing ScriptRender kinds/fields and validation rules.
- It can be expressed via AC profile/entity content values.
- It does not require new render-state operations or runtime hooks.

If any condition fails, treat it as an **ABI extension** request.

## 2) Required workflows

- AC-only path: follow `SCRIPT_RENDER_AC_ONLY_CHANGE_CHECKLIST.md`.
- ABI extension path: open request with `SCRIPT_RENDER_ABI_EXTENSION_REQUEST_TEMPLATE.md`.

## 3) Cross-platform contract

Profile key resolution is shared and deterministic:

1. `:profile-key`
2. `:renderer-id`
3. kind fallback default

Canonical implementation: `cn.li.mcmod.entity.dsl/resolve-render-profile-key`.

## 4) Runtime safety rules

- Scripted-first dispatch must always preserve native fallback.
- Global/per-id disable must immediately force native path.
- Runtime errors should degrade gracefully with warning logs.

## 5) Verification minimum

For every ScriptRender change:

- Compile gates: `ac`, `mcmod`, `forge-1.20.1`, `fabric-1.20.1`
- Boundary gates: `verifyArchitectureBoundaries`, `verifyCurrentPlatforms`
- Hook/platform gates: `verifyForgeHookCoverage`, `verifyPlatformHookCoverage`, `verifyPlatformNoBusinessHookIds`

## 6) Performance gate ownership

For any new migrated renderer-id, submit benchmark evidence before removing/deprioritizing native path:

- frame-time delta in representative scenes
- allocation delta on render thread
- rollback switch proof (global/per-id)

Without benchmark evidence, keep migration in dark-launch/hybrid mode.
