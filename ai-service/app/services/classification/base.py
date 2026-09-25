from abc import ABC, abstractmethod
from dataclasses import dataclass

from app.services.classification.taxonomy import Intent


@dataclass(frozen=True, slots=True)
class IntentPrediction:
    intent: Intent
    confidence: float
    strategy: str


class IntentClassifier(ABC):
    """Estrategia de clasificación de intención. Reglas, modelo tradicional y LLM implementan esta
    interfaz; el sistema no depende de ninguna en concreto."""

    name: str

    @abstractmethod
    def classify(self, text: str) -> IntentPrediction | None:
        """Intención del mensaje, o None si la estrategia no puede decidir."""
