"""Utilidades de texto compartidas (español e inglés): normalización, tokens, stems, idioma y frases.

Son deliberadamente simples y deterministas: alimentan los embeddings por hashing, el ranking
léxico, la validación de respuestas y los clasificadores locales.
"""

import re
import unicodedata

_WORD = re.compile(r"[a-z0-9]+(?:[.,][0-9]+)*")
_SENTENCE_END = re.compile(r"(?<=[.!?¡¿])\s+|\n+")
# Importes y cifras: "$ 45.000", "30%", "1,5", "48 horas" -> se comparan solo los dígitos
_NUMBER = re.compile(r"\d+(?:[.,]\d+)*")

STOPWORDS_ES = frozenset(
    """a al algo algun alguna algunas alguno algunos ante antes aqui asi aun cada como con contra cual
    cuales cuando de del desde donde dos el ella ellas ellos en entre era eran es esa esas ese eso esos
    esta estas este esto estos fue fueron ha hace hacer hasta hay la las le les lo los mas me mi mis mucho
    muy nada ni no nos nosotros o os otra otras otro otros para pero poco por porque que quien se sea ser
    si sin sobre solo son su sus tambien te tengo tiene tienen todo todos tu tus un una unas uno unos usted
    ustedes y ya yo quiero puedo puede favor gracias hola buenas buenos dias tardes noches necesito""".split()
)
STOPWORDS_EN = frozenset(
    """a about after all also am an and any are as at be been but by can could did do does for from get
    had has have he her his how i if in into is it its just me my no not of on or our please so some than
    that the their them then there these they this to too up us was we were what when where which who why
    will with would you your hello hi thanks thank need want""".split()
)
STOPWORDS = STOPWORDS_ES | STOPWORDS_EN

# Palabras muy frecuentes que delatan el idioma (no se eliminan en otros usos)
_LANG_HINTS_ES = frozenset(
    "el la los las de que y en un una por para con no es mi mis como pero cuando donde esta estoy tengo "
    "quiero puedo hola gracias porque pedido cuanto".split()
)
_LANG_HINTS_EN = frozenset(
    "the and of to is in it my i you for with not how when where what can this that have want hello "
    "thanks order please".split()
)


def strip_accents(text: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn")


def normalize(text: str) -> str:
    """Minúsculas y sin tildes: 'Devolución' y 'devolucion' deben coincidir."""
    return strip_accents(text.lower())


def tokens(text: str) -> list[str]:
    return _WORD.findall(normalize(text))


def stem(token: str) -> str:
    """Stemming muy ligero por prefijo: agrupa plurales y derivadas ('reembolso', 'reembolsos',
    'reembolsar') sin depender de un stemmer por idioma."""
    if token.isdigit() or len(token) <= 4:
        return token
    return token[:6]


def content_stems(text: str) -> list[str]:
    """Stems de las palabras con contenido (sin stopwords), en orden de aparición."""
    return [stem(t) for t in tokens(text) if t not in STOPWORDS and len(t) > 1]


def detect_language(text: str, default: str = "es") -> str:
    """Español o inglés según las palabras funcionales más frecuentes. Suficiente para elegir el
    idioma de la respuesta; ante la duda, el idioma por defecto."""
    words = tokens(text)
    es = sum(1 for w in words if w in _LANG_HINTS_ES)
    en = sum(1 for w in words if w in _LANG_HINTS_EN)
    if en > es:
        return "en"
    if es > en:
        return "es"
    if re.search(r"[ñáéíóú¿¡]", text.lower()):
        return "es"
    return default


def split_sentences(text: str) -> list[str]:
    return [s.strip() for s in _SENTENCE_END.split(text) if s and s.strip()]


def numbers(text: str) -> set[str]:
    """Cifras del texto normalizadas a solo dígitos ('45.000' -> '45000', '1,5' -> '15')."""
    return {re.sub(r"[.,]", "", n) for n in _NUMBER.findall(text)}


def truncate(text: str, max_chars: int) -> str:
    if len(text) <= max_chars:
        return text
    cut = text[:max_chars]
    space = cut.rfind(" ")
    return (cut[:space] if space > max_chars * 0.8 else cut).rstrip() + "…"
