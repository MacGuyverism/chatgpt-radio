package com.macguyverism.briefsona

internal val segmentTemplates = linkedMapOf(
    "Local News" to "Cover developments affecting the listener's community.",
    "Regional News" to "Explain developments across the station's selected region.",
    "National News" to "Explain major domestic developments and their consequences.",
    "International News" to "Report significant developments around the world with regional context.",
    "Politics" to "Explain policy, elections and governance with attributed competing perspectives.",
    "Financial Markets" to "Summarize significant market moves, indicators and their verified drivers.",
    "Business News" to "Report company and industry developments and implications for workers and customers.",
    "Personal Finance" to "Explain everyday financial developments and distinguish general information from individual advice.",
    "Science" to "Report discoveries and distinguish preliminary findings from established results.",
    "Astronomy & Space" to "Cover space missions and astronomy findings with their observational limits.",
    "Technology" to "Explain technological developments and practical implications.",
    "Cybersecurity" to "Cover verified incidents, affected users and defensive guidance.",
    "Health & Medicine" to "Explain medical research, evidence quality and public health implications.",
    "Weather" to "Use current official forecasts and warnings for the requested geography.",
    "Sports" to "Cover verified results, key performances and upcoming events.",
    "Entertainment" to "Cover releases, performances and industry developments.",
    "History" to "Explain a documented historical event in context.",
    "Interesting Facts" to "Explain an unexpected, verifiable fact and why it matters.",
    "Positive News" to "Cover documented progress and its remaining limitations.",
    "Opening" to "Welcome the listener and preview the actual edition.",
    "Daily Detour" to "Tell a verified unusual story or historical curiosity with light humor.",
    "Closing" to "Conclude the edition with thanks and no new stories.",
    "Custom Segment" to "Describe the subject this segment should cover."
).mapValues { (name, focus) ->
    "## Subject\n$name\n\n## Coverage\n$focus\n\n## Research instructions\nCheck dates and consult credible sources relevant to $name." +
        "\n\n## Content priorities\nExplain the most consequential verified details first.\n\n## Exclusions\nOmit speculation and repeated stories."
}
