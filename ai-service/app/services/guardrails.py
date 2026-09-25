import re
from dataclasses import dataclass

from app.core.text import normalize

# Intentos de cambiar las reglas del asistente o de extraer sus instrucciones o datos ajenos
_INJECTION_PATTERNS = [
    r"\b(ignora|olvida|omite)\w* (todas |todo |las |tus )*(instrucciones|reglas|indicaciones)",
    r"\b(ignore|forget|disregard) (all |any |the |your )*(previous |prior |above |earlier )?"
    r"(instructions|rules|prompts?)",
    r"\b(system prompt|prompt del sistema|prompt interno|mensaje del sistema)\b",
    r"\b(revela|muestra|dime|repite|imprime)\w* (tus |las |el )?(instrucciones|reglas|prompt)",
    r"\b(reveal|show|print|repeat|tell me) (me )?(your |the )?(instructions|rules|system prompt|prompt)",
    r"\b(modo desarrollador|developer mode|jailbreak|dan mode)\b",
    r"\b(actua|comportate) como (si fueras|un) (otro|sistema|administrador)",
    r"\byou are now\b",
    r"\b(datos|informacion|pedidos|conversaciones) de (otros|otras) (clientes|empresas|usuarios|organizaciones)",
    r"\b(other|another) (customers?|companies|users|organizations?)'?s? (data|information|orders|conversations)",
]
_COMPILED = [re.compile(p) for p in _INJECTION_PATTERNS]


@dataclass(frozen=True, slots=True)
class GuardVerdict:
    blocked: bool
    reason: str | None = None


class InputGuard:
    """Filtro previo al LLM: los intentos evidentes de prompt injection o de obtener datos de otros
    clientes no llegan al modelo. Es una primera barrera; las reglas del prompt y la validación de la
    respuesta son las siguientes."""

    def check(self, message: str) -> GuardVerdict:
        normalized = normalize(message)
        for pattern in _COMPILED:
            if pattern.search(normalized):
                return GuardVerdict(True, "PROMPT_INJECTION")
        return GuardVerdict(False)
