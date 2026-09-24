import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import StrEnum

from app.core.text import stem, tokens
from app.llm.base import LlmError, LlmService
from app.services.prompts import PromptTemplateService

logger = logging.getLogger(__name__)


class Sentiment(StrEnum):
    POSITIVE = "POSITIVE"
    NEUTRAL = "NEUTRAL"
    NEGATIVE = "NEGATIVE"


@dataclass(frozen=True, slots=True)
class SentimentResult:
    sentiment: Sentiment
    confidence: float
    score: float
    strategy: str


class SentimentAnalyzer(ABC):
    """Sentimiento de un mensaje. Es información auxiliar para el agente: el sistema nunca toma
    decisiones importantes sobre un cliente basándose solo en él."""

    name: str

    @abstractmethod
    def analyze(self, text: str) -> SentimentResult: ...


# Léxico por stem (6 caracteres, sin tildes): peso positivo o negativo
_LEXICON: dict[str, float] = {
    stem(word): weight
    for words, weight in (
        ("gracias excelente genial perfecto encanta encantado feliz contento satisfecho rapido amable bueno buena "
         "buenisimo maravilloso increible recomiendo solucionado resuelto funciona agradezco", 1.0),
        ("thanks thank great excellent perfect love happy satisfied fast kind good amazing awesome wonderful "
         "recommend solved resolved works appreciate helpful", 1.0),
        ("malo mala pesimo terrible horrible fatal molesto molesta enojado furioso decepcionado decepcionada "
         "inaceptable estafa lento lenta roto rota danado danada frustrado cansado harto queja reclamo nunca "
         "problema error falla fallo peor grosero indignado", -1.0),
        ("bad awful terrible horrible angry upset furious disappointed unacceptable scam slow broken damaged "
         "frustrated tired complaint never problem error worst rude useless ridiculous", -1.0),
    )
    for word in words.split()
}
# "don't" se tokeniza como "don" + "t": se incluyen las raíces de las contracciones
_NEGATIONS = frozenset("no nunca jamas tampoco ni not never don doesn didn isn wasn cannot won".split())
_INTENSIFIERS = frozenset("muy demasiado super totalmente extremadamente very really so extremely totally".split())


class LexiconSentimentAnalyzer(SentimentAnalyzer):
    """Análisis por léxico en español e inglés, con negaciones ("no funciona", "no estoy contento"),
    intensificadores ("muy malo") y énfasis (exclamaciones, mayúsculas)."""

    name = "lexicon"

    def analyze(self, text: str) -> SentimentResult:
        words = tokens(text)
        score = 0.0
        hits = 0
        for i, word in enumerate(words):
            weight = _LEXICON.get(stem(word))
            if weight is None:
                continue
            window = words[max(0, i - 3) : i]
            if any(w in _NEGATIONS for w in window):
                # "no funciona" es negativo; "no es malo" tiende a neutro-positivo
                weight = -weight * (1.0 if weight > 0 else 0.5)
            if any(w in _INTENSIFIERS for w in window):
                weight *= 1.5
            score += weight
            hits += 1

        if hits:
            emphasis = 1.0 + min(text.count("!"), 3) * 0.1
            letters = [c for c in text if c.isalpha()]
            if len(letters) > 12 and sum(c.isupper() for c in letters) / len(letters) > 0.7:
                emphasis += 0.3
            score = score * emphasis / (hits ** 0.5 * 2)
        score = max(-1.0, min(1.0, score))

        if score >= 0.2:
            sentiment = Sentiment.POSITIVE
        elif score <= -0.2:
            sentiment = Sentiment.NEGATIVE
        else:
            sentiment = Sentiment.NEUTRAL
        # Sin palabras del léxico: neutro con confianza moderada (no hay señal, no certeza de neutralidad)
        confidence = 0.6 if not hits else min(0.55 + abs(score) * 0.45, 0.97)
        return SentimentResult(sentiment, round(confidence, 3), round(score, 3), self.name)


class LlmSentimentAnalyzer(SentimentAnalyzer):
    """Sentimiento con el LLM; si falla o no está configurado se usa el léxico."""

    name = "llm"

    def __init__(self, llm: LlmService, prompts: PromptTemplateService, fallback: SentimentAnalyzer) -> None:
        self._llm = llm
        self._prompts = prompts
        self._fallback = fallback

    def analyze(self, text: str) -> SentimentResult:
        if not self._llm.enabled:
            return self._fallback.analyze(text)
        try:
            result = self._llm.complete_json(self._prompts.render("sentiment", message=text).messages,
                                             operation="sentiment", max_tokens=40)
            sentiment = Sentiment(str(result.get("sentiment", "")).strip().upper())
            confidence = min(max(float(result.get("confidence", 0.5)), 0.0), 1.0)
        except (LlmError, ValueError, TypeError) as ex:
            logger.info("LLM sentiment discarded, using lexicon: %s", type(ex).__name__)
            return self._fallback.analyze(text)
        score = {Sentiment.POSITIVE: confidence, Sentiment.NEGATIVE: -confidence}.get(sentiment, 0.0)
        return SentimentResult(sentiment, round(confidence, 3), round(score, 3), self.name)
