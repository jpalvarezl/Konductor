# Distribution

Konductor ships as a self-contained [`jpackage`](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html)
bundle per OS, built from the shaded jar. The Maven `dist` profile drives it:

```bash
mvn -Pdist package
```

The profile (a single `maven-antrun-plugin` target — pure JVM, no shell, so it behaves identically on every
OS) builds the shaded jar, stages only that jar in `target/dist-input/`, then runs `jpackage` in classpath
mode into `target/dist/`.

## Per-OS artifact types

jpackage cannot cross-compile, so each OS produces its own artifact and must be built on that OS:

| OS      | `jpackage.type` | Output                   | Notes                                       |
| ------- | --------------- | ------------------------ | ------------------------------------------- |
| Windows | `app-image`     | `target/dist/Konductor/` | zipped for release; needs `--win-console`   |
| Linux   | `deb`           | `target/dist/*.deb`      | runner needs `fakeroot`                      |
| macOS   | `dmg`           | `target/dist/*.dmg`      | **unsigned** — Gatekeeper will warn         |

`jpackage.type` and the Windows-only `jpackage.win.console` flag are Maven properties. The defaults suit a
local Windows build (`app-image` + `--win-console`); Linux and macOS override them:

```bash
mvn -Pdist package -Djpackage.type=deb "-Djpackage.win.console="   # Linux
mvn -Pdist package -Djpackage.type=dmg "-Djpackage.win.console="   # macOS
```

## Releases (GitHub Actions)

`.github/workflows/release.yml` cuts a release on a version tag:

```bash
# First add the release section and included PRs to CHANGELOG.md.
git tag v0.1.1 && git push origin v0.1.1
```

It fans out across `ubuntu-latest`, `macos-latest`, and `windows-latest`, sets up **Temurin JDK 25**,
derives the version from the tag, runs the per-OS build above, and uploads workflow artifacts. Only after every
package succeeds does a final job create the GitHub Release, populate its body from the matching `CHANGELOG.md`
section, and attach the `.deb` / `.dmg` / `.zip`. It can also be triggered via `workflow_dispatch` for artifact-only
builds.

macOS `jpackage` rejects an app version whose first component is zero. For pre-1.0 tags, CI maps only the internal
bundle version (`0.1.1` → `1.1.1`); the Git tag, changelog, release title, and artifact name remain `0.1.1`. A signed
macOS `.dmg` would need an Apple Developer account and notarization — out of scope for now.

## Notes / gotchas

- **Build JDK:** CI uses JDK 25 (`actions/setup-java`), matching the project's `jvmTarget` /
  `maven.compiler.release` of 25 (Kotlin 2.4.0 supports targeting JVM 25).
- **`--win-console`** is required on Windows because Konductor is a Lanterna TUI; without it the launcher has
  no console.
- **Release notes:** update `CHANGELOG.md` before tagging. The workflow fails instead of publishing a release when
  the matching version section is absent.
- **Re-running locally:** jpackage marks its output read-only, and Ant's `<delete>` will not force-remove a
  stale `target/dist`. Use `mvn clean -Pdist package` (or delete `target/dist` manually) when rebuilding.

## Future work — size / footprint reduction (deferred)

Deliberately deferred: as a hackathon project we favour velocity over space efficiency, so we currently
ship the large self-contained bundle as-is. Recorded here so we don't lose sight of it.

The app-image is ~215 MB (≈150 MB bundled JVM + ~64 MB shaded jar). The jar bloat is almost entirely the
`azure-ai-agents` transitive tail — **not** Konductor code:

- **`openai-java` (~24 MB, ~14.7k classes)** — transitive via `azure-ai-agents`; a real runtime dependency,
  so only `minimizeJar`/ProGuard can strip the unused generated model classes (risky with reflection — test).
- **`azure-core-http-netty` → Netty + Reactor + `netty-tcnative`** native SSL for 5 platforms (~12 MB).
  Swappable for `azure-core-http-jdk-httpclient` (JDK `java.net.http`, no native libs) — biggest low-risk win.
- **JNA native dispatch libs** for ~20 platforms — trim to shipped targets only.
- **Runtime:** modularise + `jlink --add-modules` to shrink the ~150 MB bundled JVM toward ~40–70 MB, or
  pursue a GraalVM native image (blocked today by JNA/Lanterna/reflection complexity).

Rough target if pursued: jar ~50 MB (Netty swap), self-contained bundle ~90–110 MB.
