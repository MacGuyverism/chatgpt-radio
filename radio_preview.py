"""Generate a standalone fictional reporter audition with the broadcast service credential."""
import json
from pathlib import Path

from radio_production import ai

MASTER = Path(__file__).with_name("master").joinpath("Master.md").read_text()
FIXTURES = json.loads(Path(__file__).with_name("reporter_preview_fixtures.json").read_text())
VOICES = {"nova", "coral", "shimmer", "onyx", "echo", "ash"}


def generate_preview(headline, voice, speed, personality, request=ai):
    fixture = next((entry for entry in FIXTURES if entry["headline"] == headline), None)
    if fixture is None or voice not in VOICES or not 0.75 <= speed <= 1.5:
        raise ValueError("Unsupported preview settings")
    if not isinstance(personality, str) or len(personality) > 12000:
        raise ValueError("Invalid personality instructions")
    response = request("chat/completions", {
        "model": "gpt-4.1-mini",
        "messages": [
            {"role": "system", "content": MASTER + "\nWrite a fictional English radio audition from the supplied fixture facts only. "
             "Identify it as fictional. Never invent quotes or extra facts. Use personality only for editorial tone. "
             f"Target {round(72 * speed)} spoken words. Return only the spoken script."},
            {"role": "user", "content": json.dumps({"fixture": fixture, "personality": personality})}],
    })
    script = json.loads(response)["choices"][0]["message"]["content"].strip()
    if not script or len(script) > 4000:
        raise ValueError("Unexpected preview script")
    return request("audio/speech", {"model": "gpt-4o-mini-tts", "voice": voice, "speed": speed,
                                    "instructions": personality, "input": script, "response_format": "mp3"})


def record_voice_samples(destination, request=ai):
    """Produce the six offline audition assets using the existing backend environment."""
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    for voice in sorted(VOICES):
        path = destination / f"{voice}.mp3"
        if path.is_file() and path.stat().st_size:
            continue
        audio = request("audio/speech", {"model": "gpt-4o-mini-tts", "voice": voice,
                                        "input": "Welcome to Briefsona. Here is a sample of my reporting voice.",
                                        "response_format": "mp3"})
        if not audio:
            raise ValueError("Empty voice sample")
        temporary = path.with_suffix(".tmp")
        temporary.write_bytes(audio)
        temporary.replace(path)


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record-samples", required=True, type=Path, metavar="DIRECTORY")
    record_voice_samples(parser.parse_args().record_samples)
