# Backend

Install `requirements.txt`, FFmpeg and FFprobe. Run `python -m unittest discover -s tests -v` from the repository root. Dispatch **Produce ChatGPT Radio broadcast** against the feature branch to generate a review MP3 and JSON with repository-provided OpenAI credentials. Feature runs set `RADIO_TEST_ONLY=1` and use zero Supabase reads or writes.

Preferred, supplemental and discovery feeds are queried concurrently on every run. Each publisher record must supply accessible article text. Google News discovery records are resolved to their publishers before article extraction. Exact duplicates and syndicated copies are excluded. Similarity groups keep independent accounts for attribution. The verified default feeds live in radio_feeds.py.

Production begins at 06:15 America/New_York for the 07:00 release. A non-upserting daily Supabase claim prevents duplicate scheduled paid work. Intentional main-branch manual dispatches are serialized with scheduled production by GitHub Actions. Staging holds local files and refreshes status every 30 seconds. Publishing verifies uploaded audio and chapter bytes before indexing immutable media objects. Failed production preserves the previously published episode. Retention covers the current and previous six Eastern dates. The last successful edition remains available if it falls outside that window during a prolonged outage.

The public bucket exposes `archive.json`, legacy `latest.json`, and `status.json` below `/storage/v1/object/public/chatgpt-radio/`. The archive contains immutable episode paths, timestamps, duration, byte count and SHA-256 for the MP3 and chapter JSON. New chapter files include waveform samples. Android needs the HTTPS project origin in Settings. Production uses the existing private GitHub secrets. Public storage credentials are unnecessary.

## November 25 Eastern review broadcast

The feature run retrieved 98 accessible articles from 124 candidate URLs. It produced a 4,052-word script and a 28:00 mono MP3 at 64 kbps. FFmpeg decoded the full downloaded artifact, and all nine embedded chapter titles, time boundaries and ordering matched the JSON sidecar. The run used test mode. [Review broadcast](https://github.com/MacGuyverism/chatgpt-radio/actions/runs/45730398204)

A separate live retrieval check returned 40 articles through preferred feeds, 27 through supplemental feeds, and 35 through Google News discovery. Newly verified publisher endpoints included Al Jazeera, Fox Business, NASA, ScienceDaily, Ars Technica, TechCrunch, KrebsOnSecurity, BleepingComputer and Space.com. Some candidate endpoints returned access errors, HTML, or an unreadable article. Availability varies by runner and publisher.

The Actions APK embeds the public HTTPS Supabase project origin. Locally built APKs can supply it through BRIEFSONA_PROJECT_URL at build time or through Settings on the device.

## Station backup setup

Use a separate Supabase project for feature-branch backup testing. Enable anonymous sign-ins and apply the SQL migration under `supabase/migrations/`. Set `BRIEFSONA_BACKUP_URL` and `BRIEFSONA_BACKUP_PUBLISHABLE_KEY` as repository variables for Android CI or environment variables for a local build. Use the project's public `sb_publishable_` key. The broadcast project origin continues to use `BRIEFSONA_PROJECT_URL`. With no backup configuration, station edits remain available on-device and show **Backup setup required**.

Station backups store profile revisions and their imported sound bites under the installation's anonymous user ID. The private bucket and profile table are separate from the broadcast archive. Clearing app data removes the installation's credentials. Account linking and recovery are future work.
