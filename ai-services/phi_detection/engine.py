"""Presidio-based redaction engine."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from portal_common.schemas import ModelInfo

# Entities we redact. DATE_TIME is deliberately absent: Presidio's built-in date recognizer would
# also remove "yesterday" or "for 3 days", which urgency flagging needs. Dates are redacted when
# they look like a date of birth (see recognizers.dob_recognizer).
ENTITIES = [
    "PERSON",
    "LOCATION",
    "PHONE_NUMBER",
    "EMAIL_ADDRESS",
    "MRN",
    "DATE_OF_BIRTH",
    "STREET_ADDRESS",
    "US_SSN",
]
SCORE_THRESHOLD = 0.4

# spaCy sometimes tags salutations and role words as PERSON ("Hi Doc,"). These are not identifiers.
PERSON_DENY_LIST = {
    "doc", "doctor", "dr", "dr.", "nurse", "md", "np", "pa", "sir", "madam", "hi", "hello", "hey",
    "thanks", "thank you", "regards", "best", "sincerely",
}

SPACY_MODEL = "en_core_web_lg"
NER_LABELS_TO_IGNORE = [
    "CARDINAL", "ORDINAL", "QUANTITY", "MONEY", "PERCENT", "TIME", "DATE", "EVENT", "LAW", "LANGUAGE",
    "PRODUCT", "WORK_OF_ART", "FAC", "NORP", "ORG",
]


@dataclass(frozen=True)
class Span:
    entity_type: str
    start: int
    end: int
    score: float


@dataclass(frozen=True)
class Redaction:
    redacted_text: str
    spans: list[Span]


class PhiEngine:
    def __init__(self, analyzer: Any, anonymizer: Any, model: ModelInfo):
        self._analyzer = analyzer
        self._anonymizer = anonymizer
        self._model = model

    def model_info(self) -> ModelInfo:
        return self._model

    def redact(self, text: str) -> Redaction:
        from presidio_anonymizer.entities import OperatorConfig

        results = self._analyzer.analyze(
            text=text, language="en", entities=ENTITIES, score_threshold=SCORE_THRESHOLD
        )
        results = drop_false_positives(text, results)
        # "replace" with no new_value yields "<ENTITY_TYPE>". Overlaps are resolved by the anonymizer.
        anonymized = self._anonymizer.anonymize(
            text=text, analyzer_results=results, operators={"DEFAULT": OperatorConfig("replace")}
        )
        spans = sorted(
            (Span(r.entity_type, r.start, r.end, round(float(r.score), 3)) for r in results),
            key=lambda s: (s.start, s.end),
        )
        return Redaction(redacted_text=anonymized.text, spans=spans)


def drop_false_positives(text: str, results: list[Any]) -> list[Any]:
    kept = []
    for r in results:
        value = text[r.start : r.end].strip().strip(",.!:;").lower()
        if r.entity_type == "PERSON" and (value in PERSON_DENY_LIST or not any(c.isalpha() for c in value)):
            continue
        kept.append(r)
    return kept


def load_engine() -> PhiEngine:
    import spacy
    from presidio_analyzer import AnalyzerEngine, RecognizerRegistry
    from presidio_analyzer.nlp_engine import NlpEngineProvider
    from presidio_analyzer.predefined_recognizers import SpacyRecognizer, UsSsnRecognizer
    from presidio_anonymizer import AnonymizerEngine

    from .recognizers import custom_recognizers

    nlp_engine = NlpEngineProvider(
        nlp_configuration={
            "nlp_engine_name": "spacy",
            "models": [{"lang_code": "en", "model_name": SPACY_MODEL}],
            # Only PERSON and LOCATION are used from NER; silence the rest instead of mapping them.
            "ner_model_configuration": {"labels_to_ignore": NER_LABELS_TO_IGNORE},
        }
    ).create_engine()

    registry = RecognizerRegistry(supported_languages=["en"])
    registry.add_recognizer(SpacyRecognizer(supported_language="en", supported_entities=["PERSON", "LOCATION"]))
    registry.add_recognizer(UsSsnRecognizer())
    for recognizer in custom_recognizers():
        registry.add_recognizer(recognizer)

    analyzer = AnalyzerEngine(nlp_engine=nlp_engine, registry=registry, supported_languages=["en"])
    analyzer.analyze(text="Warm-up call for Maria at (408) 555-0100.", language="en", entities=ENTITIES)

    model_version = spacy.util.get_package_version(SPACY_MODEL)
    return PhiEngine(
        analyzer,
        AnonymizerEngine(),
        ModelInfo(
            name=f"presidio-analyzer + {SPACY_MODEL}",
            revision=model_version,
            detail="custom regex: phone, email, MRN, DOB, street address",
        ),
    )
