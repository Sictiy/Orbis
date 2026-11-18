# Plugin test host

The debug build includes a synthetic plugin service used by `PluginTabInstrumentedTest.settingsActionOpensPluginPageAndClosesSheet`. It only returns static test UI and has no device actions. Install it on the test device **before** running that instrumented test:

```powershell
.\gradlew.bat :plugin-test-host:installDebug
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.orbising.orbis.PluginTabInstrumentedTest'
```

The tests use the synthetic service and isolated host storage. The synthetic
service and its signature-protected test activation entry are included only in
the test host's debug build. Tests that force-stop it run only on an emulator
and restore the fixture after the test.

## Cross-process surface feasibility probe

`SurfaceProbeService` is a debug-only, same-signature fixture. The separate
`PluginSurfaceProbeInstrumentedTest` embeds its native view into a host
`SurfaceView` on Android 11 and later. It verifies touch, text input, a host
button outside the surface, release and recreation, and Binder death when the
fixture process exits. Run it on the leased emulator with:

```powershell
.\gradlew.bat :plugin-test-host:installDebug :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.orbising.orbis.PluginSurfaceProbeInstrumentedTest'
```

The fixture is not the production protocol or a security design. In the
Compose probe the host window intercepted touches until the surface used
`setZOrderOnTop(true)`. Such a surface can cover host UI in the same window,
so a production container must bound it away from host navigation and test
menus, dialogs, accessibility, IME, resizing, lifecycle, and real devices.
Android 8–10 cannot use `SurfaceView.setChildSurfacePackage`; a compatible
fallback is still required. Plugin authentication, authorization, cancellation,
timeouts, resource limits and cleanup on both process deaths must be designed
and tested before the capability is exposed to plugins.
