# Third-party notices

Joying Cluster Bridge is licensed under the GNU General Public License v3.0
(GPL-3.0-only). Copyright © 2026 Fred Oranje. LICENSE and COPYING contain the
full license text. Third-party components retain their original copyright
notices and licenses, as described below.

The original OsmAnd AidlParams.java, AppInfoParams.java and ALatLon.java are copied unchanged from commit b473b4f7ed2fad0eede41d3af6ef5856c9fa614f. Copyright © 2010–2022 OsmAnd BV and contributors. The original license and complete reference AIDL are archived under contract/. scripts/check_contract.py checks their hashes and getAppInfo numbering. COPYING contains the GPLv3 text. No OsmAnd artwork is included.

The Joying Binder client is independently implemented from API signatures/wire behavior in the owner's com.car.service and com.car.media APKs. No vendor classes, native libraries or proprietary binaries are included. The direct ZLink receiver and iAP2 metadata decoder are independently implemented from the supplied APKs’ protocol behavior; no vendor code or binaries are bundled.

The sanitized test fixtures preserve callback ordering/timing/types but replace actual track/artist/album strings with generic labels.

Gradle wrapper scripts/JAR are Apache-2.0; their notices are preserved. gradle/wrapper/LICENSE.gradle retains the Gradle distribution's license notice. JUnit 4.13.2 (EPL-1.0) and Robolectric 4.14.1 (MIT) are test-only dependencies and are not included in the application APK.
