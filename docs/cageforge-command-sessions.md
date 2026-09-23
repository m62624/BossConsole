# Sandboxed command sessions

BOSS command sandboxing is an explicit opt-in launch of a root executable. Cageforge
owns that process and all its descendants. Starting `codex`, for example, places the
shells, Git commands and compilers it creates inside the same native boundary.
An MCP call is a transport operation, not a new sandbox boundary. Ordinary terminal
and plugin execution are outside this feature and retain their existing behavior.

## Launch from BOSS

Open **Tools > Sandbox command sessions**. Enter the absolute project directory and
policy TOML path, choose a profile, then enter the executable and a JSON array of
arguments. For example, executable `node` with arguments `["script.js", "a b", ""]`
passes three distinct arguments, including the final empty one. BOSS does not parse
these fields as a shell command.

**Review and run** prepares the native permission request without starting a process.
The dialog shows the project, exact argv and Cageforge's resolved permissions. Deny,
approve once, or remember that exact review until BOSS closes. Changed commands or
policies still require a new approval. The manager also offers **Revoke remembered
approvals**, which invalidates pending approvals but does not stop already-running
sessions.

The manager retains bounded stdout/stderr tails, accepts lines on stdin, sends EOF,
and stops the root process together with its descendants. Closing the manager does
not stop sessions; use **Stop session and descendants**, or quit BOSS. Finished
output remains until removed. At most eight sessions are retained. Application
shutdown revokes consent and waits for native cleanup, including launches that were
in progress when shutdown started. There is no implicit Windows setup or elevation.

The CLI/MCP launch adapters and agent-scoped additional-permission endpoint are still
being integrated. The GUI launch path is not a claim that agent escalation is ready.

## Policy and approval contract

A session selects a project directory, a TOML file, a named CLI profile, and an
executable with separate arguments. BOSS reads the policy once, adds a final child
profile containing that exact command and working directory, then asks Cageforge
Java 0.7.1 for its permission request. Approval identifies this immutable snapshot.
Edits to the TOML file affect the next preparation, never a running session. A plan
can launch once. A failed launch has no unsandboxed retry or fallback.

The final child profile `boss-command-session` is reserved. It enforces preflight
approval for the initial launch and selects Cageforge's mode required for
on-demand escalation. It also captures stdin/stdout/stderr. It inherits the
selected policy; native Cageforge resolves all filesystem, environment, network
and OS-specific rules. MCP additional-permission requests are still integration
work, described below.
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

## Additional permission requests

Since 0.7.0, Cageforge supports explicit permission escalation. The integration must use
its `requestEscalation`, `approveEscalation` and `launchEscalated` APIs, not rewrite
the running process's policy. The native contract requires a new immutable sandbox;
relaunching a session must stop its previous process boundary first. It is not an
in-place permission change or a promise to preserve an agent's in-memory state.

The MCP request must identify the command, project/session, additional filesystem
or network capabilities, and a human-readable reason. The agent requests access;
it never supplies the approval decision. BOSS must show the exact command and
resolved native permissions in the GUI before launching anything with more access.
Ordinary MCP tool trust is not authorization for arbitrary sandbox capabilities.

The host consent queue implements these two scopes:

- **Allow once**: one execution of the reviewed command and policy. Replaying the
  authorization is rejected, including after a failed launch.
- **Allow until BOSS closes**: remember only the exact reviewed command, policy
  and capabilities in this application process. Changed requests still prompt.
  Nothing is persisted; restarting BOSS asks again. Revocation invalidates pending
  responses and unused authorizations as well as remembered decisions.

Denial, timeout, cancellation, queue overflow and application shutdown never grant
permission. A stale dialog cannot approve the next request. This consent mechanism
and its unit tests are implemented in the session module. Initial GUI launches use
this same queue. Native escalation has a separate API-level security test; its
agent MCP/GUI request loop remains integration work, not verified end-to-end functionality.

## Verification work

The command session module separates immutable preparation and native launch from
Compose and MCP integration. Ordinary tests cover snapshot identity, argument
handling, bounds, approval replay, cancellation during native acquisition and
application shutdown. Compose tests cover explicit submission, exact argv and the
request-specific arming of both approval buttons. Native policy and security tests exercise
the published binding and real backend on Linux, macOS and Windows. Linux enforcement
runs in a prepared QEMU guest with the consumer compiled on the host. The descendant
termination probe uses the application session service and its consent queue.
GUI coverage for the full agent escalation flow remains required before completion.

On Windows, each native test restores the explicitly installed `WindowsSetup`
before JUnit removes that test's temporary files, because Cageforge restores
journaled ACLs during uninstall. The Java probe grants only its classes and the
runtime files it opens; it does not recursively grant the entire JDK. The cwd
probe writes relative paths and the host verifies their contents in the selected
project; it does not use `toRealPath`, which enumerates ungranted Windows ancestor
directories.

The existing `feat/cageforge-secure-plugin` branch supplies useful native provisioning
and QEMU patterns, but its protected plugin lifecycle is not this feature's launch
model. In particular, replacing a plugin worker cannot isolate commands launched by
an unrelated terminal plugin. No claim that the old branch works or fails on all
platforms follows from its presence in Git; its CI and native evidence need separate
inspection.
