"""spaCy pipeline: tokenizer + parser (sentence boundaries) + EntityRuler (red-flag phrases) + negex."""

from __future__ import annotations

from typing import Any

from portal_common.schemas import ModelInfo

from .rules import RULESET_VERSION, SOON, URGENT, Flag, contextual_tier, decide, ruler_patterns

SPACY_MODEL = "en_core_web_sm"


class UrgencyEngine:
    def __init__(self, nlp: Any, model: ModelInfo):
        self._nlp = nlp
        self._model = model

    def model_info(self) -> ModelInfo:
        return self._model

    def flags(self, text: str) -> list[Flag]:
        doc = self._nlp(text, disable=["negex"])
        flags = []
        for sent in doc.sents:
            # negex runs per sentence so "No fever. Chest pain since noon." does not carry the
            # first sentence's "No" over to "chest pain".
            sent_doc = sent.as_doc()
            for ent in self._negex(sent_doc).ents:
                if ent.label_ in (URGENT, SOON):
                    phrase = ent.text.lower()
                    tier, note = contextual_tier(phrase, ent.label_, sent.text)
                    flags.append(
                        Flag(
                            phrase=phrase,
                            tier=tier,
                            negated=bool(ent._.negex),
                            start=sent.start_char + ent.start_char,
                            end=sent.start_char + ent.end_char,
                            note=note,
                        )
                    )
        return flags

    def _negex(self, doc: Any) -> Any:
        return self._nlp.get_pipe("negex")(doc)

    def classify(self, text: str) -> tuple[str, list[Flag]]:
        flags = self.flags(text)
        return decide(flags), flags


# Patient-message phrasing that ends a negation's scope, on top of negspaCy's clinical termset:
# "no chest pain, just a mild fever" must not negate "fever".
EXTRA_TERMINATIONS = ["just", "only", "other than", "except for", "now", "and now", "but now"]


def build_pipeline(nlp: Any) -> Any:
    """Adds the red-flag EntityRuler and negex to a loaded pipeline that has a parser."""
    import negspacy.negation  # noqa: F401  (registers the "negex" factory)
    from negspacy.termsets import termset

    ruler = nlp.add_pipe("entity_ruler", config={"phrase_matcher_attr": "LOWER", "overwrite_ents": True})
    ruler.add_patterns(ruler_patterns())
    terms = termset("en_clinical")
    terms.add_patterns({"termination": EXTRA_TERMINATIONS})
    nlp.add_pipe("negex", config={"ent_types": [URGENT, SOON], "neg_termset": terms.get_patterns()})
    return nlp


def load_engine() -> UrgencyEngine:
    import spacy

    # NER is excluded: the only entities we want are the ruler's red-flag phrases.
    nlp = build_pipeline(spacy.load(SPACY_MODEL, exclude=["ner"]))
    return UrgencyEngine(
        nlp,
        ModelInfo(
            name=f"rules {RULESET_VERSION} + negspacy + {SPACY_MODEL}",
            revision=spacy.util.get_package_version(SPACY_MODEL),
            detail="demonstration-only review flags, not validated clinical triage",
        ),
    )
