"""Red-flag phrase rules for demonstration-only urgency flags.

NOT validated clinical triage. Phrases are matched case-insensitively on tokens; negated mentions
("no chest pain", "denies shortness of breath") are ignored. Decision:
  any non-negated URGENT phrase -> "urgent"
  else any non-negated SOON phrase -> "soon"
  else -> "routine"

A breathing phrase in the same sentence as a nasal cue ("stuffy", "through my nose") is downgraded
to SOON (see contextual_tier).

Known limitations (documented, not fixed): hypotheticals ("if I get chest pain") and family history
("my dad had a stroke") still match, and phrases outside the list are missed.
"""

from __future__ import annotations

from dataclasses import dataclass

RULESET_VERSION = "2026.10-2"

URGENT = "URGENT"
SOON = "SOON"

URGENT_PHRASES = [
    # Cardiopulmonary
    "chest pain", "chest pains", "chest pressure", "chest tightness", "tightness in my chest",
    "pressure in my chest", "heart attack", "shortness of breath", "short of breath",
    "trouble breathing", "difficulty breathing", "hard to breathe", "can't breathe", "cannot breathe",
    "can not breathe", "struggling to breathe", "gasping", "lips turning blue", "lips are blue",
    "coughing up blood", "heart racing", "heart is racing",
    # Neurological
    "stroke", "slurred speech", "slurring my words", "face drooping", "facial droop",
    "face is drooping", "numbness on one side", "weakness on one side", "can't move my arm",
    "can't move my leg", "worst headache", "worst headache of my life", "sudden severe headache",
    "seizure", "seizures", "passed out", "fainted", "fainting", "lost consciousness", "unconscious",
    "unresponsive", "sudden confusion", "vision loss", "lost my vision",
    "sudden blindness",
    # Mental health crisis
    "suicidal", "suicide", "kill myself", "end my life", "want to die", "hurt myself",
    "harm myself", "self harm", "self-harm", "overdose", "overdosed", "took too many pills",
    # Bleeding, allergy, other emergencies
    "severe bleeding", "bleeding heavily", "won't stop bleeding", "can't stop the bleeding",
    "vomiting blood", "throwing up blood", "blood in my vomit", "black stool", "black stools",
    "tarry stool", "anaphylaxis", "throat swelling", "throat is swelling", "throat closing",
    "tongue swelling", "swollen tongue", "severe allergic reaction", "severe abdominal pain",
    "severe stomach pain", "stiff neck and fever", "fever and stiff neck", "testicular pain",
    "pregnant and bleeding", "bleeding while pregnant",
]

SOON_PHRASES = [
    "fever", "high fever", "fevers", "chills", "infection", "infected", "pus", "spreading rash",
    "rash is spreading", "rash", "hives", "swelling", "swollen", "vomiting", "throwing up",
    "can't keep anything down", "can't keep food down", "diarrhea", "dehydrated", "dizzy",
    "dizziness", "lightheaded", "light-headed", "blood in my urine", "blood in urine",
    "burning when i pee", "burning urination", "painful urination", "uti", "getting worse",
    "worsening", "worse than before", "severe pain", "a lot of pain", "pain is unbearable",
    "new lump", "a lump", "palpitations", "out of my medication", "ran out of my medication",
    "ran out of my", "out of insulin", "no more pills", "missed doses", "side effects",
    "side effect", "allergic reaction", "wound", "stitches", "fell", "sprained", "migraine",
    "blurry vision", "blurred vision", "ear pain", "wheezing",
]


# Breathing red flags that, next to a nasal cue in the same sentence, describe congestion ("trouble
# breathing through my nose, it's all stuffed up") rather than respiratory distress. Seen in the
# seeded data; those flags are downgraded to SOON.
BREATHING_PHRASES = {
    "shortness of breath", "short of breath", "trouble breathing", "difficulty breathing", "hard to breathe",
    "can't breathe", "cannot breathe", "can not breathe", "struggling to breathe",
}
NASAL_CUES = ("nose", "nasal", "stuffy", "stuffed", "congested", "congestion", "sinus")
NASAL_NOTE = "downgraded: nasal context"


@dataclass(frozen=True)
class Flag:
    phrase: str
    tier: str
    negated: bool
    start: int
    end: int
    note: str | None = None


def contextual_tier(phrase: str, tier: str, sentence: str) -> tuple[str, str | None]:
    if tier == URGENT and phrase in BREATHING_PHRASES and any(c in sentence.lower() for c in NASAL_CUES):
        return SOON, NASAL_NOTE
    return tier, None


def decide(flags: list[Flag]) -> str:
    active = {f.tier for f in flags if not f.negated}
    if URGENT in active:
        return "urgent"
    if SOON in active:
        return "soon"
    return "routine"


def ruler_patterns() -> list[dict]:
    """EntityRuler phrase patterns. Longer phrases are listed first so they win overlaps."""
    patterns = [{"label": URGENT, "pattern": p} for p in URGENT_PHRASES]
    patterns += [{"label": SOON, "pattern": p} for p in SOON_PHRASES if p not in URGENT_PHRASES]
    return sorted(patterns, key=lambda p: -len(p["pattern"]))
