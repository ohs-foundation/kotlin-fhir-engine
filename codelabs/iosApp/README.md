#### Running on a real iOS device

To run on a real iOS device, you'll need the following:

* The `TEAM_ID` associated with your [Apple ID](https://support.apple.com/en-us/HT204316)
* The iOS device registered in Xcode

##### Finding your Team ID

Install KDoctor with [Homebrew](https://brew.sh/):

```text
brew install kdoctor
```

Run `kdoctor --team-ids` to find your Team ID.
KDoctor will list all Team IDs currently configured on your system, for example:

```text
3ABC246XYZ (Max Sample)
ZABCW6SXYZ (SampleTech Inc.)
```

<details>
<summary>Alternative way to find your Team ID</summary>

If KDoctor doesn't work for you, try this alternative method:

1. Open `iosApp/iosApp.xcodeproj` in Xcode.
2. In the left-hand menu, select `iosApp`.
3. Navigate to **Signing & Capabilities**.
4. In the **Team** list, select your team.

If you haven't set up your team yet, use the **Add account** option and follow the steps.

</details>

##### Setting your Team ID

1. Copy `iosApp/Local.xcconfig.example` to `iosApp/Local.xcconfig`. `Local.xcconfig` is ignored
   by Git, so your Team ID stays on your machine.
2. In `Local.xcconfig`, replace `YOUR_TEAM_ID_HERE` with your Team ID.
3. Plug your iOS device into the computer.
4. Open `iosApp.xcodeproj` in Xcode, select your device, and click **Run**.

##### Connecting to the FHIR server

The app connects to `http://localhost:8080/fhir/`, which only works on the simulator. On a real
device, `localhost` is the device itself. Change `baseUrl` in
`shared/src/iosMain/kotlin/dev/ohs/fhir/codelab/MainViewController.kt` to an address of your Mac
that the device can reach on the same network, such as `http://<your-mac-name>.local:8080/fhir/`.
