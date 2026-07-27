# Foundry project connections — SDK feedback

Dog-fooding feedback from building Konductor's credential-safe project connection catalog against
`com.azure:azure-ai-projects` **2.2.0**. This was cross-checked against the 2.2.0 source and its
`README.md`/`ConnectionsSample` at Azure SDK commit `ee28e96b521`.

Legend: **Impact** = what it cost us · **Workaround** = what Konductor does today · **Suggestion** = what would
have removed the friction.

## The documented no-credential lookup overload is package-private

The 2.2.0 README's “Get a connection without credentials” example and
`com.azure.ai.projects.ConnectionsSample.getConnectionWithoutCredentials()` call
`ConnectionsClient.getConnection(String)`. That one-argument method is package-private, as is the asynchronous
`ConnectionsAsyncClient.getConnection(String)` equivalent. The examples compile only because the SDK samples share
the `com.azure.ai.projects` package with the clients; an ordinary consumer in its own package cannot call the shown
API. The public synchronous lookup is `ConnectionsClient.getConnection(String, boolean)` (and the async client has
the equivalent two-argument method).

- **Impact:** copying the official no-credential example into application code fails to compile. The only public
  convenience overload also makes callers pass a security-sensitive `includeCredentials` boolean, so the safest and
  most obvious operation is less ergonomic and an accidental `true` changes which service operation is invoked.
- **Workaround:** `AzureFoundryConnectionCatalog` receives an already-built `ConnectionsClient` and calls
  `getConnection(name, false)` at its SDK adapter boundary. It never requests credential values and maps only name,
  id, connection type, target, default status, metadata, and credential type into an application-owned DTO.
- **Suggestion:** make `ConnectionsClient.getConnection(String)` and
  `ConnectionsAsyncClient.getConnection(String)` public no-credential conveniences, and update or compile-check the
  README snippets from a normal consumer package. A distinctly named public no-credential method would also avoid a
  boolean whose wrong value retrieves secrets.
