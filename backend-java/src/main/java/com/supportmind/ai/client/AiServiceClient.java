package com.supportmind.ai.client;

import com.supportmind.ai.client.AiContract.ChatRequest;
import com.supportmind.ai.client.AiContract.ChatResult;
import com.supportmind.ai.client.AiContract.Classification;
import com.supportmind.ai.client.AiContract.EmbedRequest;
import com.supportmind.ai.client.AiContract.EmbedResult;
import com.supportmind.ai.client.AiContract.SearchRequest;
import com.supportmind.ai.client.AiContract.SearchResult;
import com.supportmind.ai.client.AiContract.Sentiment;
import com.supportmind.ai.client.AiContract.SuggestRequest;
import com.supportmind.ai.client.AiContract.Suggestion;
import com.supportmind.ai.client.AiContract.SummarizeRequest;
import com.supportmind.ai.client.AiContract.Summary;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Único punto de acceso al servicio de IA. Los controllers nunca lo llaman directamente: siempre a
 * través de un servicio (Controller -> Service -> AiServiceClient -> FastAPI).
 * <p>
 * Todas las operaciones pueden lanzar {@link AiServiceUnavailableException} (caído, timeout,
 * circuito abierto) o {@link AiServiceRejectedException} (petición rechazada).
 */
public interface AiServiceClient {

    Classification classify(String message);

    Sentiment sentiment(String message);

    /** Respuesta RAG en streaming: {@code onDelta} recibe cada fragmento a medida que llega. */
    ChatResult chat(ChatRequest request, Consumer<String> onDelta);

    Summary summarize(SummarizeRequest request);

    Suggestion suggest(SuggestRequest request);

    EmbedResult embed(EmbedRequest request);

    void deleteDocument(UUID organizationId, UUID documentId);

    SearchResult search(SearchRequest request);
}
