package com.pipeline.intelligence_bot.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureAnalysisServiceTest {

    @Test
    void analyzeFailure_prefersCompilationErrorsOverDependencyWarnings() {
        FailureAnalysisService service = new FailureAnalysisService();

        String log = """
                [WARNING] 'dependencies.dependency.(groupId:artifactId:type:classifier)' must be unique: org.projectlombok:lombok:jar -> version 1.18.32 vs (?) @ line 146, column 21
                [ERROR] COMPILATION ERROR :
                [ERROR] /home/gitlab-runner/builds/x/Backend/src/main/java/com/cyhub/backend/controller/admin/AdminChatController.java:[3,35] cannot find symbol
                  symbol:   class AdminConversationSummary
                  location: package com.cyhub.backend.dto.admin
                """.trim();

        Map<String, String> result = service.analyzeFailure(log);

        assertEquals("Code Compilation Failure", result.get("failureType"));
        assertTrue(result.get("rootCause").contains("missing symbol"));
        assertTrue(result.get("fixRecommendation").contains("compilation errors"));
    }
}
