package eu.youforgemax.thumb

/**
 * Turns a freeform description into an [OverlaySpec].
 * Implementations: [OnDeviceLlm] (Gemma-2 2B on the tablet) and [TemplateProvider]
 * (offline keyword heuristics, always available).
 */
interface AiProvider {
    suspend fun suggest(description: String): OverlaySpec
}

/** Shared instruction the LLM must follow. The renderer only needs these fields. */
object Prompts {
    fun overlay(description: String): String = """
You design punchy YouTube thumbnail text overlays. Read the creator's description and
reply with ONLY a single JSON object, no prose, no markdown fences.

Schema:
{"title":"<<=4 WORDS, UPPERCASE, punchy hook>","subtitle":"<short, optional>","title_color":"#RRGGBB","stroke_color":"#RRGGBB","position":"upper-left|upper-center|upper-right|center|lower-left|lower-center|lower-right","mood":"<one word>","accent":"<single emoji or empty>","effect":"shadow|outline|glow|neon|gradient|pop","glow_color":"#RRGGBB"}

Rules:
- title MUST be 4 words or fewer and feel like a clickable hook.
- title_color and stroke_color MUST be high contrast against each other.
- keep subtitle very short (or empty).
- effect: pick the style that fits the mood — "neon"/"glow" for night/epic/gaming,
  "pop" for loud hype, "gradient" for premium/cinematic, "outline" for clean daytime.
- glow_color: a vivid colour for the glow/neon halo (only matters for those effects).

Description: "$description"
JSON:
""".trim()
}
