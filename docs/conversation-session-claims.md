# Conversation and session actor claims

The OpenAI connector establishes a service identity. A human owner authorizes that connector to claim a session actor. The conversation groups those actors. Resource operations use the claimed actor's own identity and policy.

A claim belongs to an immutable provider conversation and session, scoped to the verified human owner, registered connector, product, and project or general context. Conversation titles and title slugs are display metadata. A rename or a reconnect with the same verified identifiers keeps the same claim. A different session under the same conversation gets its own actor.

The requested logical hierarchy runs from OpenAI through ChatGPT, product, context, conversation, and session. The first domain operation records the conversation grouping on a leased child under the existing connector actor. The physical DID hierarchy is a separate encoding decision.

## Claim admission

The application boundary verifies a live owner delegation that gives the exact authenticated connector claim authority in the named product and context. It verifies the conversation and session binding before supplying those identifiers, the proof expiry, and a candidate child DID selected by Axxium. The domain rechecks the connector's current place in the owner's actor tree, its liveness, and the child's authorized policy and lease bounds. The future admission adapter must recheck grant revocation against current state before each claim.

The same live claim returns the same actor even if its name or candidate DID in a retry differs. An expired proof, a dead actor, or a candidate owned by another claim fails closed. A retry does not extend a lease. Serialize claim decisions against the current identity ledger and commit the recorded decision and new actor together. A live adapter must prove that concurrent retries persist only one actor and recover from uncertain writes before enabling the command.

Claiming an actor starts with no repository permissions or allowed resource actions, retains the connector's explicit prohibitions, and creates no credential or resource grant. Subsequent mailbox, Drive, Git, SSH, and SFTP access requires the corresponding actor-specific authorization. A claim response can disclose the public actor identity and lease. Keep opaque provider identifiers inside claim state. Secret custody belongs to a separate credential operation.

## Provider integration

OpenAI documents `openai/session` as an anonymized conversation correlation ID. A production adapter also needs evidence for the individual session, owner delegation, and the product and context its claim names. Connector OAuth validates the applicable token and scope. Where the provider exposes only conversation correlation, qualify an owner-authorized Axxium binding before accepting session claims. Titles, tool arguments, and MCP transport session IDs are insufficient on their own. [OpenAI request metadata](https://developers.openai.com/plugins/reference#_meta-fields-the-client-provides) · [OpenAI OAuth requirements](https://developers.openai.com/plugins/build/auth)

The domain fixtures establish invariants independently of the provider. A staging pilot needs verifiable provider binding evidence, two sessions under one conversation, a denied unrelated owner, and no inherited resource access. Verify session-addressed delivery only after both actors have separately authorized mailboxes. The existing production Work round trip and the pinned Desktop wake remain evidence for those transport paths.
