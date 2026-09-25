import re

from app.core.text import normalize
from app.services.classification.base import IntentClassifier, IntentPrediction
from app.services.classification.taxonomy import INTENT_PROFILE, Intent, Priority

# Patrones sobre el texto normalizado (minúsculas y sin tildes), en español e inglés.
# Cada coincidencia suma; las frases muy específicas pesan más que una palabra suelta.
_RULES: dict[Intent, list[tuple[str, float]]] = {
    Intent.HUMAN_REQUEST: [
        (r"\b(hablar|comunicar\w*|pasar\w*|atender\w*) (con )?(un |una |el |la )?"
         r"(agente|humano|persona|asesor\w*|operador\w*)", 3),
        (r"\b(agente|asesor|persona) (humana|real)\b", 3),
        (r"\b(talk|speak|chat) (to|with) (a |an )?(human|person|agent|representative)", 3),
        (r"\b(real|human) (person|agent)\b", 2),
    ],
    Intent.FRAUD_REPORT: [
        (r"\bfraud\w*", 3), (r"\b(cargo|cobro|compra)s? (no|que no) (autoriza|reconoz|hice)\w*", 3),
        (r"\bunauthori[sz]ed (charge|transaction|payment|purchase)", 3), (r"\b(hackea|robaron|robo de)\w*", 2),
        (r"\b(hacked|stolen)\b", 2), (r"\bsuplantacion\b", 2),
    ],
    Intent.LEGAL_REQUEST: [
        (r"\b(demanda|abogado|denuncia|sic|superintendencia|proteccion al consumidor)\b", 2),
        (r"\b(habeas data|datos personales|gdpr|lawyer|lawsuit|legal action)\b", 2),
        (r"\b(borrar|eliminar) (mis|todos mis) datos\b", 2), (r"\bdelete my (data|personal data)\b", 2),
    ],
    Intent.REFUND_REQUEST: [
        (r"\b(reembols|devolu|devolv|refund|reintegr)\w*", 2), (r"\bmoney back\b", 2),
        (r"\b(devuelvan|regresen) (mi |el )?dinero\b", 3), (r"\breturn (the|my|an?) (item|product|order)\b", 2),
    ],
    Intent.CANCELLATION: [
        (r"\b(cancelar|cancelacion|anular|dar de baja|darme de baja)\b", 2),
        (r"\b(cancel|unsubscribe|terminate) (my )?(order|subscription|account|plan)?", 2),
    ],
    Intent.PAYMENT_ISSUE: [
        (r"\b(pago|cobro|cargo|tarjeta)s? (rechazad|fallid|duplicad|doble)\w*", 3),
        (r"\bme (cobraron|cobran) (dos veces|doble|de mas)\b", 3),
        (r"\b(payment|card) (failed|declined|rejected)\b", 3), (r"\b(charged twice|double charge)\b", 3),
        (r"\bno (me )?(deja|puedo) pagar\b", 2),
    ],
    Intent.BILLING_QUESTION: [
        (r"\b(factura|facturacion|cobro|precio|tarifa|iva|recibo|medios? de pago|metodos? de pago)\w*", 1.5),
        (r"\b(invoice|billing|bill|price|pricing|payment methods?)\b", 1.5),
    ],
    Intent.ORDER_STATUS: [
        (r"\b(donde|cuando) (esta|llega|va|viene) (mi |el )?(pedido|paquete|orden|envio|compra)", 3),
        (r"\b(estado|seguimiento|rastreo|rastrear) (de |del )?(mi |el )?(pedido|orden|envio|paquete)", 3),
        (r"\b(where|when) (is|will) (my )?(order|package|parcel)", 3), (r"\b(track|tracking)\b", 2),
        (r"\bnumero de guia\b", 2),
    ],
    Intent.SHIPPING_ISSUE: [
        (r"\b(llego|lleg\w+) (roto|danad\w*|incomplet\w*|equivocad\w*|tarde)", 3),
        (r"\b(no (ha )?llegado|no llega|nunca llego|retrasad\w*|demora\w*)\b", 2),
        (r"\b(envio|entrega|shipping|delivery|domicilio)s?\b", 1),
        (r"\b(damaged|broken|wrong item|late delivery|never arrived|delayed)\b", 2),
    ],
    Intent.ACCOUNT_ACCESS: [
        (r"\b(contrasena|clave|password|usuario|cuenta|login|iniciar sesion|acceder|ingresar)\b", 1),
        (r"\b(no puedo|no me deja) (entrar|ingresar|acceder|iniciar sesion)", 3),
        (r"\b(olvide|recuperar|restablecer) (mi )?(contrasena|clave|cuenta)", 3),
        (r"\b(can'?t|cannot|unable to) (log ?in|sign ?in|access)", 3), (r"\b(forgot|reset) (my )?password\b", 3),
        (r"\bcuenta (bloqueada|suspendida)\b", 3), (r"\baccount (locked|suspended)\b", 3),
    ],
    Intent.TECHNICAL_ISSUE: [
        (r"\b(error|falla|fallo|bug|no funciona|no carga|se cae|se cierra|se bloquea)\b", 2),
        (r"\b(not working|doesn'?t work|crash\w*|broken app|won'?t load)\b", 2),
        (r"\b(app|aplicacion|pagina|sitio|web)\b", 0.5),
    ],
    Intent.COMPLAINT: [
        (r"\b(queja|reclamo|inaceptable|pesimo|terrible|horrible|estafa|indignad\w*|molest\w*)\b", 2),
        (r"\b(complaint|unacceptable|worst|awful|scam|disappointed)\b", 2),
        (r"\bmal (servicio|trato|atencion)\b", 2), (r"\bpoor service\b", 2),
    ],
    Intent.PRODUCT_INFO: [
        # "producto" aparece en casi cualquier consulta (reembolsos, envíos...): pesa poco por sí sola
        (r"\b(producto|modelo)s?\b", 0.5),
        (r"\b(caracteristica|especificacion|garantia|talla|color|disponib|stock|compatible)\w*", 1.5),
        (r"\b(product|feature|specification|warranty|size|available|availability|compatible)\w*", 1.5),
        (r"\b(que|cual) (es la|es el|son las|son los) (diferencia|caracteristicas)", 2),
    ],
    Intent.GREETING: [
        (r"^(hola|buenas|buenos dias|buenas tardes|buenas noches|hello|hi|hey|"
         r"good (morning|afternoon|evening))\W*$", 3),
    ],
}

_COMPILED = {intent: [(re.compile(p), w) for p, w in rules] for intent, rules in _RULES.items()}

_ORDER = list(Intent)


class RuleBasedIntentClassifier(IntentClassifier):
    """Clasificación por patrones. Rápida, explicable y predecible: resuelve los casos claros y deja
    los ambiguos al modelo o al LLM (confianza baja)."""

    name = "rules"

    def classify(self, text: str) -> IntentPrediction | None:
        normalized = normalize(text).strip()
        scores: dict[Intent, float] = {}
        for intent, patterns in _COMPILED.items():
            score = sum(weight for pattern, weight in patterns if pattern.search(normalized))
            if score:
                scores[intent] = score
        if not scores:
            return None
        # A igual puntuación gana la intención de mayor prioridad (fraude antes que facturación)
        best = max(scores, key=lambda i: (scores[i], _priority_rank(i), -_ORDER.index(i)))
        total = sum(scores.values())
        # Confianza: fuerza de la señal (puntos) y margen sobre las demás intenciones
        strength = min(scores[best] / 3, 1.0)
        dominance = scores[best] / total
        confidence = round(min(0.35 + 0.35 * strength + 0.25 * dominance, 0.95), 3)
        return IntentPrediction(best, confidence, self.name)


def _priority_rank(intent: Intent) -> int:
    return list(Priority).index(INTENT_PROFILE[intent][1])
