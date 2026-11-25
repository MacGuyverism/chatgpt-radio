import subprocess
import re
import zipfile
from pathlib import Path

presenters = {
    "Opening headlines": ("Samantha Brooks", "nova", "Warm bright female anchor, cheerful and professional."),
    "Daily Detour": ("Samantha Brooks", "nova", "Warm playful witty female anchor."),
    "Northern Rhode Island": ("Emily Sullivan", "coral", "Friendly community-minded female correspondent."),
    "Rhode Island": ("Emily Sullivan", "coral", "Friendly community-minded female correspondent."),
    "United States": ("Michael Reynolds", "onyx", "Composed analytical impartial male reporter."),
    "World": ("Daniel Mercer", "echo", "Measured thoughtful male international correspondent."),
    "Business and markets": ("Victoria Bennett", "shimmer", "Confident energetic female business reporter."),
    "Science and technology": ("Alex Morgan", "ash", "Curious enthusiastic technology reporter."),
    "Closing": ("Samantha Brooks", "nova", "Warm bright female anchor, friendly farewell.")
}
cues = {"Opening headlines":"intro", "Daily Detour":"detour", "Northern Rhode Island":"local",
        "United States":"national", "World":"international", "Business and markets":"finance"}

def ff(*args):
    subprocess.run(["ffmpeg","-hide_banner","-loglevel","error","-y",*map(str,args)],check=True)

def duration(p):
    return float(subprocess.check_output(["ffprobe","-v","error","-show_entries","format=duration",
        "-of","default=noprint_wrappers=1:nokey=1",str(p)],text=True).strip())

def normalize(src,dst,level):
    ff("-i",src,"-af",f"loudnorm=I={level}:TP=-2:LRA=7","-ar",24000,"-ac",1,"-c:a","pcm_s16le",dst)

def prepare_cues(out):
    required = set(cues.values()) | {"outro"}
    with zipfile.ZipFile("chatgpt-radio-audio.zip") as archive:
        found = {}
        for member in archive.infolist():
            name = Path(member.filename).stem.strip().lower()
            if name == "world":
                name = "international"
            if name in required and Path(member.filename).suffix.lower() == ".mp3":
                if member.file_size > 10000000:
                    raise RuntimeError("Unexpected cue size")
                raw = out / ("cue-"+name+".mp3")
                raw.write_bytes(archive.read(member))
                normalize(raw,out / ("cue-"+name+".wav"),-19)
                found[name] = True
        if required - found.keys():
            raise RuntimeError("Missing cues: "+str(required-found.keys()))

def concat(parts,dst,out):
    listing = out / "concat.txt"
    listing.write_text("".join("file '"+p.name+"'\n" for p in parts))
    ff("-f","concat","-safe",0,"-i",listing,"-c:a","pcm_s16le",dst)

def chunks(text):
    current = ""
    for sentence in re.split(r"(?<=[.!?])\s+",text):
        if len(sentence)>2800:
            raise RuntimeError("Oversized narration sentence")
        if current and len(current)+len(sentence)+1>2800:
            yield current
            current=""
        current=(current+" "+sentence).strip()
    if current:
        yield current

def render_episode(generated,out,final,ai):
    parts=[]
    for i,(title,text) in enumerate(generated):
        name,voice,style=presenters[title]
        speech=[]
        for j,chunk in enumerate(chunks(text)):
            raw=out / f"voice-{i:02d}-{j:02d}.mp3"
            raw.write_bytes(ai("audio/speech",{"model":"gpt-4o-mini-tts","voice":voice,
                "input":chunk,"instructions":style+" Clear American English, natural pauses.",
                "response_format":"mp3"}))
            wav=raw.with_suffix(".wav")
            normalize(raw,wav,-16)
            speech.append(wav)
            print("Narrated",name,title,j+1,flush=True)
        section=out / f"section-{i:02d}.wav"
        concat(speech,section,out)
        if title in cues:
            cue=out / ("cue-"+cues[title]+".wav")
            if title=="Daily Detour":
                parts.append(cue)
            else:
                length=duration(cue)
                overlap=min(1.5,length/3)
                delay=round((length-overlap)*1000)
                mixed=out / f"mixed-{i:02d}.wav"
                ff("-i",cue,"-i",section,"-filter_complex",
                    f"[0:a]afade=t=out:st={length-overlap:.3f}:d={overlap:.3f}[m];"
                    f"[1:a]adelay={delay}:all=1[v];[m][v]amix=inputs=2:duration=longest:normalize=0,alimiter=limit=0.8",
                    "-ar",24000,"-ac",1,mixed)
                section=mixed
        parts.append(section)
    parts.append(out / "cue-outro.wav")
    master=out / "master.wav"
    concat(parts,master,out)
    length=duration(master)
    tempo=1 if 1620<=length<=1740 else length/1680
    if not 0.88<=tempo<=1.12:
        raise RuntimeError("Episode outside safe tempo adjustment range")
    ff("-i",master,"-af",f"atempo={tempo:.6f},loudnorm=I=-16:TP=-1.5:LRA=7",
        "-ar",24000,"-ac",1,"-c:a","libmp3lame","-b:a","64k",final)
    return duration(final)
