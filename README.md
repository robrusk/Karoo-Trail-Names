# RRD Trail Names for Karoo

**"Trail names for your Karoo- Don't stop at trail signs"**


<img width="384" height="487" alt="Alien_sh_loop" src="https://github.com/user-attachments/assets/be730b9c-92b3-4365-b952-6e72d5f177dd" />



A community-built extension for Hammerhead Karoo that displays real-time trail names, directional arrows, and distance on your data field during rides. Works completely offline after initial download.

---

## Features

* **Real-Time Trail Names** — see the name of the trail you're riding on
* **Directional Arrows** — ← → ↑ ↓ arrows show which way the trail is relative to your heading
* **Compass Bearings** — N, NE, E, SE, S, SW, W, NW direction to nearest trail
* **Distance Display** — meters to the nearest trail segment
* **GPS Download** — one-tap download of all trails within a 20-mile radius
* **Multi-Area Support** — save Aztec, Phoenix, Sedona, or anywhere you ride — separately
* **Offline Operation** — no WiFi or phone needed after initial download
* **Beep Alerts** — audible rising chirp when entering a new trail segment
* **Auto Area Naming** — reverse geocodes your location via OpenStreetMap
* **Delete Areas** — remove individual trail areas you no longer need

---

## Display Examples

| Status | Data Field Shows |
|--------|-----------------|
| On a trail | `On: Alien Run - Long Loop` |
| Approaching (trail is to your left) | `← Alien Run (NW)` + `42m` |
| Approaching (trail is ahead) | `↑ Broken Mesa (E)` + `118m` |
| Leaving a trail | `→ Alien Run (SE)` + `85m` |
| No trail nearby | `No Trail` |

---

## Tested & Working

Successfully field-tested at multiple trail systems in Aztec, NM with 700+ trails loaded across 4 areas.

<img src="https://github.com/user-attachments/assets/73d3c6d1-ccf4-4e90-beaa-57ba2e59c2b5" width="240" alt="Trail Names Screenshot">

---

## Install

### Karoo 3 (Companion App — No Computer Needed)

1. Make sure your Karoo 3 is **connected to WiFi**
2. On your **phone**, open the browser and go to the [Releases page](https://github.com/robrusk/Karoo-Trail-Names/releases)
3. **Long-press** the APK download link (don't tap — long-press)
4. Tap **Share link**
5. Select **Hammerhead Companion** from the share menu
6. Wait for "Transfer Complete" on the phone
7. On the Karoo 3, tap **Install** when prompted
8. After install, go to **Settings → Apps → Trail Names → Permissions → Enable Location**
9. Open the Trail Names app once to register the extension
10. Add "Trail Name" to your ride profile data page

### Karoo 2 (ADB Sideload)

1. Enable Developer Mode: **Settings → About → tap Build Number 7 times**
2. Turn on USB Debugging: **Settings → Developer Options → USB Debugging**
3. Connect Karoo 2 to your computer via USB
4. Install via terminal:
   ```
   adb install RRD_Trail_Names_v1.2_beta.apk
   ```
5. Open the Trail Names app once on the Karoo to register the extension
6. Edit a Ride Profile, add the "Trail Name" data field

---

## How to Use

1. Connect your Karoo to **WiFi**
2. Open the **Trail Names** app
3. Wait for a GPS fix (coordinates will appear)
4. Tap **"Download Trails Near Me"**
5. Trails for a 20-mile radius will be downloaded and saved
6. Disconnect WiFi — trails are stored locally
7. Start a ride — trail names appear on your data field

Download trails for as many areas as you want. Each area is saved separately. Use the **X** button to delete areas you no longer need.

---

## Compatibility

| Device | Install Method | Status |
|--------|---------------|--------|
| Karoo 3 ("The Karoo") | Companion app sideload | ✅ Tested |
| Karoo 2 | ADB sideload | ✅ Tested |

---

## Technical Details

| Detail | Info |
|--------|------|
| SDK | karoo-ext 1.1.8 |
| Data Source | OpenStreetMap Overpass API |
| Storage | Local JSON files (offline after download) |
| License | MIT |![Uploading Alien_sh_loop.png…]()
![Uploading Alien_sh_loop.png…]()
![Uploading Alien_sh_loop.png…]()
![Uploading Alien_sh_loop.png…]()

| Data License | ODbL (OpenStreetMap) |

---

## Roadmap

- [x] **v1.0** — Core GPS matching and offline storage
- [x] **v1.1** — GPS-based download, multi-area management, beep alerts
- [x] **v1.2** — Directional arrows, compass bearings, thread safety fix
- [ ] **v1.3** — Trail stickiness, feet/meters toggle, adjustable radius
- [ ] **v1.4** — Customizable beep patterns
- [ ] 🔍 **Future Research**
  - Extension Library listing
  - SRAM AXS button integration for audio trail announcements
  - Dashboard sync for preferred trail areas

---

## Disclaimer

**This is an independent, community-driven extension.** It is not an official product of Hammerhead Navigation or SRAM, LLC.

* **No Affiliation** — developed by a private enthusiast, not endorsed by Hammerhead or SRAM
* **Use at Your Own Risk** — sideloading apps can affect device stability
* **Data Sources** — trail data provided by [OpenStreetMap](https://www.openstreetmap.org/) contributors under the ODbL license

---

Built by Rob Rusk — [Rusk Racing Designs](https://ruskracing.com/) — lifelong rider, first Android app.
