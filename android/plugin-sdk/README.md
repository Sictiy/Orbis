# Orbis Android plugin contract

This module defines the Android IPC contract used by the Orbis plugin
host and separately installed plugins. Orbis implements service
discovery, user activation, binding, host-scoped UI contributions and declarative
root pages, and experimental Android 11+ plugin-rendered embedded pages. The
feature is in a test-stage working tree, not a stable published SDK.

## Local SDK artifact

`sdk-version.properties` declares the SDK artifact version. To prepare the local
Maven repository for a plugin consumer, from the `android` directory run:

```text
gradlew :plugin-sdk:publishReleasePublicationToLocalSdkRepository
```

The repository is `plugin-sdk/build/local-maven` unless
`-PorbisSdkPublishRepo=<directory>` selects another local directory. The
coordinate is `com.orbising.orbis:plugin-sdk:<version>`; publishing here does not
publish it to a public registry. Move or copy this repository to the plugin's
build environment to build the plugin without the Orbis source checkout. SDK
changes require a new version and an intentional update of the consuming
plugin's pinned artifact hashes; do not replace an existing version silently.

Plugin consumers may opt into local source co-development, which uses a mutable
checkout and therefore is not a reproducible release build. The host itself
continues to compile the SDK from its own source module.

Each plugin is a separate APK. The host binds an explicit approved service and
uses the AIDL interfaces in this module. Requests and callbacks are asynchronous;
the caller must still enforce deadlines and bound outstanding work. Callbacks are
untrusted IPC just like requests.

`IOrbisPlugin.openBinding` supplies a binding-scoped `IOrbisHost` endpoint to
the plugin. The endpoint reserves a path for plugin-initiated host actions, but
the current Orbis binding does not accept plugin callbacks. `binding.open` is
accepted only through `openBinding`; other plugin operations use
`IOrbisPlugin.request`. Implementations must cancel outstanding calls when the
binding is closed or invalidated.

Before generated AIDL code unmarshals a transaction, the receiving Stub must
enforce `MAX_PARCEL_BYTES` in `onTransact`. Inside every incoming request,
cancellation, and callback, capture `BinderPeerVerifier.captureCallingPeer()`
on the Binder thread and compare the result with the explicitly approved package
and current signer. In addition, the host must verify the bound service component,
the active binding, operation-specific grant, request identity, and target Host;
plugins must independently verify the calling host package and signer against their policy. Do not authorize
using the package, plugin ID, or any other identity claimed in JSON.

`PluginEnvelopeValidator` handles only request envelope syntax and bounds. Its
result is not a permission check and does not validate operation-specific payloads.
The caller supplies the actual endpoint so the validator can reject operations
sent to the wrong endpoint; it must still check the operation's capability grant.
`PluginResponseValidator` validates the shared response envelope but does not
authenticate the caller or validate operation-specific payloads. Implementations
still need payload schemas, replay/lifecycle handling, per-plugin queues, cancellation,
package-replacement handling and timeouts before exposing this interface.
PendingPluginRequests provides a bounded, binding-scoped outgoing request
registry, but its owner must schedule expiry, cancel remote work, and authenticate
and validate replies before invoking takeReply. The host must also enforce the
per-plugin aggregate limit if it permits multiple registries for one plugin.

Deadline values use `SystemClock.elapsedRealtime()` on the same Android device.
Never put message text, conversation history, credentials, or cryptographic keys
in events. Operation-specific fields require their own explicit data policy.

The Android `:app` depends on this module. The current host accepts plugin UI
contributions only after the user activates the plugin; a plugin remains a separate
APK and may independently enforce caller identity.

For device-level IPC development, `../plugin-test-host` builds a separate test
APK. It can probe discovery, binding, UI payloads and trust revocation against
a separately installed plugin; it does not integrate plugins into the Orbis app.
