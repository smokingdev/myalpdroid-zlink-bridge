# Direct ZLink source — implementation and validation notes

The default remains the existing Joying subscriber. `directZlink=false` is the default preference. Changing the switch while running closes the old reader, invalidates its queued callbacks, clears visible metadata, and opens the selected reader. It preserves the diagnostic session. Stop, sticky restart and boot startup continue to use the same persisted enabled/auto-start preferences.

## Independently recovered wire format

The supplied `libzlink_metadata.so` reads TCP from `127.0.0.1:1555` without sending registration bytes. Its receive loop uses an eight-byte header: unsigned little-endian message type and payload length. This implementation handles fragmented headers/payloads and multiple frames per read, with a 1 MiB maximum frame payload. Unknown framing disables parsing while raw logging continues. Closed/failed sockets retry after 15 seconds; a connected socket waiting for data stays open. No writes, vendor library loading, or direct vehicle frames are used.

Known CarPlay frame types from the client's dispatch table:

- 8: route guidance.
- 9: maneuver update.
- 10: media, containing nested item (field 0) and playback (field 1) groups.

Their payloads use iAP2 parameters: big-endian uint16 total parameter length, big-endian uint16 field ID, then value bytes. Length includes the four-byte header. Unknown fields are retained in the raw frame. Parsing rejects truncated/invalid lengths and invalid scalar widths.

Guidance uses component ID (0/u16), route state (1/u8), live distance (10/u32), and the maneuver-index list (13/u16 array). State 1 is the observed active guidance state. The first list entry selects the current maneuver. A route-plan burst can contain future maneuvers, so the decoder never chooses whichever packet arrived last. A missing selection/cache entry stays inactive. Only real distance updates refresh the 30-second freshness deadline.

Maneuver fields include component (0/u16), index (1/u16), description (2/string), type (3/u8), road (4/string), segment distance (5/u32), driving side (8/u8), junction type (9/u8), junction-arm angles (10/s16 array), and exit angle (11/s16). Segment length never substitutes for the live countdown. Route transitions, disconnects and source changes invalidate cached guidance.

Item fields used: persistent ID (0/u64), title (1/string), duration milliseconds (4/u32), album (6/string), artist (12/string), album artist fallback (14/string). Playback fields used: state (0/u8: 0 stopped, 1 playing, 2 paused) and elapsed milliseconds (1/u32). Absent fields retain context; a new persistent ID clears previous item details. Title-only chapter changes retain the current podcast context. No media controls/audio output are implemented.

## Roundabouts

CarPlay maneuver types 28–46 encode exits 1–19. Generic roundabout types and junction-type 1 are also recognized. A present exit angle within -180..180 and a known driving side (0/right traffic, 1/left traffic) are required. A missing angle or the native 360 sentinel does not become a fabricated zero. An explicitly transmitted zero is preserved.

The signed exit bearing is forwarded as `next_turn_angle`; `next_turn_possibly_left` selects the traffic side. Historical MyAlpDroid recognizes `RNDB` plus one digit, then chooses its graphic from angle/side. Exits above nine use the generic single-digit marker `RNDB0`; no exit count is fabricated for the cluster. The bridge dashboard shows the available single-digit exit number and a schematic symbol.

Angle orientation and the actual graphics on the installed commercial MyAlpDroid require a physical test. The public source is not proof that every installed version renders identically. Reference source inspected: [MyAlpDroid ClusterInfo.kt](https://github.com/sandev83/AlpDroid/blob/133b16d11775f67845a06c3ede4eab71b21e5428/app/src/main/java/com/alpdroid/huGen10/ClusterInfo.kt#L431-L477). Independent signed-angle observations: [CarPlay HUD parser](https://github.com/KidMixer/mazda-carplay-hud/blob/main/mazda/patches/blmjcicarplay/nav.cpp).

## Logs and limits

Existing Downloads export includes source changes, TCP connection status, raw chunks with connection IDs/stream offsets, complete frame payloads as Base64, decoded state and decoding errors. Keeping full frame payloads allows offline analysis even when TCP chunks have rolled out of the bounded log. The existing 2 MiB/5,000-entry rolling policy applies. Raw metadata can contain private phone/media/navigation text. Source changes do not truncate the log; starting a fresh service session does.

A successful TCP connection is distinct from received/decoded metadata. Diagnostic counters show bytes, complete frames, active index and cache size. A second client receiving nothing does not establish whether the server is single-client or requires some other behavior. There is no automatic fallback to hide this failure: disable the switch to return to Joying and export the same session.

Validation uses synthetic wire bytes derived from the recovered field layout, the existing anonymized Joying captures, and a local TCP server exercising real split/coalesced reads through to OsmAnd fields and Android media output. It does not establish multi-client support or real angle availability on the head unit.

## Second maneuver and navigation discovery (0.3.1)

The decoder retains the explicit guidance field-13 selection list. Only its second entry can supply `no_speak_next_turn_type` and `no_speak_next_turn_angle`; it never guesses index + 1, chooses the latest packet, or skips an unsupported entry to show a later maneuver. Missing cache entries remain blank until their details arrive. A single-entry/empty selection clears the secondary output. Both arrows require a supported primary maneuver and a fresh live countdown; stop, disconnect, source changes, route-end transitions, and component changes invalidate visible secondary output. No second countdown is inferred; the existing secondary-distance field remains zero.

Historical MyAlpDroid uses the primary `next_turn_possibly_left` flag for both symbols. Secondary symbols are suppressed when their required side flag differs from the primary flag. Roundabouts require explicit valid angle/side geometry as before. Cluster rendering and multi-entry selections still need a real head-unit capture.

Decoded diagnostics include the selected indices (first 32 plus total count), guidance maneuver state and destination when present, and each maneuver's raw type, description, and start/arrival candidate label. A bounded field-ID/byte-length summary includes unknown and repeated fields; the existing raw frame payload remains authoritative for later analysis. These labels are investigative interpretations, not proof of an arrival event. Cancellation is not treated as arrival. Start/arrival mappings and full lane guidance remain disabled because the historical MyAlpDroid input translator does not expose dedicated mappings and the supplied ZLink parser has no established lane-data schema.

For the next drive, enable direct mode before starting navigation. Include consecutive turns, a motorway junction, and arrival where practical. Export soon after arrival without restarting. Since capture rolls at 2 MiB/5,000 entries, export separately after route start if preserving that event is important on a long drive.
