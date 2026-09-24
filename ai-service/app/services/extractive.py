from app.core.text import content_stems, split_sentences
from app.rag.ranking import RankedContext
from app.services.messages import Text, text


class ExtractiveAnswerBuilder:
    """Respuesta sin LLM: las frases del contexto recuperado que mejor cubren la pregunta.

    Se usa cuando no hay LLM configurado, cuando el LLM falla y cuando su respuesta no pasa la
    validación. Nunca inventa nada: todo lo que dice está literalmente en la base de conocimiento.
    """

    def __init__(self, max_sentences: int = 3) -> None:
        self.max_sentences = max_sentences

    def build(self, question: str, context: RankedContext, language: str) -> str:
        best = context.chunks[0].hit
        query = set(content_stems(question))
        candidates: list[tuple[float, int, str]] = []
        for position, sentence in enumerate(split_sentences(best.content)):
            stems = set(content_stems(sentence))
            if not stems:
                continue
            overlap = len(query & stems)
            # Ligera preferencia por las primeras frases: suelen enunciar la regla principal
            candidates.append((overlap - position * 0.01, position, sentence))
        if not candidates:
            return best.content.strip()
        chosen = sorted(candidates, reverse=True)[: self.max_sentences]
        # Si ninguna frase comparte términos con la pregunta, se toma el inicio del fragmento
        if chosen[0][0] <= 0:
            chosen = sorted(candidates, key=lambda c: c[1])[: self.max_sentences]
        body = " ".join(sentence for _, _, sentence in sorted(chosen, key=lambda c: c[1]))
        return f"{text(Text.EXTRACTIVE_PREFIX, language, title=best.title)} {body}"
