import tomllib
from dataclasses import dataclass
from pathlib import Path
from string import Template

from app.llm.base import ChatMessage

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"


@dataclass(frozen=True, slots=True)
class PromptTemplate:
    name: str
    version: str
    description: str
    system: Template
    user: Template


@dataclass(frozen=True, slots=True)
class RenderedPrompt:
    name: str
    version: str
    messages: list[ChatMessage]
    system_text: str

    @property
    def id(self) -> str:
        """Identificador que viaja en la respuesta (p. ej. customer_response_v2) para auditar qué
        versión del prompt generó cada mensaje."""
        return f"{self.name}_{self.version}"


class PromptTemplateService:
    """Plantillas de prompt versionadas, fuera del código (app/prompts/*.toml).

    Cada archivo define varias versiones y una por defecto; PROMPT_VERSIONS permite fijar otra sin
    desplegar código (p. ej. volver a customer_response=v1 si la v2 empeora las respuestas).
    Las variables usan la sintaxis $variable: los valores sustituidos (mensajes del cliente) no se
    vuelven a interpretar, así que un "$context" escrito por el cliente no inyecta nada.
    """

    def __init__(self, directory: Path = PROMPTS_DIR, overrides: dict[str, str] | None = None) -> None:
        self._templates: dict[str, dict[str, PromptTemplate]] = {}
        self._active: dict[str, str] = {}
        for path in sorted(directory.glob("*.toml")):
            self._load(path)
        for name, version in (overrides or {}).items():
            if version not in self._templates.get(name, {}):
                raise ValueError(f"Unknown prompt version {name}={version}")
            self._active[name] = version

    def render(self, name: str, version: str | None = None, **variables: str) -> RenderedPrompt:
        template = self.get(name, version)
        system = template.system.substitute(variables).strip()
        user = template.user.substitute(variables).strip()
        return RenderedPrompt(
            name=name,
            version=template.version,
            messages=[{"role": "system", "content": system}, {"role": "user", "content": user}],
            system_text=system,
        )

    def get(self, name: str, version: str | None = None) -> PromptTemplate:
        versions = self._templates.get(name)
        if versions is None:
            raise KeyError(f"Unknown prompt template: {name}")
        selected = version or self._active[name]
        if selected not in versions:
            raise KeyError(f"Unknown version {selected} for prompt {name}")
        return versions[selected]

    def active_version(self, name: str) -> str:
        return self._active[name]

    def catalog(self) -> dict[str, dict[str, object]]:
        return {
            name: {"active": self._active[name], "versions": sorted(versions)}
            for name, versions in sorted(self._templates.items())
        }

    def _load(self, path: Path) -> None:
        with path.open("rb") as file:
            data = tomllib.load(file)
        name = path.stem
        versions = {
            version: PromptTemplate(name, version, spec.get("description", ""), Template(spec["system"]),
                                    Template(spec["user"]))
            for version, spec in data["versions"].items()
        }
        default = data["default_version"]
        if default not in versions:
            raise ValueError(f"{path.name}: default_version {default} is not defined")
        self._templates[name] = versions
        self._active[name] = default
