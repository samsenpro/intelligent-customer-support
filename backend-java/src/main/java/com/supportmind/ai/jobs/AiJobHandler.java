package com.supportmind.ai.jobs;

import java.util.Set;

/** Procesa los jobs de uno o varios tipos. */
public interface AiJobHandler {

    Set<AiJobType> handles();

    void handle(AiJob job);
}
