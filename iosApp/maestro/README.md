# iOS end-to-end tests (Maestro)

CI job **E2E / ios-simulator** (`.github/workflows/e2e-ios.yml`) builds the Debug simulator app,
boots an iPhone simulator, and runs `run-e2e.sh`, which runs every flow in `flows/` in one Maestro
session. Results are uploaded as the `e2e-ios` artifact:

- `flows/<flow>/` – that flow's screenshots (`takeScreenshot/`), Maestro `commands.json`, logs, `check.log`
- `app-stdout.log` – the app's stdout (`TORCHECK …`, `echalote.*.timing` lines); `tor-lines.log` is the Tor subset
- `report.xml` (JUnit), `summary.md` (also in the job summary), `final.png`

## Layout

- `flows/*.yaml` – one file per test. `flows/config.yaml` sets the run order (`executionOrder`).
- `flows/<flow>.check.sh` – optional check run after Maestro with `APP_LOG` (app stdout) and
  `FLOW_OUT` (the flow's artifact folder). Non-zero exit fails the job; its last output line goes in the summary.
- `common/*.yaml` – shared subflows. `open-settings.yaml` gets to Settings from a fresh install, home,
  or Settings; on a fresh install it runs `import-watch-only.yaml`, which imports the public watch-only
  address `1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa`.

All flows share one fresh install and one app process. Don't open Create wallet, and never screenshot
a seed or secret screen: use the watch-only import.

## Adding a flow

1. Create `flows/<name>.yaml` with `appId: io.bluewallet.blueberry` and `name: <name>` (same as the file name),
   then start it with `- runFlow: ../common/open-settings.yaml` or another common subflow.
2. Use text or accessibility labels as selectors (Compose `contentDescription` shows up as the label).
   Use `extendedWaitUntil` with generous timeouts for anything that goes over the network.
3. Take screenshots with `- takeScreenshot: NN-what`. They land in `flows/<name>/takeScreenshot/`.
4. If it depends on another flow's state, add it to `flowsOrder` in `flows/config.yaml`.
5. Optionally add an executable `flows/<name>.check.sh` for assertions on the app log.

## Running locally (macOS, Xcode, Maestro 2.12.1)

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO build
iosApp/maestro/run-e2e.sh \
  iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator/Blueberry.app /tmp/e2e-ios
```

Set `SIM_UDID=<udid>` to pick a simulator. The script uninstalls and reinstalls the app, so the flows
start from onboarding. To run one flow against an app you've already launched:
`maestro test iosApp/maestro/flows/test-tor-cold.yaml`.
