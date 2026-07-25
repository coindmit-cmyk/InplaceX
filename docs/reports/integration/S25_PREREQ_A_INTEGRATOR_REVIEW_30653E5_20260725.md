# S25 prerequisite A integrator review: 30653e5

Decision: REJECT AND PRESERVE

Candidate `30653e5a5eba6d81193435cab77769f0faa984c9` is preserved on its worker
branch, but must not be integrated into `develop`.

## Blocking finding

`JwtAccessTokenService.issue` is declared `internal` and accepts a raw canonical
subject. Kotlin emits it as a public JVM method together with a public default
argument bridge:

```text
public final java.lang.String issue$InplaceX_backend(
  java.lang.String,
  java.time.Instant,
  java.time.Instant
);

public static java.lang.String issue$InplaceX_backend$default(
  JwtAccessTokenService,
  java.lang.String,
  java.time.Instant,
  java.time.Instant,
  int,
  java.lang.Object
);
```

An in-process caller with the service instance can therefore mint a signed
token for a known active player id and then obtain a valid
`AuthenticatedPrincipal`. This bypasses the identity/bootstrap authority and
violates the task requirement that no public, internal or synthetic JVM
factory can mint authority from a raw identity.

The candidate's hostile tests enumerate constructors, but they do not enumerate
or invoke declared methods on `JwtAccessTokenService`. The worker report's
no-residual-blocker conclusion is therefore not sufficient.

## Verified salvageable intent

- Principal and participant capability implementations take only their owner,
  keep bindings in private registries and reject reflection-created instances.
- Constructor tests include synthetic constructors and do not filter
  `isSynthetic`.
- Token verification and membership checks cover the intended fail-closed
  validation cases.

These points are evidence for a clean retry, not authorization to cherry-pick
the candidate.

## Required retry shape

- Keep the public authentication service read-only: its authority-producing
  public surface may authenticate a supplied token, but may not issue one from
  `String`, `UUID` or another raw identity value.
- Put token issuance behind a JVM-private identity-owned boundary, or require
  an opaque identity capability that cannot be constructed from raw values.
- Enumerate every declared constructor and method, including synthetic and
  mangled Kotlin `internal` members, with `javap -p -s` and hostile reflection.
- Reject any public, protected, package-visible, internal or synthetic method
  that accepts a raw identity and returns a token, principal, verified claims,
  membership or session participant capability.
- Preserve the registry-backed constructor/proxy protections already proven by
  this candidate.

## Review evidence

- Candidate: `30653e5a5eba6d81193435cab77769f0faa984c9`
- Rejected worker branch:
  `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership/retry-s25-prerequisite-a-unforgeable-jvm-princip-retry-20260725T232612Z`
- Independent command:
  `/home/main/.local/jdk21/bin/javap -p -s -classpath InplaceX-backend/build/classes/kotlin/main com.mirkori.inplacex.backend.auth.JwtAccessTokenService`

