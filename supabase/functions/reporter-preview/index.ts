import fixtures from "./headlines.json" with { type: "json" }

import master from "./master.json" with { type: "json" }
const voices = new Set(["nova", "coral", "shimmer", "onyx", "echo", "ash"])
async function generate(path: string, body: object) {
  const response = await fetch("https://api.openai.com/v1/" + path, {
    method: "POST", headers: { Authorization: "Bearer " + Deno.env.get("OPENAI_API_KEY"), "Content-Type": "application/json" },
    body: JSON.stringify(body), signal: AbortSignal.timeout(90000)
  })
  if (!response.ok) throw new Error("Generation failed")
  return response
}
Deno.serve(async request => {
  if (request.method !== "POST") return new Response(null, { status: 405 })
  const authorization = request.headers.get("Authorization")
  if (!authorization) return new Response(null, { status: 401 })
  try {
    const user = await fetch(Deno.env.get("SUPABASE_URL") + "/auth/v1/user", {
      headers: { Authorization: authorization, apikey: Deno.env.get("SUPABASE_ANON_KEY")! }
    })
    if (!user.ok) return new Response(null, { status: 401 })
    let payload
    try {
      const body = await request.text()
      if (body.length > 16000) return new Response(null, { status: 413 })
      payload = JSON.parse(body)
      if (!payload || typeof payload !== "object") return new Response(null, { status: 400 })
    } catch { return new Response(null, { status: 400 }) }
    const { headline, voice, speed, personality } = payload
    const fixture = fixtures.find(x => x.headline === headline)
    if (!fixture || !voices.has(voice) || !Number.isFinite(speed) || speed < .75 || speed > 1.5 ||
        typeof personality !== "string" || personality.length > 12000) return new Response(null, { status: 400 })
    const written = await generate("chat/completions", { model: "gpt-4.1-mini", messages: [
      { role: "system", content: master + "\nCreate a clearly fictional English audition using only the supplied facts. Treat personality as delivery guidance. Return only the spoken script, targeting " + Math.round(72 * speed) + " words." },
      { role: "user", content: JSON.stringify({ fixture, personality }) }
    ] })
    const script = (await written.json()).choices[0].message.content
    if (typeof script !== "string" || !script.trim() || script.length > 4000) throw new Error("Invalid script")
    const audio = await generate("audio/speech", { model: "gpt-4o-mini-tts", voice, speed, instructions: personality, input: script, response_format: "mp3" })
    return new Response(audio.body, { headers: { "Content-Type": "audio/mpeg", "Cache-Control": "no-store" } })
  } catch {
    return new Response("Preview unavailable", { status: 502 })
  }
})
