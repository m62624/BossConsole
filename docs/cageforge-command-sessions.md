# Sandboxed command sessions

BOSS command sandboxing is an explicit opt-in launch of a root executable. Cageforge
owns that process and all its descendants. Starting `codex`, for example, places the
shells, Git commands and compilers it creates inside the same native boundary.
An MCP call is a transport operation, not a new sandbox boundary. Ordinary terminal
and plugin execution are outside this feature and retain their existing behavior.

## Policy and approval contract

A session selects a project directory, a TOML file, a named CLI profile, and an
executable with separate arguments. BOSS reads the policy once, adds a final child
profile containing that exact command and working directory, then asks Cageforge
Java 0.6.1 for its permission request. Approval identifies this immutable snapshot.
Edits to the TOML file affect the next preparation, never a running session. A plan
can launch once. A failed launch has no unsandboxed retry or fallback.

The final child profile `boss-command-session` is reserved. It enforces preflight
approval and captured stdin/stdout/stderr. It inherits the selected policy; native
Cageforge resolves all filesystem, environment, network and OS-specific rules.
The project directory is the resolution context even when the TOML file is elsewhere.
There is no automatic discovery or execution of repository-provided commands.

TOML is trusted configuration. The permissions shown for review may grant resources
outside the project. A configuration file is not itself a security ceiling. BOSS
must show the resolved permission request before issuing a grant. No MCP credentials
are added to ordinary command sessions. Agent connection configuration belongs only
to explicitly requested agent sessions.

## TOML inheritance

Use one project policy with shared profiles and named CLI profiles. Cageforge's
`inherits` performs the merge; BOSS does not concatenate independent policy files
or implement a second TOML merger. Parents are applied in inheritance order, shared
ancestors once, and children last. A platform overlay participates at each profile.

- Matching filesystem rules are replaced by canonical target identity, not appended
  indiscriminately. Different targets remain present.
- `workspace_roots` maps paths to booleans; `false` disables an inherited root.
- Command arguments replace the inherited argument list, including an empty list.
- Environment set/remove entries override the same case-insensitive variable name.
- Changing a policy mode can clear inherited mode-specific rules. An empty list is
  not a general instruction to remove inherited permissions.
- Cycles, unknown fields, duplicate canonical rules and unknown profiles fail.

Custom runtimes can require explicit readable paths. On macOS, custom executable
roots additionally need `runtime.executable_roots`; read access alone does not grant
executable mapping. Windows setup is explicit and may require elevation. Launch only
verifies existing setup; it never invokes UAC implicitly.

The session uses pipes. This does not promise a PTY, terminal emulation, resize
support, or compatibility with CLIs that require a controlling terminal.

## Verification work

The command session module separates immutable preparation and native launch from
Compose and MCP integration. Ordinary tests cover snapshot identity, argument
handling, bounds and approval replay. Native policy and security tests must exercise
the published binding and real backend on Linux, macOS and Windows. Linux enforcement
runs in a prepared QEMU guest with the consumer compiled on the host. GUI tests must
exercise opt-in, review, denial, failure, output and termination through Compose.

The existing `feat/cageforge-secure-plugin` branch supplies useful native provisioning
and QEMU patterns, but its protected plugin lifecycle is not this feature's launch
model. In particular, replacing a plugin worker cannot isolate commands launched by
an unrelated terminal plugin. No claim that the old branch works or fails on all
platforms follows from its presence in Git; its CI and native evidence need separate
inspection.
