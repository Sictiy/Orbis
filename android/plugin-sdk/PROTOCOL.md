# Plugin IPC contract: work in progress

This describes the current SDK contract and its operation-specific payloads.
This remains a working contract, not a stable SDK release;
requirements below do not imply that every proposed capability is implemented.

## Endpoints

The plugin exports a Service with action
`com.orbising.orbis.plugin.BIND`. The host discovers it with a restricted
package-visibility query, presents its package, signer and requested capabilities
to the user, and binds the approved explicit component. Discovery is not approval.

- `IOrbisPlugin.openBinding` passes a binding-scoped host endpoint and callback.
- `IOrbisPlugin.request` sends requests to the plugin.
- `IOrbisHost.request` is reserved for host-side requests; the current Orbis
  binding does not accept plugin-initiated callbacks.
- Both endpoints support cancellation. `IPluginReply` returns a response.

All methods are asynchronous AIDL transactions. A timeout, successful Binder
enqueue or cancellation does not imply that a remote operation was completed or
reverted. Operation outcomes need separate, bounded correlation.
The `host.action.request` and `plugin.event.publish` operations are reserved
for the host endpoint, but the current Orbis binding does not accept them; their
payload schemas and authorization rules remain future work. `operation.read`
lets the host retrieve an accepted operation's state from the plugin.

## Request envelope

Every listed field must appear; nullable fields use JSON `null`. Unknown or
duplicate fields and duplicate keys in nested objects are rejected.

| Field | Type | Meaning |
| --- | --- | --- |
| `apiVersion` | integer | Protocol version, currently 1. |
| `payloadSchemaVersion` | integer | Payload schema version, currently 1. |
| `requestId` | identifier | Unique request correlation ID. |
| `bindingId` | identifier or null | Active binding; null is allowed for descriptor lookup and binding establishment. |
| `pluginId` | identifier | Plugin identifier; not proof of caller identity. |
| `capability` | string | Requested capability; the receiver must check its grant and operation mapping. |
| `op` | string | One of the SDK's recognized operations. |
| `hostId` | identifier or null | Target paired Host when relevant. |
| `deadlineElapsedRealtimeMs` | integer | Absolute Android elapsed-realtime deadline. |
| `payload` | object | Operation-specific data, subject to its own schema and data policy. |

Identifiers are 1–128 ASCII letters, digits, period, underscore, colon or
hyphen, beginning with a letter or digit. Capability names begin with a lowercase
letter and contain up to 64 lowercase letters, digits, periods, underscores or
hyphens. A normal deadline is at most 3 seconds ahead when received; preflight
is at most 1 second ahead.

The UTF-8 request is at most 16 KiB, each decoded string at most 8 KiB, and the
Binder Parcel at most 64 KiB. JSON is limited to 16 nesting levels, 1,024 values,
and 128 members or elements per collection. The current constants apply to the
whole envelope, not only its payload. A content page must also fit the enclosing
envelope after JSON escaping.

Example (the deadline is illustrative):

```json
{
  "apiVersion": 1,
  "payloadSchemaVersion": 1,
  "requestId": "9e1d5739-248d-4f0b-899f-05f918e5ca53",
  "bindingId": "e2d229f2-5b8d-4633-bb08-e08b0db81439",
  "pluginId": "com.example.plugin",
  "capability": "ui",
  "op": "ui.read",
  "hostId": null,
  "deadlineElapsedRealtimeMs": 912345,
  "payload": {"slot": "settings.section"}
}
```

## Response envelope

The response repeats the request's `apiVersion`, `payloadSchemaVersion`,
`requestId`, `bindingId`, `pluginId`, `capability`, `op` and `hostId` for
correlation. A `binding.open` response echoes the request's null binding ID;
its payload carries the newly established ID, which the binding-specific
schema must validate.

It also contains `status`, `errorCode` and `payload`. Status is `ok`, `accepted`
or `error`. An error has a known error code and an empty payload; other statuses
require a null error code. `accepted` means the plugin accepted asynchronous
work, not that it completed. The operation ID and final result need their own
payload schema. The receiver checks its original local deadline.

```json
{
  "apiVersion": 1,
  "payloadSchemaVersion": 1,
  "requestId": "9e1d5739-248d-4f0b-899f-05f918e5ca53",
  "bindingId": "e2d229f2-5b8d-4633-bb08-e08b0db81439",
  "pluginId": "com.example.plugin",
  "capability": "ui",
  "op": "ui.read",
  "hostId": null,
  "status": "ok",
  "errorCode": null,
  "payload": {"schemaVersion": 1, "contributions": []}
}
```

## Required checks around the envelope

The host and plugin must separately capture and verify the real Binder caller
for every request, cancellation and callback. Package, current certificate,
service component, binding, grants, target Host, request and operation must be
checked as appropriate. Enforce the Parcel bound before unmarshalling; enforce
the JSON and payload bounds before processing. Never perform network I/O on a
Binder thread.

Request IDs generated by PendingPluginRequests are unique to an outgoing call.
The owner must schedule timeouts, stop waiting on Binder death, reject late
callbacks and invalidate calls after package replacement, permission revocation
or binding closure. Only the owner can decide what to do with a previously
deferred user action; the plugin never receives chat text or Orbis credentials.

## Draft `ui.read` response payload

After authenticating the peer, matching the response to the original request,
and verifying the UI grant, the host validates the response payload against the
original locally selected slot and host ID using `PluginUiValidator`. A reply
cannot choose another slot or host. `hostId` is null for settings and navigation,
and equals the requested paired Host ID for the two host-scoped slots. A host
must discard actions when the binding, grant, Host or displayed snapshot changes.
An unchanged, fully validated contribution list may retain its snapshot identity;
changed or invalid data, disconnects and revocation still invalidate old handles.

```json
{
  "schemaVersion": 1,
  "contributions": [{
    "id": "sample.action",
    "slot": "paired_host.trailing_actions",
    "hostId": "host-1",
    "component": {"type":"icon_action", "icon":"power", "label":"Action", "enabled":true, "actionId":"sample.action"}
  }]
}
```

Each contribution has exactly `id`, `slot`, `hostId` and `component`; IDs follow
the envelope identifier syntax and must be unique in the response. The host
accepts at most four items in `settings.section`, one in
`navigation.bottom_tab`, four in `chat.host.sidebar.section`, and one in
`paired_host.trailing_actions` per plugin. The following exact component fields
are allowed; every listed field is required and unknown fields are rejected.

| Component | Slots | Fields besides `type` |
| --- | --- | --- |
| `icon_action` | paired host | `icon`, `label`, `enabled`, `actionId` |
| `navigation_item` | navigation | `icon`, `label`, `actionId` |
| `text_row` | settings, host panel | `title`, `description` |
| `status_badge` | settings, host panel | `label`, `tone` |
| `status_card` | settings, host panel | `title`, `description`, `tone`, `action` |
| `section_action` | settings, host panel | `label`, `description`, `enabled`, `actionId` |

For the active host's drawer footer, Orbis shows current `status_badge` and
`paired_host.trailing_actions` contributions irrespective of host connectivity.
A connected host always retains the host's own connected state. With
one plugin and at most one badge, its status and action appear with the host;
the action may wrap to another line when space is limited. When
multiple plugins or badges contribute, Orbis lists each plugin's statuses and
actions together. A plugin may contribute an action without a status badge and
decides whether its own action is applicable.
Actions are resolved against their own current authenticated snapshot and are
invalidated when it is replaced. Other host-panel component types do not replace
the footer status. This presentation does not grant access to additional hosts.

`action` is null or exactly `{ "label": string, "enabled": boolean,
"actionId": identifier }`. Labels and titles are nonblank, at most 120 UTF-16
code units; descriptions can be empty and are at most 512. Text cannot contain
control characters. Allowed icons are `info`, `settings`, `power`, `refresh`,
`link`, `more`, `play`, `check`, `warning`, and `device`; allowed tones are
`neutral`, `info`, `success`, `warning`, and `error`. All text is displayed as
plain text. No URL, Intent, resource identifier, code, HTML or arbitrary view is
accepted. An `actionId` is an opaque plugin action, never a host command.

This schema defines validation only; it does not itself render contributions,
establish trust, authorize an action, or implement page navigation.

## Draft binding and contribution calls

For the initial proof of concept, `pluginId` is the installed plugin package
name. `binding.open` uses capability `binding`, a null binding ID and payload
`{"grants":["ui"],"hostIds":["host-1"]}`. The grants are selected by the
user; `hostIds` contains only explicitly authorized paired Hosts, not an implicit
all-hosts grant. The SDK accepts at most 16 distinct Hosts and the capabilities
`ui`, `host_state_observe`, `preflight`, and `host_action`. The plugin validates
the request, independently authenticates the host, and replies with the request's
null binding ID and payload `{"bindingId":"<new-random-id>"}`. The new ID is
valid only for that authenticated binding and is discarded on closure or death.

`descriptor.read` uses capability `descriptor`, null binding ID and empty
payload. Its successful payload is limited to non-sensitive static data:
`schemaVersion`, `pluginId`, `name`, `version`, and `capabilities`. It cannot
grant itself permissions. A `ui.read` request uses capability `ui` and payload
`{"slot":"navigation.bottom_tab"}` (or one of the host-scoped slots); its
envelope carries the authorized host ID when the slot requires one.
The SDK validates the reserved `settings.section` shape, but the current Orbis
host does not request or render it. Plugins must not depend on that slot as an
entry point; use the navigation contribution or the verified setup Activity.

For a host-validated, currently visible contribution, `action.perform` uses
capability `ui` and payload `{"slot":"...","contributionId":"...",
"actionId":"..."}`. The host resolves the action from its current
binding-scoped snapshot instead of trusting an action ID supplied by UI state.
The plugin independently checks its binding, grant, Host scope and action
mapping. An `action.perform` error of `PERMISSION_DENIED`, `UNSUPPORTED_VERSION`,
`INVALID_PAYLOAD`, or `CAPACITY_EXCEEDED` guarantees the action was not performed.
Plugins must return these codes before initiating any side effect. Other errors,
including `TIMEOUT`, `CANCELLED`, `PLUGIN_UNAVAILABLE`, and `INTERNAL_ERROR`, do
not guarantee that the action was not performed and must be treated as unknown.
A transport failure likewise does not establish whether the plugin acted.

Asynchronous actions may return `accepted` with payload `{"operationId":"..."}`;
acceptance is not success. The host queries `operation.read` with the original
Host ID (including null for navigation) and payload
`{"operationId":"...","schemaVersion":2}`. A plugin that implements this
version must accept the earlier `{"operationId":"..."}` request too and retain
its legacy state values in response to that request. When a plugin rejects the
version 2 payload with `INVALID_PAYLOAD`, the host can safely retry the read with
the earlier payload, because this call only reads status. The successful response
has `operationId`, `state`, and optional nullable `result`.
The public states are `pending` (not yet terminal), `succeeded`, `failed` (the
operation has definitely failed and its effects are known), and `unknown`. Plugins
must map their own internal stages to these states and return `unknown` whenever
they cannot determine the effects. For compatibility, the host also recognizes
the earlier pending stages `submitting`, `requested`, `dispatched`, `accepted`,
`executing`, and `verifying`; the earlier terminal stages `rejected`, `cancelled`,
and `expired` are treated as unknown because their effects were not specified.

For navigation actions, synchronous success is `{"destination":"page"}` or
`{"destination":"setup"}`. An asynchronous navigation success puts that same
object in `operation.read.result`. The host validates the destination, and an
obsolete navigation request must not change the current page. Operation tracking
continues even if the user navigates elsewhere. Handles are bound to the current
binding and Host and can disappear after disconnection or process restart. Hosts
may bound polling time (currently 90 seconds in Orbis); a lost handle, timeout,
malformed result, or unknown state must be shown as an unknown result, with no
automatic retry. The user must acknowledge the unknown result after checking the
operation before starting another action in the same scope.

`page.read` with a null Host and empty payload
returns `schemaVersion`, `title`, and `message` as bounded plain text. Hosts
may request `{"schemaVersion":2}` to receive a declarative page with `title`,
`message`, at most 64 `{title,value}` text rows, and a `settings` boolean.
The host owns rendering and validates all fields; the settings action opens the
verified plugin's setup screen. If the plugin rejects version 2, the host can
retry the read using the version 1 empty payload. A host
must validate each operation-specific payload and correlate it with its request.
No host action is authorized solely by appearing in a plugin-provided payload.

### Optional activation and restoration

A plugin may expose an exported Activity for `com.orbising.orbis.plugin.ACTIVATE`
with the DEFAULT category, protected by a signature permission declared by the
plugin or the host. A host can use this optional entry point after an explicit user
activation or reconnect action to allow the system to start a stopped plugin.
The host revalidates the service package, signer and component, requires a shared
signature and a granted signature permission owned by either peer, and starts the single
matching Activity explicitly. On `RESULT_OK` it revalidates the service again
before binding. Plugins without this entry point retain ordinary service binding.
The Activity should finish immediately without showing plugin UI or granting
access. For an existing saved approval, the host can also launch this entry point
while its own Activity is resumed to restore a stopped plugin; it must never launch
it from the background or treat the launch as a new authorization. OEM startup
policies can still block activation; the host must report failure instead of
assuming a connection.

Orbis persists a successful approval for the selected paired Host and the exact
service package, class and signer. On a later launch it rediscovers that identity
before attempting to restore it, and revalidates it again after the activation
Activity returns. Connection or activation failures are retried with backoff while
the approval remains in place. Removing authorization prevents its restoration.
Unpairing a Host revokes its saved plugin approvals and closes its bindings;
pairing it again requires a new activation. Unresolved operation markers remain
until acknowledged so reconnecting cannot silently duplicate a prior operation.
A plugin without a prior saved approval, or whose signer or service has changed,
still requires explicit user activation.


### Experimental embedded pages (Android 11+)

A service may opt in with the boolean service metadata
`com.orbising.orbis.plugin.EMBEDDED_PAGE`. The host binds the *same approved
service component* with action `com.orbising.orbis.plugin.EMBEDDED_PAGE` and
uses `IEmbeddedPageService` to request a page for an existing binding. This
interface is experimental.
Absent metadata or a failed request, the host falls back to `page.read`.

The page context identifies the generic entry point, current dark/light theme,
and up to 128 paired Host IDs and display names as metadata for presentation and
cross-host association. These names and IDs are disclosed to the activated plugin;
they do not authorize operations for those Hosts. The separate binding scope
continues to authorize only the current Host. The context never includes
conversation content or host credentials. A page can report whether it handles system Back. A host sets
`ownActivityRequests` to true in page context when it supports
`IEmbeddedPageCallback.onRequestOwnActivity`; a page must check this flag before
requesting an Activity so an older host does not silently ignore the request.
The request Bundle contains exactly `className` and `extras`: `className` is the
plugin Activity's fully qualified class name of at most 255 characters, and
`extras` is a Bundle with at most 16 entries. Keys match `[A-Za-z0-9_.-]{1,64}`;
values are Strings of at most 512 characters with no ISO control characters.
The host authenticates the callback and current binding, permits it only from
the currently visible page, and resolves an enabled, exported Activity in the
approved plugin package. It checks the Activity's permission and any alias's
target, then constructs an explicit Intent restricted to that package. Failure
is reported through `activityLaunchFailed`. The old `onRequestActivity`
PendingIntent callback remains in its original AIDL position for wire stability,
but new hosts reject it: the PendingIntent creator does not establish its actual
target. The page control can update the theme without reopening the page.
This enables plugin-owned flows such as camera scanning without
implementing those flows in Orbis.

The context may also contain a `theme` Bundle with `dark` (boolean) and the
ARGB integer colors `base`, `lift`, `ink`, `muted`, `accent`, `success`,
`danger`, `highlight`, `shadow`, and `scrim`. A compatible page can apply the
same Bundle through `IEmbeddedPageControl.setTheme` when the host appearance
changes. `darkTheme` and `setDarkTheme` remain available for older pages; a page
that cannot use the complete palette can use its own light/dark defaults. The
theme is presentation data and conveys no additional permission.

A host may set `hostSheet` in the page context to advertise support for a
host-rendered bottom sheet. A supporting page sends bounded `EmbeddedSheet`
presentation data through `onSheetChanged`; null closes it. Text, choices and
buttons use the host's own sheet, theme and window insets. The host checks the
callback identity, request and current binding, validates the whole payload and
only shows it for the attached selected page. It sends a selected opaque action,
or `dismiss`, through `sheetAction`. A page authenticates the caller and accepts
only actions belonging to its current sheet; neither the presentation nor its
tokens grant additional permissions. Busy sheets may temporarily disallow
dismissal and actions. Older peers retain the page's own presentation. Sheets
are limited to 60 entries and 12,000 text characters; a plugin with more choices
should page them. Tokens must not encode credentials or device IDs.

The plugin must authenticate the Binder caller on every transaction, verify
that `bindingId` belongs to that caller and grants UI, validate the request,
limit dimensions and number of live pages, and release each page on close,
binding revocation, or host death. The host verifies the callback caller against
the approved identity and correlates its random request ID before accepting a
surface. Only an already-visible, user-selected plugin page may open a surface.
The host releases the page on tab switch, disconnection, or disposal of the
selected page. A temporary Surface destruction, such as when a plugin-owned
Activity covers the host, does not close the page; its surface may be reattached
when the host becomes visible again.
Page creation times out. A page provides only its own pixels and input; it does
not imply access to other host identities or host application permissions.

The surface is placed above its host window to support input dispatch and must
be confined to the page area. Native dialogs, host overlays, keyboard, screen
rotation, accessibility, background lifecycle, and plugin process death require
independent tests on target devices. `IEmbeddedPageControl` is an instance-scoped
capability and its caller must be reauthenticated. The current implementation is
test-stage and Android 11+ only; real-device acceptance remains necessary.
