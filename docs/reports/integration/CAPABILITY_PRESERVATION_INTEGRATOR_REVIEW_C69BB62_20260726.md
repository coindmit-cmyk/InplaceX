# Capability preservation integrator review: c69bb62

## Verdict

`REJECT / needs_worker_fix`.

Candidate `c69bb62d043269aedf1f795c02bfe29698450948` is preserved as salvage evidence and must not be integrated.

## Blocking finding

`compare_entries` builds one global set of symbols present after the change and treats any matching token in another changed file as proof of a move. It does not prove that the token was newly introduced in the destination relative to the base ref.

The independently reproduced hostile fixture has two files that already contain a function with the same name. The candidate removes the function from one file and changes only another function body in the second file. Expected result is `capability_removed:function:duplicate`; actual result is `status=preserved`, `exit_decision=allow`, with a fabricated move to the unrelated file.

This is fail-open capability loss and violates the conservative preservation contract.

## Positive evidence retained

- The previous leaked `new_path` state bug is fixed.
- Delete/modify/rename/move permutations are deterministic.
- The isolated worker suites passed on Linux.
- Invalid refs fail closed.
- The comparison remained read-only and the candidate scope is valid.

## Mandatory retry contract

- A move is proven only when the capability is new in the destination relative to that destination's base state, or when an explicit valid Git rename/copy mapping proves it.
- A same-named capability that already existed in an unrelated destination must never mask removal from the source.
- Add hostile duplicate-name fixtures for both functions and classes across multiple changed files.
- Assert `review_required`/non-zero for the hostile removal case and deterministic JSON for every changed-entry ordering.
- Preserve the read-only behavior, invalid-ref fail-closed result, deterministic output, and existing permutation coverage.
