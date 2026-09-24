import logging

from app.llm.base import LlmError, LlmService
from app.services.classification.base import IntentClassifier, IntentPrediction
from app.services.classification.taxonomy import Intent
from app.services.prompts import PromptTemplateService

logger = logging.getLogger(__name__)


class LlmIntentClassifier(IntentClassifier):
    """Clasificación con el LLM. Solo se aceptan intenciones de la taxonomía: si el modelo inventa
    una, la respuesta se descarta."""

    name = "llm"

    def __init__(self, llm: LlmService, prompts: PromptTemplateService) -> None:
        self._llm = llm
        self._prompts = prompts

    def classify(self, text: str) -> IntentPrediction | None:
        if not self._llm.enabled:
            return None
        prompt = self._prompts.render("classification", intents=", ".join(i.value for i in Intent), message=text)
        try:
            result = self._llm.complete_json(prompt.messages, operation="classification", max_tokens=60)
            intent = Intent(str(result.get("intent", "")).strip().upper())
            confidence = min(max(float(result.get("confidence", 0.5)), 0.0), 1.0)
        except (LlmError, ValueError, TypeError) as ex:
            logger.info("LLM classification discarded: %s", type(ex).__name__)
            return None
        return IntentPrediction(intent, round(confidence, 3), self.name)
