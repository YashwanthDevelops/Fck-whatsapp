# Native app direction notes

These notes record how the seed assignment was applied to the supplied product facts. The UI follows Android Material 3 and iOS Human Interface Guidelines; the visual source shapes presentation and status cues, not platform navigation or controls.

## Grounded directions considered

1. **Kitchen-table field notebook** — fits everyday catching up, quick drafts, and returning to a conversation after time away; familiar, but risks becoming a generic warm journal.
2. **Mixtape liner notes** — carries the friends' voice and later media sharing; memorable, but could over-emphasize audio before media exists.
3. **Photo contact sheet** — reinforces a known small circle and gives media room later; weak for a text-first MVP.
4. **Transit departure board** — makes waiting, arrival, and reconnect states immediately legible; risks making the product feel like a utility dashboard.
5. **Postcard exchange** — signals a personal circle and recognizable sender identity; its slow correspondence metaphor fights the instant-send requirement.
6. **Library margin annotations** — gives replies and reactions a natural visual logic; its reading-room setting makes it less suited to short everyday conversation.
7. **Community radio cue sheet** — connects familiar voices with a clear, low-noise signal for waiting, delivery, and reconnect states. The seed assigned candidate 7 (`2e9d8be1`), so this is the direction carried forward.

The strongest grounded alternate is the kitchen-table field notebook. It is familiar and approachable, though less distinctive. The assigned radio cue sheet must stay human and conversational; its controls never become a dashboard.

## Seed challengers

| Challenger | Verdict on audience identification and product clarity | Discipline retained |
|---|---|---|
| Full-bleed vertical media feed | Declined: a media feed does not fit the friend-to-friend text task or explain reliable delivery. | Media opens deliberately from a conversation instead of taking over the viewport. |
| Creator hardware bench | Declined: the control surface speaks to makers rather than friends and overstates technical operation. | Subtle haptics confirm meaningful actions such as sending. |
| One-bit desktop | Declined: the retro desktop is not a natural social setting and cannot express a rich message/media timeline accessibly. | Sending, delivered, and failed are distinct, legible states. |
| Night-flight instrument panel | Declined: cockpit gauges make ordinary messaging feel specialist and shift focus from conversations to system telemetry. | One concise connection state remains visible. |
| Theater-lighting cyclorama | Declined: theatrical light does not identify the friend-group use case, though it can clarify transitions. | State changes use restrained motion and labeled cues; tint never carries meaning alone. |
| Origami folding sequence | Declined: a step-driven paper transformation adds attention and friction to a task that should be immediate. | Reversible, reduced-motion transitions keep the UI responsive. |

## Session choice

The product brief and the user's instruction to proceed without questions provide enough context to move ahead. This is a code-first native build; no preference is stored in Impeccable configuration.
