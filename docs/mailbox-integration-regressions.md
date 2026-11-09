# Mailbox integration regression contract

## User story
As a user resuming delegated work across agents, I need unread messages, exact payloads and undelivered notifications to survive interruption so that a reconnect neither loses context nor invents completion.

This tests-only carrier starts at `eaf47420664513f55a53f2ed51216278b049808b` (riatzukiza/axxium#18). It does not edit the owning `feat/mailbox-mcp` branch. open-hax/axxium#47 remains the coordination anchor. Grokbot owns implementation repair.

## Observable cases
1. Acknowledge the second message before the first. The first remains readable. This is a per-message acknowledgment contract, not an implicit cumulative acknowledgment.
2. Send a JSON body with `a/id`, `b/id`, `x_y` and `x-y`, including nested objects. Reading through the MCP wire returns exactly those string keys and values. Envelope DTO naming must not rewrite user data.
3. Compute a notification plan without any network effect or delivery acknowledgment. `get_delivery` must not claim the notification was delivered. The current `dispatch` API has no external acknowledgment input; under this contract its notification state remains `pending` until an explicit delivery result is recorded. A repaired API may rename the planning operation, but the behavioral requirement remains.
4. An idempotent replay returns a nonempty receipt equal to the original `message/receipt`; comparing two absent `receipt` keys is not evidence.

These tests are expected to expose the current candidate's known defects, not qualify deployment. They deliberately do not prescribe a same-DID broadcast/competing-consumer policy; that needs an explicit application contract. They also do not prove disk atomicity, short-write recovery, a JVM load, HTTP mounting, a live webhook, or real ChatGPT delivery.

## Cross-repository boundaries
- Axxium owns actor/session/mailbox admission, operation commit and outbox recovery.
- Clio's store in riatzukiza/clio#7 appends **one event**; transfer is not a batch transaction. Its personal-fork merge is `ae4d51984793fd78ad09f362e206dc288494c9c6`, with reviewed source head `35ddb9ce816de521380b5c2c477dbe17571cb7d5`. Organization adoption is separate.
- Katamorph owns portable Message/Mailbox/Resource/Grant shapes. JSON message payload preservation does not create a second canonical EDN codec.
- OpenPlanner's archive/restore integration is a later phase; current personal #2/#3/#4 do not implement it. A graph projection must not advance before its source operation commits.
- Services owns isolated staging, issuer/audience/signing-key wiring and deployment evidence.

## Run and reporting
The new `axxium.domain.mailbox-integration-test` namespace matches the existing Shadow test regex. Run `npm test` and `npm run typecheck` on the exact candidate, retaining failures and warnings. Do not suppress a failing test, call this carrier complete, or merge it as a deployment approval. This sandbox has not executed the native ClojureScript suite; hosted results must be read before claiming execution.
