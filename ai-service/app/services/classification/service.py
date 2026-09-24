import re
from dataclasses import dataclass

from app.core.text import normalize
from app.services.classification.base import IntentClassifier, IntentPrediction
from app.services.classification.taxonomy import INTENT_PROFILE, Category, Intent, Priority

# Expresiones de urgencia: suben la prioridad un nivel (nunca el sentimiento por sí solo)
_URGENCY = re.compile(
    r"\b(urgente|urgencia|inmediat\w*|cuanto antes|lo antes posible|ya mismo|hoy mismo|emergencia|"
    r"urgent\w*|asap|immediately|right now|emergency)\b"
)


@dataclass(frozen=True, slots=True)
class Classification:
    intent: Intent
    category: Category
    priority: Priority
    confidence: float
    strategy: str
    urgent_language: bool


class HybridIntentClassifier(IntentClassifier):
    """Combina estrategias de más barata a más cara: reglas -> modelo -> LLM.

    Se queda con la primera que supera su umbral; si ninguna lo hace, con la de mayor confianza.
    """

    name = "hybrid"

    def __init__(self, rules: IntentClassifier, model: IntentClassifier, llm: IntentClassifier | None,
                 llm_threshold: float, rules_threshold: float = 0.75) -> None:
        self._rules = rules
        self._model = model
        self._llm = llm
        self._llm_threshold = llm_threshold
        self._rules_threshold = rules_threshold

    def classify(self, text: str) -> IntentPrediction | None:
        candidates: list[IntentPrediction] = []
        by_rules = self._rules.classify(text)
        if by_rules and by_rules.confidence >= self._rules_threshold:
            return by_rules
        by_model = self._model.classify(text)
        candidates.extend(p for p in (by_rules, by_model) if p)
        best = max(candidates, key=lambda p: p.confidence, default=None)
        if best and best.confidence >= self._llm_threshold:
            return best
        by_llm = self._llm.classify(text) if self._llm else None
        if by_llm:
            candidates.append(by_llm)
        return max(candidates, key=lambda p: p.confidence, default=None)


class ClassificationService:
    """Clasificación completa de un mensaje: intención, categoría y prioridad.

    La estrategia (reglas, modelo, LLM o híbrida) se inyecta: el resto del sistema no sabe cuál
    se está usando, solo recibe el campo `strategy` para auditarlo.
    """

    def __init__(self, classifier: IntentClassifier) -> None:
        self._classifier = classifier

    def classify(self, text: str) -> Classification:
        prediction = self._classifier.classify(text) or IntentPrediction(Intent.GENERAL_QUESTION, 0.3, "default")
        category, priority = INTENT_PROFILE[prediction.intent]
        urgent = bool(_URGENCY.search(normalize(text)))
        if urgent:
            priority = priority.raised()
        return Classification(prediction.intent, category, priority, prediction.confidence, prediction.strategy,
                              urgent)
