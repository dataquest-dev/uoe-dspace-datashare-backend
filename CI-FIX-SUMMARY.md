# CI Fix Summary — PR #1 (`uoe/fix-github-actions`)

## Problem

GitHub Actions builds were failing due to deprecated action versions.

## Root Cause

The `codescan.yml` workflow used `github/codeql-action@v2` which is deprecated.
The original PR branch was also based on `master` instead of the target branch
`datashare-UoEMainLibrary-dspace-8_x`, causing merge conflicts — the target branch
already had `build.yml` and `docker.yml` upgraded to v4 actions and restructured
with reusable workflows.

## Fix

1. **Rebased** the PR branch onto `datashare-UoEMainLibrary-dspace-8_x` (eliminating conflicts)
2. **Upgraded** `codescan.yml`: `github/codeql-action/*` v2 → v3

All other workflow files (`build.yml`, `docker.yml`, `reusable-docker-build.yml`)
already had up-to-date action versions on the target branch.

## Note

Node.js 20 deprecation warnings exist (deadline June 2026) — non-blocking, no action needed now.
