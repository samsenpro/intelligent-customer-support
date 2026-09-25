package com.supportmind.ai.jobs;

import com.supportmind.ai.AiReplyService;
import com.supportmind.ai.MessageAnalysisService;
import com.supportmind.ai.SummaryService;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Jobs de conversación: análisis de mensajes, respuestas de la IA y resúmenes. */
@Component
public class ConversationJobHandler implements AiJobHandler {

    private final MessageAnalysisService analysisService;
    private final AiReplyService replyService;
    private final SummaryService summaryService;

    public ConversationJobHandler(MessageAnalysisService analysisService, AiReplyService replyService,
                                  SummaryService summaryService) {
        this.analysisService = analysisService;
        this.replyService = replyService;
        this.summaryService = summaryService;
    }

    @Override
    public Set<AiJobType> handles() {
        return Set.of(AiJobType.ANALYZE_MESSAGE, AiJobType.AI_REPLY, AiJobType.SUMMARIZE_CONVERSATION);
    }

    @Override
    public void handle(AiJob job) {
        switch (job.type()) {
            case ANALYZE_MESSAGE -> analysisService.process(job.entityId());
            case AI_REPLY -> replyService.replyOnDemand(job.entityId());
            case SUMMARIZE_CONVERSATION -> summaryService.summarize(job.entityId());
            default -> throw new IllegalArgumentException("Unsupported job type " + job.type());
        }
    }
}
