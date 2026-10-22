"""Custom Presidio recognizers for the PHI the portal is likely to see.

The brief calls for custom regex recognizers for phone, email, MRN and DOB, so those replace
Presidio's built-ins. A street-address recognizer is added because the synthetic identities include
addresses. Names and places come from spaCy NER (``en_core_web_lg``) via Presidio's SpacyRecognizer.

Scores: a pattern that is unambiguous on its own scores at or above the analyzer threshold (0.4).
Ambiguous patterns (a bare 6-10 digit number, a date) score below it and only pass when context
words such as "MRN" or "born" appear within five words before; Presidio's context enhancer adds
0.35.

Context words are compared one token at a time against spaCy *lemmas*, so they must be single
lowercase lemmas: "record", not "record number"; and "bear", because spaCy lemmatises "born" to
"bear".
"""

import re

from presidio_analyzer import Pattern, PatternRecognizer

# Presidio applies IGNORECASE by default. Patterns that rely on capitalisation opt out of it.
CASE_SENSITIVE = re.DOTALL | re.MULTILINE

_MONTH = r"(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|June?|July?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"

_STREET_SUFFIX = (
    r"(?:St|Street|Ave|Avenue|Rd|Road|Dr|Drive|Ln|Lane|Ct|Court|Way|Blvd|Boulevard|Pl|Place|Ter|Terrace"
    r"|Cir|Circle|Pkwy|Parkway|Hwy|Highway|Bend|Loop|Trl|Trail|Sq|Square)"
)


def phone_recognizer() -> PatternRecognizer:
    return PatternRecognizer(
        supported_entity="PHONE_NUMBER",
        name="UsPhoneRegexRecognizer",
        patterns=[
            Pattern("phone_parens", r"\(\d{3}\)\s?\d{3}[-.\s]\d{4}\b", 0.75),
            Pattern("phone_intl", r"\+1[-.\s]?\d{3}[-.\s]?\d{3}[-.\s]?\d{4}\b", 0.75),
            Pattern("phone_separated", r"\b\d{3}[-.]\d{3}[-.]\d{4}\b", 0.65),
            # Space-separated digits are often something else (dosages, ids); need context.
            Pattern("phone_spaces", r"\b\d{3}\s\d{3}\s\d{4}\b", 0.3),
        ],
        context=["phone", "call", "cell", "mobile", "number", "reach", "text", "contact"],
    )


def email_recognizer() -> PatternRecognizer:
    return PatternRecognizer(
        supported_entity="EMAIL_ADDRESS",
        name="EmailRegexRecognizer",
        patterns=[Pattern("email", r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b", 0.9)],
    )


def mrn_recognizer() -> PatternRecognizer:
    return PatternRecognizer(
        supported_entity="MRN",
        name="MrnRegexRecognizer",
        patterns=[
            Pattern("mrn_prefixed", r"\bMRN[-:#\s]*\d{6,10}\b", 0.95),
            Pattern("mrn_digits", r"\b\d{6,10}\b", 0.2),
        ],
        context=["mrn", "record", "chart"],
    )


def dob_recognizer() -> PatternRecognizer:
    return PatternRecognizer(
        supported_entity="DATE_OF_BIRTH",
        name="DobRegexRecognizer",
        patterns=[
            Pattern("date_iso", r"\b(?:19|20)\d{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\d|3[01])\b", 0.35),
            Pattern("date_us", r"\b(?:0?[1-9]|1[0-2])/(?:0?[1-9]|[12]\d|3[01])/(?:19|20)?\d{2}\b", 0.35),
            Pattern("date_long", rf"\b{_MONTH}\.?\s+\d{{1,2}}(?:st|nd|rd|th)?,?\s+(?:19|20)\d{{2}}\b", 0.35),
            Pattern("date_long_dmy", rf"\b\d{{1,2}}(?:st|nd|rd|th)?\s+(?:of\s+)?{_MONTH}\.?,?\s+(?:19|20)\d{{2}}\b", 0.35),
        ],
        context=["dob", "d.o.b", "born", "bear", "birth", "birthday", "birthdate"],
    )


def street_address_recognizer() -> PatternRecognizer:
    # "1420 Alder Ln, San Jose, CA 95112": number, 1-3 capitalised words, suffix, optional city/state/zip.
    pattern = (
        rf"\b\d{{1,5}}\s+(?:[A-Z][a-z]+\s+){{1,3}}{_STREET_SUFFIX}\b\.?"
        r"(?:,?\s+(?:Apt|Unit|Suite|#)\s*\w+)?"
        r"(?:,\s*[A-Z][a-z]+(?:\s+[A-Z][a-z]+){0,2})?"
        r"(?:,\s*[A-Z]{2})?(?:\s+\d{5}(?:-\d{4})?)?"
    )
    return PatternRecognizer(
        supported_entity="STREET_ADDRESS",
        name="StreetAddressRegexRecognizer",
        patterns=[Pattern("street_address", pattern, 0.75)],
        context=["live", "address", "moved", "mail", "send", "home"],
        global_regex_flags=CASE_SENSITIVE,
    )


def custom_recognizers() -> list[PatternRecognizer]:
    return [phone_recognizer(), email_recognizer(), mrn_recognizer(), dob_recognizer(), street_address_recognizer()]
