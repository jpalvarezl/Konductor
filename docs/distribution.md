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
git tag v0.2.0 && git push origin v0.2.0
```

It fans out across `ubuntu-latest`, `macos-latest`, and `windows-latest`, sets up **Temurin JDK 25**,
derives the version from the tag, runs the per-OS build above with tests skipped, and uploads workflow artifacts.
Tests run in the required CI workflow with the pinned Azure SDK test proxy before release preparation merges. Only
after every package succeeds does a final job create the GitHub Release, populate its body from the matching
`CHANGELOG.md` section, and attach the `.deb` / `.dmg` / `.zip`. It can also be triggered via `workflow_dispatch`
for artifact-only builds.

macOS `jpackage` rejects an app version whose first component is zero. For pre-1.0 tags, CI maps only the internal
bundle version (`0.2.0` → `1.2.0`); the Git tag, changelog, release title, and artifact name remain `0.2.0`. A signed
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
ship the large self-contained bundle as-is. GitHub issue
[#52](https://github.com/jpalvarezl/Konductor/issues/52) owns discussion and prioritization; the measurements and
technical constraints remain here with the distribution contract.

The last measured app-image was ~215 MB (≈150 MB bundled JVM + a ~64 MB shaded jar). After upgrading to
`azure-ai-agents` 2.3.0 / openai-java 4.45.0, the packaged shaded jar is ~87 MB; a new app-image was not built for this
SDK-only change. The jar bloat is almost entirely the `azure-ai-agents` transitive tail — **not** Konductor code:

- **`openai-java` (~53 MB, ~25.9k classes)** — transitive via `azure-ai-agents`; a real runtime dependency,
  so only `minimizeJar`/ProGuard can strip the unused generated model classes (risky with reflection — test).
- **`azure-core-http-netty` → Netty + Reactor + `netty-tcnative`** native SSL for 5 platforms (~12 MB).
  Swappable for `azure-core-http-jdk-httpclient` (JDK `java.net.http`, no native libs) — biggest low-risk win.
- **JNA native dispatch libs** for ~20 platforms — trim to shipped targets only.
- **Runtime:** modularise + `jlink --add-modules` to shrink the ~150 MB bundled JVM toward ~40–70 MB, or
  pursue a GraalVM native image (blocked today by JNA/Lanterna/reflection complexity).

Rough target if pursued: jar ~50 MB (Netty swap), self-contained bundle ~90–110 MB.
