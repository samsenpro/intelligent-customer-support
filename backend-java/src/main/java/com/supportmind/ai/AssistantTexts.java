package com.supportmind.ai;

import com.supportmind.conversation.HandoffReason;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Textos que el sistema muestra al cliente cuando la decisión la toma el backend (derivación,
 * IA no disponible), en español o inglés según el idioma del cliente.
 */
public final class AssistantTexts {

    private static final Pattern SPANISH = Pattern.compile(
            "[ñ¿¡áéíóú]|\\b(el|la|los|las|de|que|mi|por|para|con|quiero|hola|gracias|pedido|cuando|donde)\\b");
    private static final Pattern ENGLISH = Pattern.compile(
            "\\b(the|my|is|are|what|where|when|how|want|order|please|hello|thanks|can)\\b");

    private AssistantTexts() {
    }

    /** Idioma aproximado del cliente (es por defecto). */
    public static String language(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        long es = SPANISH.matcher(lower).results().count();
        long en = ENGLISH.matcher(lower).results().count();
        return en > es ? "en" : "es";
    }

    public static String handoff(String language) {
        return "en".equals(language)
                ? "I'm connecting you with an agent from our team, who will continue the conversation shortly."
                : "Te comunico con un agente de nuestro equipo, que continuará la conversación en breve.";
    }

    public static String aiUnavailable(String language) {
        return "en".equals(language)
                ? "AI temporarily unavailable. A human agent will continue the conversation shortly."
                : "El asistente de IA no está disponible temporalmente. Un agente humano continuará la conversación en breve.";
    }

    public static String handoffTicketDescription(String language, HandoffReason reason, String customerMessage) {
        return "en".equals(language)
                ? "Conversation handed off to a human agent (" + reason + ").\n\nLast customer message:\n" + customerMessage
                : "Conversación derivada a un agente humano (" + reason + ").\n\nÚltimo mensaje del cliente:\n"
                + customerMessage;
    }
}
