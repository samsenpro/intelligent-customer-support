import math
from collections import Counter

from app.core.text import content_stems
from app.services.classification.base import IntentClassifier, IntentPrediction
from app.services.classification.taxonomy import Intent
from app.services.classification.training_data import TRAINING_EXAMPLES


class NaiveBayesIntentClassifier(IntentClassifier):
    """Modelo tradicional: Naive Bayes multinomial sobre stems, con suavizado de Laplace.

    Se entrena en memoria al arrancar (milisegundos). La confianza es la probabilidad a posteriori
    de la clase ganadora; un mensaje sin ningún término conocido no se clasifica.
    """

    name = "model"

    def __init__(self, examples: dict[Intent, list[str]] | None = None, alpha: float = 1.0) -> None:
        examples = examples or TRAINING_EXAMPLES
        self._alpha = alpha
        self._term_counts: dict[Intent, Counter[str]] = {}
        self._totals: dict[Intent, int] = {}
        vocabulary: set[str] = set()
        documents = sum(len(texts) for texts in examples.values())
        self._log_priors: dict[Intent, float] = {}
        for intent, texts in examples.items():
            counts: Counter[str] = Counter()
            for text in texts:
                counts.update(content_stems(text))
            self._term_counts[intent] = counts
            self._totals[intent] = sum(counts.values())
            self._log_priors[intent] = math.log(len(texts) / documents)
            vocabulary.update(counts)
        self._vocabulary = vocabulary

    def classify(self, text: str) -> IntentPrediction | None:
        terms = [t for t in content_stems(text) if t in self._vocabulary]
        if not terms:
            return None
        size = len(self._vocabulary)
        log_scores = {
            intent: prior + sum(
                math.log((self._term_counts[intent][t] + self._alpha) / (self._totals[intent] + self._alpha * size))
                for t in terms
            )
            for intent, prior in self._log_priors.items()
        }
        best = max(log_scores, key=log_scores.__getitem__)
        # Softmax estable para convertir log-probabilidades en probabilidad a posteriori
        top = log_scores[best]
        total = sum(math.exp(score - top) for score in log_scores.values())
        return IntentPrediction(best, round(1 / total, 3), self.name)
