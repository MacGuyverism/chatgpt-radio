# Briefsona v0.0.2 Phase 2 handoff

This is a documentation handoff for `main`. Android and backend development stay on `feature/article-chapters-reliability`. APK builds and production promotion each require separate approval.

## Stations and Station Profile

- The header **+** is the only create-station action. Remove the duplicate button at the bottom.
- Tapping a station opens its profile. **Load station** selects a playable station for Radio.
- New stations start in **Manual** mode with editable Opening and Closing segments. Station copies can be edited independently.

## Segments

- Use a lineup page and a separate editor for each segment. Opening and Closing stay enabled and pinned to the first and last positions.
- Custom segments support enabling, disabling, reordering, content instructions, and relative duration weights such as 1.5×.
- Preserve the manual sequence when switching between **Fixed** and **Random**.
- Target duration is 5–60 minutes. Allow up to one custom segment per three minutes, capped at 20.

## Sound bites

Users can select, import, preview, replace, and detach clips. Audio editing stays outside the app. Omit volume, trimming, and fade controls.

## Scheduling and later versions

- Persist **Manual**, **Scheduled**, and **Scheduled (paused)**. Show **Pause** or **Resume** in the applicable state.
- Use the seven mockups as layout references. Disable controls for features awaiting implementation.
- v0.0.3 covers reporter personalities and Markdown.
- v0.0.4 covers broadcast days, release times, schedule execution, and **Produce Now**.
- v0.0.5 covers visible accounts and providers.

## Save behavior

Each page’s **Save** commits its own changes immediately on-device. Saving a segment or sound bite requires no second save on Station Profile. Queue the committed profile and imported audio for private Supabase backup under the installation’s anonymous identity.

## Radio segment card

Show horizontally scrollable tiles with the chapter number, start time, and title. Highlight the active chapter and show its details below the tiles. Tapping a tile seeks to that chapter. Use the actual chapter count in **All**.

## Implementation and verification status

- Remote feature commit [`01b2639`](https://github.com/MacGuyverism/chatgpt-radio/commit/01b2639) contains the initial v0.0.2 station editor and backup implementation. Android CI passed. Live backup verification still requires a configured test project. The seven-page redesign includes planned work beyond that commit.
- The redesigned Radio segment card exists as a local, unbuilt edit. Local feature commit `372c58e` separately addresses premature reporter sign-offs and Bluetooth chapter metadata. The intended car display is **Briefsona / Morning Edition / current chapter title**. Physical receiver verification is pending.
- The production workflow on `main` targets a **6:15 a.m. Eastern** start and a **7:00 a.m. Eastern** release. The November 26 edition ran manually, and the cause of the missed automatic run is unconfirmed. A read-only check was scheduled for November 27 at **7:30 a.m. Eastern**. Track this workflow separately from the future per-station scheduler.
