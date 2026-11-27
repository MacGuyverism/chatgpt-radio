# Briefsona Phase 2 — Station profiles and segments

Target version: v0.0.2  
Development branch: `feature/article-chapters-reliability`

## Purpose

Phase 2 lets users create and edit stations, configure their segment lineup, and assign sound bites. The seven page references guide the layout. Use the station’s actual name, segment count, schedule mode, and broadcast status wherever the references show example data. Keep controls for later releases visible but disabled.

## Stations and Station Profile

- **Stations** shows a card for each station with its icon, name, target duration, segment count, and production mode. Highlight the station currently loaded in Radio. A chevron at the far right of the name row indicates that tapping the card opens its profile. **Open Radio** is available on the station that owns the published feed when it has a playable edition. Station profiles without editions show that status on their cards.
- The **+** in the Stations header uses the selected illumination color and is the only create-station action. Remove the duplicate **Create new station** button at the bottom.
- **+** opens a new Station Profile in Manual mode with an editable Opening and Closing. The Profile, Segments, Schedule, and Sound bites tabs belong to that station.
- **Station Profile** includes the station name, icon, a target-duration slider from 5 to 60 minutes in one-minute steps (default 30), and editable station-wide broadcast instructions. New stations start with guidance on what changed, why it matters, attribution, uncertainty, and avoiding repetition. **Create a copy** creates an independent profile and segment lineup. The reference also shows **Delete station**. Keep it disabled until deletion of local and backed-up data is supported.
- Keep **Reporter library** and **Produce broadcast now** visible but disabled.

## Segments and Edit Segment

- **Segments** shows the lineup, enabled count, and a Fixed or Random position control on each custom row alongside its drag handle and editor chevron. Tapping a row opens **Edit Segment**. Users can add, remove, and reorder custom segments. Opening and Closing are editable and remain enabled at the first and last positions.
- New segments default to Fixed. Drag determines the saved lineup. On each run, shuffle only the slots of enabled Random segments that produced a report. Zero or one Random report keeps its place. Opening, Closing, and every Fixed report keep their places in the playable lineup. Producing an edition preserves the saved lineup. Older stations previously saved in station-wide Random mode retain that choice on their existing custom segments.
- Allow one custom segment per three minutes of target duration, up to 20. If a shorter duration would exceed that limit, show the allowed count and let the user adjust the lineup before saving. Preserve existing segments until the user chooses which to remove.
- **Edit Segment** includes the name, enabled state for custom segments, segment-specific content instructions, approximate airtime, and sound-bite assignment. Opening and Closing default to 0:30 each and can be adjusted. Enabled custom segments share the remaining seconds equally unless given a set time. Show the remaining budget during editing, exclude disabled segments, and reserve at least one second per automatic segment. Existing unequal custom weights convert to time estimates when opening older profiles.
- Keep **Reporter** and **Preferred sources** visible but disabled until their editors are available.

## Sound bites and Schedule

- **Sound bites** shows the target segment and offers **Bundled library** and **My uploads**. Users can import a device audio file, preview a clip, and assign, replace, or detach it. A failed import preserves the previous assignment. The app uses the supplied audio as-is. Omit trimming, volume, and fade controls.
- **Schedule** offers **Automatic** and **Manual**. Automatic corresponds to the saved Scheduled mode and includes **Pause schedule** and **Resume schedule**. Saving a mode in Phase 2 records the preference without starting a production job.
- Keep broadcast days, release time, and time zone controls disabled. Show a next broadcast or production start only when an actual schedule supplies it. Under Manual, keep **Produce broadcast now** and **Retry production** disabled. **Retrieve existing broadcast** remains available for a published edition.

## Later releases

- v0.0.3 covers reporter personalities and Markdown.
- v0.0.4 covers broadcast days, release times, schedule execution, and **Produce Now**.
- v0.0.5 covers visible accounts and providers.

## Saving and private backups

Each **Save** button commits the changes made on that page. **Save segment** commits the segment fields, **Save sound bite** commits its assignment, **Save changes** on Segments commits the lineup, **Save schedule** commits the mode and pause state, and **Save station** commits the profile fields. A user can return directly to Stations after saving a child page. Saving another page later must preserve those committed edits.

Save locally first, including when offline. Queue the latest committed station revision and its referenced imported audio for private Supabase backup under the installation’s anonymous identity. Show the backup state separately from broadcast production and download status.

## Radio segment card

Show horizontally scrollable tiles with the chapter number, start time, and title. Highlight the active chapter and show its details below the tiles. Tapping a tile seeks to that chapter. Use the actual chapter count in **All**.

## Implementation and verification status

- Remote feature commit [`01b2639`](https://github.com/MacGuyverism/chatgpt-radio/commit/01b2639) contains the initial v0.0.2 station editor and backup implementation. Android CI passed. Live backup verification still requires a configured test project. The seven-page redesign includes planned work beyond that commit.
- The redesigned Radio segment card exists as a local, unbuilt edit. Local feature commit `372c58e` separately addresses premature reporter sign-offs and Bluetooth chapter metadata. The intended car display is **Briefsona / Morning Edition / current chapter title**. Physical receiver verification is pending.
- The production workflow on `main` targets a **6:15 a.m. Eastern** start and a **7:00 a.m. Eastern** release. The November 26 edition ran manually, and the cause of the missed automatic run is unconfirmed. A read-only check was scheduled for November 27 at **7:30 a.m. Eastern**. Track this workflow separately from the future per-station scheduler.

## Acceptance checks

- Create a station using the header **+**, reopen it, and copy it. Editing the copy leaves the original intact.
- Edit and save a segment, return directly to Stations, restart the app, and reopen it. Verify the edit persisted without an additional **Save station**.
- Reorder custom segments, mark two nonadjacent rows Random, and verify that only those slots can swap on a run. With one Random segment, verify the saved order plays. Shorten the target duration and verify that validation preserves the lineup.
- Import and preview a sound bite, save its assignment, and verify it plays after restart. Confirm a failed replacement preserves the saved clip.
- Save Manual, Scheduled, and Scheduled (paused) states offline. Verify the latest revision backs up after reconnecting and that these actions start no production jobs.
- In Radio, scroll the chapter tiles and tap one. Verify playback seeks to the displayed start time, the active tile and details update, and **All** shows the actual chapter count.
- Confirm that later-phase controls are visibly disabled and that the Stations page has a single create action.

Development stays on the feature branch. Use `RADIO_TEST_ONLY=1` for development broadcasts. APK builds and backend production promotion require separate approval.
