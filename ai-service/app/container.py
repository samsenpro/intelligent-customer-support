from dataclasses import dataclass

from psycopg.conninfo import make_conninfo

from app.core.config import Settings
from app.embeddings.base import EmbeddingService
from app.embeddings.factory import create_embedding_service
from app.llm.base import LlmService
from app.llm.factory import create_llm_service
from app.rag.chunking import TextChunker
from app.rag.indexing import DocumentIndexer
from app.rag.ranking import ContextRanker
from app.rag.retrieval import VectorSearchService
from app.rag.vector_store.base import VectorStore
from app.rag.vector_store.memory import InMemoryVectorStore
from app.rag.vector_store.pgvector import PgVectorStore
from app.services.chat import ResponsePipeline
from app.services.classification.base import IntentClassifier
from app.services.classification.llm_classifier import LlmIntentClassifier
from app.services.classification.naive_bayes import NaiveBayesIntentClassifier
from app.services.classification.rules import RuleBasedIntentClassifier
from app.services.classification.service import ClassificationService, HybridIntentClassifier
from app.services.conversation_context import ConversationContextBuilder
from app.services.extractive import ExtractiveAnswerBuilder
from app.services.guardrails import InputGuard
from app.services.prompts import PromptTemplateService
from app.services.sentiment import LexiconSentimentAnalyzer, LlmSentimentAnalyzer, SentimentAnalyzer
from app.services.suggestion import SuggestionService
from app.services.summarization import ConversationSummarizer
from app.services.validation import ResponseValidator


@dataclass
class Services:
    settings: Settings
    llm: LlmService
    embeddings: EmbeddingService
    store: VectorStore
    prompts: PromptTemplateService
    classification: ClassificationService
    sentiment: SentimentAnalyzer
    search: VectorSearchService
    indexer: DocumentIndexer
    pipeline: ResponsePipeline
    summarizer: ConversationSummarizer
    suggestions: SuggestionService

    def close(self) -> None:
        self.llm.close()
        self.embeddings.close()
        self.store.close()


def create_vector_store(settings: Settings) -> VectorStore:
    if settings.vector_store_provider == "memory":
        return InMemoryVectorStore()
    conninfo = psycopg_conninfo(settings)
    store = PgVectorStore(conninfo, settings.vector_db_schema, settings.embedding_dimensions,
                          settings.vector_db_pool_max_size)
    store.open()
    return store


def psycopg_conninfo(settings: Settings) -> str:
    return make_conninfo(
        host=settings.vector_db_host, port=settings.vector_db_port, dbname=settings.vector_db_name,
        user=settings.vector_db_user, password=settings.vector_db_password.get_secret_value(),
        application_name=settings.service_name,
    )


def create_intent_classifier(settings: Settings, llm: LlmService, prompts: PromptTemplateService) -> IntentClassifier:
    rules = RuleBasedIntentClassifier()
    model = NaiveBayesIntentClassifier()
    by_llm = LlmIntentClassifier(llm, prompts)
    strategies: dict[str, IntentClassifier] = {"rules": rules, "model": model, "llm": by_llm}
    if settings.classifier_strategy in strategies:
        return strategies[settings.classifier_strategy]
    return HybridIntentClassifier(rules, model, by_llm if llm.enabled else None, settings.classifier_llm_threshold)


def build_services(
    settings: Settings,
    *,
    llm: LlmService | None = None,
    embeddings: EmbeddingService | None = None,
    store: VectorStore | None = None,
) -> Services:
    """Construye el grafo de dependencias. Los tests sustituyen el LLM, los embeddings o el vector
    store sin tocar el resto."""
    llm = llm or create_llm_service(settings)
    embeddings = embeddings or create_embedding_service(settings)
    store = store or create_vector_store(settings)
    prompts = PromptTemplateService(overrides=settings.prompt_version_overrides)

    classification = ClassificationService(create_intent_classifier(settings, llm, prompts))
    lexicon = LexiconSentimentAnalyzer()
    sentiment: SentimentAnalyzer = (
        LlmSentimentAnalyzer(llm, prompts, lexicon) if settings.sentiment_strategy == "llm" else lexicon
    )
    search = VectorSearchService(embeddings, store)
    ranker = ContextRanker(settings.effective_min_score, settings.rag_max_context_chars)
    context_builder = ConversationContextBuilder(settings.max_history_messages)
    validator = ResponseValidator()
    extractive = ExtractiveAnswerBuilder()
    chunker = TextChunker(settings.chunk_size_chars, settings.chunk_overlap_chars)

    pipeline = ResponsePipeline(
        guard=InputGuard(), classifier=classification, context_builder=context_builder, search=search,
        ranker=ranker, prompts=prompts, llm=llm, validator=validator, extractive=extractive,
        default_top_k=settings.vector_search_top_k, candidate_multiplier=settings.rag_candidate_multiplier,
    )
    suggestions = SuggestionService(
        context_builder=context_builder, search=search, ranker=ranker, prompts=prompts, llm=llm,
        validator=validator, extractive=extractive, default_top_k=settings.vector_search_top_k,
        candidate_multiplier=settings.rag_candidate_multiplier,
    )
    return Services(
        settings=settings, llm=llm, embeddings=embeddings, store=store, prompts=prompts,
        classification=classification, sentiment=sentiment, search=search,
        indexer=DocumentIndexer(chunker, embeddings, store),
        pipeline=pipeline, summarizer=ConversationSummarizer(llm, prompts), suggestions=suggestions,
    )
