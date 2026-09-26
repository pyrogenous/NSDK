# Working on Nitea

Read this before changing anything in this repository. Nitea is a library bundled inside other people's mods, so a
mistake here doesn't break one project, it breaks every mod that ships the release, in every modpack that contains
one of them. The website half of the protocol lives in the `niteamc` repository (`apps/web/lib/sdk-protocol.ts`,
`apps/web/lib/api-auth.ts`, `apps/web/app/api/v1/events/route.ts`).

## Why compatibility is the first rule

Every mod bundles its own copy of Nitea (Jar-in-Jar on NeoForge and Forge, `include` on Fabric), but the game loads
only one: the newest. So a mod built against Nitea 0.4 may run on Nitea 0.9 because another mod in the pack ships
it. If 0.9 changed or removed a method 0.4 had, that mod crashes with `NoSuchMethodError`, and it's not even the mod
that updated.

On top of that, copies of different versions can run side by side (a mod that shades Nitea instead of using
Jar-in-Jar). They share one state through `System.getProperties()` (see `Engine`), so the format of that state is
also part of the contract.

## The guarantees

1. **Supported window: the current release line and the 5 before it.** A mod built against any of them works
   exactly as documented on the current Nitea. "Line" means `major.minor`: 0.4.0 and 0.4.3 are the same line.
   The lines are listed in `Compat.LINES`, the window size is `Compat.SUPPORTED_LINES` (5). Never lower it.
2. **Older than the window: the kill switch.** A mod built against a line more than 5 behind the running one still
   starts, but `Nitea.init` returns a client that does nothing, doesn't register the mod, and logs an error telling
   the mod author to update (`Nitea.start`, `Compat.check`). Which version a mod was built with is read from its
   own jar (the nested `nitea-<loader>-<minecraft>-<version>.jar` or the loader's metadata). When it can't be told,
   the mod is treated as up to date: never turn Nitea off on a guess.
3. **Public signatures are forever.** Every class, constructor, method and field in `cc.nitea` (not
   `cc.nitea.internal`, not the loader packages) that was ever released stays, with the same name, parameter types,
   return type and static-ness, even after it leaves the supported window: the kill switch only makes Nitea do
   nothing, the mod still calls those methods. If a method becomes pointless, deprecate it and make it a no-op.
   Adding methods, classes and builder options is fine.
4. **`Nitea.init` and every `NiteaClient` method never throw** because of Nitea. A failure inside Nitea turns it
   off for that mod and is logged; the mod keeps working.
5. **Shared state is append-only.** The map under the system property `cc.nitea.engine.v1` and its entries
   (`consent`, `installationId`, `gameDir`, `lock`, `listeners`, `modPackages`, `modModules`, `promptAvailable`)
   keep their names and JDK types. New entries are fine. A breaking change needs a new key (`...v2`) *and* keeping
   `v1` in sync for as long as any supported release reads it.
6. **The server accepts what supported releases send.** `MIN_SDK_VERSION` on the website must stay at most 5 lines
   behind the newest release, and the event schema (`apps/web/lib/ingest.ts`) only gains optional fields. Protocol 1
   (unsigned, Nitea 0.3 and older) stays accepted for projects with `allowLegacySdk`. Turning a single broken release
   off remotely is `NITEA_DISABLED_SDK_VERSIONS` on the website; the library answers a 426 by turning itself off.
7. **Nitea stays dependency-free and Java 8** in `core/` (`options.release = 8`). The tests run on JDK 25.

## What enforces it (run `./gradlew -p core test`)

| Test | Checks |
| ---- | ------ |
| `ApiCompatibilityTest` | Every signature in `core/src/test/resources/api/*.txt` (one file per released line) still exists; the current snapshot is up to date; every line in `Compat.LINES` has a snapshot and a fixture. |
| `LegacyCompatibilityTest` | For every kept release in the window: `compat/mods/<line>/` (a mod using every public method of that release) is compiled against the real old jar `compat/nitea-core-<version>.jar`, then run on the current Nitea, and its events must arrive, signed. For every kept release: an old copy and the current one share consent, installation ID, the mod list and error attribution. |
| `CompatTest` | Version detection from mod jars (Jar-in-Jar, Fabric `include`), the line arithmetic, the kill switch turning a too-old mod off and keeping a 5-lines-old one on. |
| `ProtocolTest` | Signing, proof of work, and the reaction to every API answer (428, 400 `clock_skew`, 429, 403, 426, 401). Its test vector is shared with `apps/web/lib/sdk-protocol.test.mjs`. |

A failing compatibility test is never fixed by editing a snapshot of a released line, a legacy mod or a fixture jar.
Those are the released reality. Fix the code.

## Releasing a new version

1. Bump `nitea_version` in `gradle.properties`. For a new line (e.g. 0.5.0): set `Compat.CURRENT_LINE = "0.5"` and
   append `"0.5"` to `Compat.LINES`.
2. If you added public API: `./gradlew -p core test -PupdateApi` rewrites `api/<current line>.txt`. Review the diff:
   it may only add lines.
3. `./gradlew -p core test` and `./gradlew buildAll` must pass.
4. Tag and push (`git tag 0.5.0 && git push origin 0.5.0`); the Publish workflow does the rest.
5. **Right after tagging a new line**, keep it for the next releases' tests:
   - `./gradlew -p core compatFixture` writes `core/src/test/resources/compat/nitea-core-<version>.jar`;
   - copy the previous `compat/mods/<line>/legacy/LegacyMod.java` to `compat/mods/<new line>/legacy/`, change the
     version in its comment, and add a call for every public method the new line added;
   - commit both. From then on they never change.
6. On the website: bump `NSDK_VERSION` in `packages/nsdk/index.js`, and if the window moved past it, raise
   `MIN_SDK_VERSION` in `apps/web/lib/sdk-protocol.ts` (never above the oldest line of the window).

Fixture jars of lines that left the window can stay: the shared-state test keeps using them, and they cost little.

## Protocol 2 (Nitea 0.4+), in short

Each `POST /api/v1/events` carries `Authorization: Bearer <key>` plus `X-Nitea-Protocol: 2`, `X-Nitea-Mod`,
`X-Nitea-Owner` (the `owner(...)` class), `X-Nitea-Timestamp`, `X-Nitea-Signature: v1=<HMAC-SHA256 of the canonical
text, keyed with hex SHA-256 of the key>` and `X-Nitea-Pow` (a nonce giving `SHA-256(signature ":" nonce)` the number
of leading zero bits the server announces in `X-Nitea-Pow-Bits`). The canonical text is
`nitea-v1\nPOST\n/api/v1/events\n<timestamp>\n<modId>\n<owner>\n<hex SHA-256 of body>`. See `Signer` and
`Transport`. Changing any of it means a new signature version (`v2`) accepted next to `v1` for the whole window.

The server checks, cheapest first: per-address token bucket (memory), library version, headers, clock (±5 min),
proof of work, replay; then, with the database, the key, the signature, the mod ID and Java package, and the
per-project / per-installation quotas. The library follows the answers: 429 pauses for `Retry-After`, 428 and
400 `clock_skew` are corrected and resent, 401/403/426 turn Nitea off for that mod until the next launch with a
log line saying why.

## Other conventions

- `cc.nitea.internal` is not API, but loader modules in `versions/` use some of it (`ConsentText`, `Engine`,
  `Browser`): they're compiled together, so that's fine inside one release.
- Log messages a mod author must act on use `Log.error`; the troubleshooting page of the docs lists them, keep it in
  sync (`apps/docs/docs/guides/troubleshooting.mdx` in `niteamc`).
- Comments explain why, in the style of the surrounding code.
