# Native messaging surface

**Mode:** Operate  
**Platforms:** Adaptive native UI (Android Compose and iOS SwiftUI)  
**Audience and job:** Friends in a small invited circle open the app to continue a conversation, send a short message immediately, or catch up after being offline.  
**Constraints:** Native navigation and controls on each OS; text-first MVP; keep device/security detail understandable but out of the everyday chat flow; show connectivity and delivery state without color alone; future media must fit this visual system.  
**Open decisions:** Product name and logo are not set. No brand assets were supplied.

## Direction contract

**THESIS:** Make a familiar private conversation feel like an open line to a few known people. Refuse the generic inbox of identical floating cards and noisy online-status badges.

**OWN-WORLD:** Borrow the visual grammar of a community radio cue sheet: clear named lines, strong reading order, compact timing marks, and a restrained signal color for state. Keep platform typography and native controls; never turn the chat into a control panel.

**STORY:** A conversation is ready, messages appear on the sender's device at once, and a quiet labeled state explains when delivery is waiting or complete. People can talk without learning the transport details.

**FIRST VIEWPORT:** A plain-language sign-in leads to a conversation list with names, the latest message, time, and one concise delivery/unread cue. Entering a chat places the person's name and connection state above a full-height timeline; the composer stays anchored to the keyboard-safe bottom edge.

**FORM:** Community radio cue sheet, grounded candidate 7 of 7; assigned by concept seed `2e9d8be1`. Native Material/HIG structure carries the interface. Keep from the one-bit challenger: binary send-state clarity. Keep from the night-flight challenger: one glanceable connection cue. Keep from the paper-fold challenger: reversible, restrained motion. Keep from the theater-light challenger: label states as well as tinting them. Keep from the hardware challenger: subtle haptics on meaningful actions. Keep from the vertical-media challenger: media opens deliberately and does not hijack the chat timeline.

**FINISH:** unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
