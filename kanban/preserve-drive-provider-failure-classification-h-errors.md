---
category: "kanban"
dependency: []
type: "task"
write-id: "1793660948208-0.zvxn1ys12sft34phi2"
title: "Preserve Drive provider failure classification"
priority: "P2"
status: "incoming"
uuid: "axxium-drive-oauth-errors"
created_at: "2026-11-02T23:07:28.611Z"
---

## Outcome
Preserve Google provider HTTP failure status and classify rejected refresh grants for reconnect.

## Destination
Google Drive resource, PR https://github.com/riatzukiza/axxium/pull/19 (standalone story; no existing Drive epic).

## Scope
Handle non-JSON error bodies and OAuth error codes without exposing provider payloads. Do not alter credentials or authorization.

## Acceptance criteria
- Non-JSON failures retain HTTP status.
- Refresh invalid_grant returns not-connected; other provider failures remain drive-unavailable.
- Successful response behavior remains unchanged.

## Verification
Offline provider-boundary and MCP tests, full test suite, typecheck and build.