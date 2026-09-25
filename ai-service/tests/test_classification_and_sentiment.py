import pytest

from app.llm.base import LlmError
from app.services.classification.llm_classifier import LlmIntentClassifier
from app.services.classification.naive_bayes import NaiveBayesIntentClassifier
from app.services.classification.rules import RuleBasedIntentClassifier
from app.services.classification.service import ClassificationService, HybridIntentClassifier
from app.services.classification.taxonomy import Intent, Priority
from app.services.prompts import PromptTemplateService
from app.services.sentiment import LexiconSentimentAnalyzer, LlmSentimentAnalyzer, Sentiment
from tests.conftest import FakeLlm


@pytest.mark.parametrize(("message", "intent"), [
    ("Quiero que me devuelvan el dinero de mi compra", Intent.REFUND_REQUEST),
    ("¿Dónde está mi pedido? Lo compré hace una semana", Intent.ORDER_STATUS),
    ("Me cobraron dos veces la misma compra", Intent.PAYMENT_ISSUE),
    ("No puedo iniciar sesión, olvidé mi contraseña", Intent.ACCOUNT_ACCESS),
    ("Hay un cargo que no reconozco en mi tarjeta, creo que es fraude", Intent.FRAUD_REPORT),
    ("Quiero hablar con un asesor humano", Intent.HUMAN_REQUEST),
    ("I want to cancel my subscription", Intent.CANCELLATION),
    ("The app keeps crashing, it's not working", Intent.TECHNICAL_ISSUE),
    ("Hola", Intent.GREETING),
])
def test_rules_classify_clear_messages(message, intent):
    prediction = RuleBasedIntentClassifier().classify(message)
    assert prediction is not None
    assert prediction.intent is intent
    assert prediction.strategy == "rules"


def test_rules_return_none_without_signal():
    assert RuleBasedIntentClassifier().classify("xyz qwerty") is None


@pytest.mark.parametrize(("message", "intent"), [
    ("my parcel never arrived", Intent.SHIPPING_ISSUE),
    ("¿el producto tiene garantía?", Intent.PRODUCT_INFO),
    ("quiero que borren mis datos personales", Intent.LEGAL_REQUEST),
])
def test_naive_bayes_generalizes_from_the_training_corpus(message, intent):
    prediction = NaiveBayesIntentClassifier().classify(message)
    assert prediction is not None
    assert prediction.intent is intent
    assert 0 < prediction.confidence <= 1


def test_naive_bayes_ignores_messages_without_known_terms():
    assert NaiveBayesIntentClassifier().classify("zzz") is None


def test_hybrid_uses_the_llm_only_when_rules_and_model_are_not_confident():
    llm = FakeLlm({"classification": '{"intent": "PRODUCT_INFO", "confidence": 0.8}'})
    hybrid = HybridIntentClassifier(RuleBasedIntentClassifier(), NaiveBayesIntentClassifier(),
                                    LlmIntentClassifier(llm, PromptTemplateService()), llm_threshold=0.99)
    assert hybrid.classify("Me cobraron dos veces").intent is Intent.PAYMENT_ISSUE
    assert llm.calls == []  # las reglas bastaron

    prediction = hybrid.classify("xyzzy plugh")
    assert prediction.intent is Intent.PRODUCT_INFO
    assert prediction.strategy == "llm"


def test_llm_classifier_discards_intents_outside_the_taxonomy():
    llm = FakeLlm({"classification": '{"intent": "WANTS_PIZZA", "confidence": 0.99}'})
    assert LlmIntentClassifier(llm, PromptTemplateService()).classify("pizza") is None
    failing = FakeLlm({"classification": LlmError("down")})
    assert LlmIntentClassifier(failing, PromptTemplateService()).classify("hola") is None


def test_urgency_raises_the_priority_one_level():
    service = ClassificationService(RuleBasedIntentClassifier())
    normal = service.classify("¿Dónde está mi pedido?")
    urgent = service.classify("¿Dónde está mi pedido? Es urgente")
    assert normal.priority is Priority.MEDIUM
    assert urgent.priority is Priority.HIGH
    assert service.classify("Creo que es un fraude, urgente").priority is Priority.URGENT


def test_unclassifiable_messages_default_to_a_general_question():
    result = ClassificationService(RuleBasedIntentClassifier()).classify("xyz")
    assert result.intent is Intent.GENERAL_QUESTION
    assert result.strategy == "default"


@pytest.mark.parametrize(("message", "sentiment"), [
    ("¡Muchas gracias, excelente servicio!", Sentiment.POSITIVE),
    ("El producto llegó roto y el servicio es pésimo", Sentiment.NEGATIVE),
    ("La aplicación no funciona", Sentiment.NEGATIVE),
    ("No estoy contento con la compra", Sentiment.NEGATIVE),
    ("¿Cuál es el horario de atención?", Sentiment.NEUTRAL),
    ("Thanks, the support was really helpful", Sentiment.POSITIVE),
    ("This is the worst, totally unacceptable", Sentiment.NEGATIVE),
])
def test_lexicon_sentiment(message, sentiment):
    result = LexiconSentimentAnalyzer().analyze(message)
    assert result.sentiment is sentiment
    assert 0 < result.confidence <= 1


def test_llm_sentiment_falls_back_to_the_lexicon():
    lexicon = LexiconSentimentAnalyzer()
    ok = LlmSentimentAnalyzer(FakeLlm({"sentiment": '{"sentiment": "NEGATIVE", "confidence": 0.9}'}),
                              PromptTemplateService(), lexicon)
    assert ok.analyze("meh").sentiment is Sentiment.NEGATIVE
    assert ok.analyze("meh").strategy == "llm"

    broken = LlmSentimentAnalyzer(FakeLlm({"sentiment": "not json"}), PromptTemplateService(), lexicon)
    result = broken.analyze("¡Excelente, gracias!")
    assert result.strategy == "lexicon"
    assert result.sentiment is Sentiment.POSITIVE
