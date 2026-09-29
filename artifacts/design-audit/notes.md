# Sign-in screen review

## Audit scope

Android sign-in screen in the isolated `outboxDiagDebug` build on the API 37 emulator. Evidence: [01-login.png](01-login.png).

## User goal

Connect to the private homeserver with an administrator-provisioned Matrix account.

## Strengths

- The title and short explanation make the private-server account model clear.
- Homeserver, Matrix ID, and password are separate, clearly labeled fields; the password is masked.
- The sign-in action is prominent, and the form scrolls so it can remain usable when the keyboard is open.
- Supporting copy explains that the server address is only used to connect the account.

## UX risks and changes

- The captured debug build prefilled an emulator-only HTTP address. That is useful for local testing, but confusing and unusable in a release build. Android and iOS now retain their local loopback defaults only in debug builds; release builds start with an empty field and show the HTTPS example placeholder. A real release should be configured with the selected private-server domain before distribution.
- The sign-in screen depends on the user receiving their Matrix ID and server address from the account administrator. It does not explain where to find those values beyond the field examples.

## Accessibility risks

- The visible labels, masked password, and large full-width button are clear in the captured state.
- Screenshot review cannot confirm TalkBack/VoiceOver reading order, keyboard focus order, large-text reflow, reduced-motion behavior, or contrast under device accessibility settings. Those need device checks.

## Evidence limits

Only the Android sign-in screen was available in this audit run. No signed release, authenticated conversation, iOS screen, screen-reader session, or dynamic type/font-scale session was captured. This is not a full product accessibility audit.
