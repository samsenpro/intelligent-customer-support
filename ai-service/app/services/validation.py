import re
from dataclasses import dataclass, field
from enum import StrEnum

from app.core.metrics import RESPONSE_VALIDATION_FAILURES
from app.core.text import content_stems, normalize, numbers

NO_CONTEXT_SENTINEL = "NO_RELEVANT_CONTEXT"

_CITATION = re.compile(r"\[\d+\]")
_LEAK_PHRASES = re.compile(
    r"\b(mis instrucciones|mis reglas|prompt del sistema|system prompt|my instructions|my rules|"
    r"as an ai language model|como modelo de lenguaje)\b"
)
# Por debajo de esta cobertura la respuesta habla de cosas que no están en el contexto
LOW_GROUNDING = 0.35


class Issue(StrEnum):
    EMPTY = "EMPTY"
    NO_CONTEXT = "NO_CONTEXT"
    PROMPT_LEAK = "PROMPT_LEAK"
    UNGROUNDED_NUMBERS = "UNGROUNDED_NUMBERS"
    LOW_GROUNDING = "LOW_GROUNDING"


# Problemas que invalidan la respuesta (LOW_GROUNDING solo reduce la confianza)
_BLOCKING = {Issue.EMPTY, Issue.NO_CONTEXT, Issue.PROMPT_LEAK, Issue.UNGROUNDED_NUMBERS}


@dataclass(frozen=True, slots=True)
class ValidationResult:
    issues: list[Issue] = field(default_factory=list)
    grounding: float = 1.0

    @property
    def valid(self) -> bool:
        return not any(issue in _BLOCKING for issue in self.issues)


class ResponseValidator:
    """Última etapa del pipeline: comprueba la respuesta del LLM antes de que llegue al cliente.

    - Cifras (precios, plazos, porcentajes) que no aparecen en el contexto ni en la conversación:
      el modelo las inventó ("no inventar precios").
    - Fragmentos literales del prompt del sistema o frases sobre sus instrucciones: fuga del prompt.
    - Cobertura de los términos de la respuesta en el contexto (grounding), que modula la confianza.
    """

    def validate(self, answer: str, *, context: str, conversation: str, system_prompt: str) -> ValidationResult:
        stripped = answer.strip()
        if not stripped:
            return self._result([Issue.EMPTY], 0.0)
        if NO_CONTEXT_SENTINEL in stripped:
            return self._result([Issue.NO_CONTEXT], 0.0)

        issues: list[Issue] = []
        if _LEAK_PHRASES.search(normalize(stripped)) or _shares_long_fragment(stripped, system_prompt):
            issues.append(Issue.PROMPT_LEAK)

        allowed_numbers = numbers(context) | numbers(conversation)
        if numbers(_CITATION.sub("", stripped)) - allowed_numbers:
            issues.append(Issue.UNGROUNDED_NUMBERS)

        answer_stems = set(content_stems(stripped))
        context_stems = set(content_stems(context)) | set(content_stems(conversation))
        grounding = len(answer_stems & context_stems) / len(answer_stems) if answer_stems else 0.0
        if grounding < LOW_GROUNDING:
            issues.append(Issue.LOW_GROUNDING)
        return self._result(issues, round(grounding, 3))

    @staticmethod
    def _result(issues: list[Issue], grounding: float) -> ValidationResult:
        for issue in issues:
            if issue in _BLOCKING and issue is not Issue.NO_CONTEXT:
                RESPONSE_VALIDATION_FAILURES.labels(issue=issue.value).inc()
        return ValidationResult(issues, grounding)


def _shares_long_fragment(answer: str, system_prompt: str, size: int = 8) -> bool:
    """True si la respuesta contiene `size` palabras seguidas del prompt del sistema."""
    prompt_words = normalize(system_prompt).split()
    if len(prompt_words) < size:
        return False
    shingles = {" ".join(prompt_words[i : i + size]) for i in range(len(prompt_words) - size + 1)}
    answer_words = normalize(answer).split()
    return any(" ".join(answer_words[i : i + size]) in shingles for i in range(len(answer_words) - size + 1))
