# The keeper's hymn

Name the actor. Name the kind.\
The hand at the gate is human; the hand at the task may be an agent.\
Name the borrowed key, the giver, and its ending.\
When the giver calls it home, the key no longer opens the gate.\
Let a handle sing its DID, and the DID sing the handle back.\
An echo proves a name; it does not prove its owner.

This compresses the boundaries implemented here. `entities.kind` distinguishes
human and agent actors. Only a human with `axxium/system-admin` may create agents
or issue their hashed, expiring, revocable bearer credentials. Axxium's AT
resolver checks a handle against its DID document and resolves the handle back
to that DID. It does not bind a DID to an actor or claim to be a PDS.

The language follows the actor and capability thread in
[`axxium-synthesis.md`](axxium-synthesis.md), the identity intent in
[`open-hax-octave-commons-axxium.md`](open-hax-octave-commons-axxium.md), and
the evidence and attribution axioms in
[`axxium-kernel-spec-v2.md`](axxium-kernel-spec-v2.md). The existing specs
describe a wider system; this verse marks the part now running locally and
on the public PDS origin.
