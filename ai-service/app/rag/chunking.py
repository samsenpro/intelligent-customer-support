import re
from dataclasses import dataclass

from app.core.text import split_sentences

_BLANK_LINES = re.compile(r"\n\s*\n")
_SPACES = re.compile(r"[ \t\f\v]+")
_HEADING = re.compile(r"^#{1,6}\s+")


@dataclass(frozen=True, slots=True)
class TextChunk:
    index: int
    content: str


class TextChunker:
    """Divide un documento en fragmentos de tamaño acotado respetando párrafos y frases.

    - Un fragmento nunca corta una frase por la mitad (salvo frases más largas que el máximo).
    - Los fragmentos consecutivos se solapan unas frases para no perder el contexto en el borde.
    - Un título de Markdown se une al párrafo que encabeza: "## Reembolsos" solo no aporta nada.
    """

    def __init__(self, max_chars: int = 800, overlap_chars: int = 120) -> None:
        if overlap_chars >= max_chars:
            raise ValueError("overlap must be smaller than the chunk size")
        self.max_chars = max_chars
        self.overlap_chars = overlap_chars

    def split(self, text: str) -> list[TextChunk]:
        units = self._units(text)
        chunks: list[str] = []
        current: list[str] = []
        size = 0
        for unit in units:
            if current and size + len(unit) + 1 > self.max_chars:
                chunks.append(" ".join(current))
                current = self._overlap(current)
                size = sum(len(u) + 1 for u in current)
                if size + len(unit) + 1 > self.max_chars:
                    # La frase no cabe ni con el solapamiento: un fragmento con solo solapamiento no aporta nada
                    current, size = [], 0
            current.append(unit)
            size += len(unit) + 1
        if current:
            chunks.append(" ".join(current))
        return [TextChunk(i, c) for i, c in enumerate(chunks)]

    def _units(self, text: str) -> list[str]:
        """Frases del documento, con los títulos pegados a la primera frase de su sección."""
        units: list[str] = []
        pending_heading = ""
        for paragraph in _BLANK_LINES.split(text.replace("\r\n", "\n")):
            lines = [_SPACES.sub(" ", line).strip() for line in paragraph.split("\n")]
            lines = [line for line in lines if line]
            if not lines:
                continue
            if len(lines) == 1 and _HEADING.match(lines[0]):
                pending_heading = _HEADING.sub("", lines[0]).strip()
                continue
            if _HEADING.match(lines[0]):
                pending_heading = _HEADING.sub("", lines.pop(0)).strip()
            sentences = split_sentences(" ".join(lines))
            if pending_heading and sentences:
                sentences[0] = f"{pending_heading}: {sentences[0]}"
                pending_heading = ""
            for sentence in sentences:
                units.extend(self._hard_split(sentence))
        if pending_heading:
            units.append(pending_heading)
        return units

    def _hard_split(self, sentence: str) -> list[str]:
        if len(sentence) <= self.max_chars:
            return [sentence]
        parts, current = [], ""
        for word in sentence.split(" "):
            if current and len(current) + len(word) + 1 > self.max_chars:
                parts.append(current)
                current = word
            else:
                current = f"{current} {word}".strip()
        if current:
            parts.append(current)
        return parts

    def _overlap(self, units: list[str]) -> list[str]:
        kept: list[str] = []
        size = 0
        for unit in reversed(units):
            if size + len(unit) > self.overlap_chars:
                break
            kept.insert(0, unit)
            size += len(unit) + 1
        return kept
