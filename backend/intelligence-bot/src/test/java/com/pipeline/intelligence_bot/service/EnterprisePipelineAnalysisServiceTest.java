package com.pipeline.intelligence_bot.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnterprisePipelineAnalysisServiceTest {

    @Test
    void fallbackAnalysis_prefersCompilerErrorsOverDependencyWarnings() throws Exception {
        EnterprisePipelineAnalysisService service =
                new EnterprisePipelineAnalysisService(null, null, null, null);

        String log = """
                [WARNING] 'dependencies.dependency.(groupId:artifactId:type:classifier)' must be unique: org.projectlombok:lombok:jar -> version 1.18.32 vs (?) @ line 146, column 21
                [INFO] Compiling 179 source files with javac [debug parameters release 17] to target/classes
                [ERROR] COMPILATION ERROR :
                [ERROR] /home/gitlab-runner/builds/x/Backend/src/main/java/com/cyhub/backend/controller/admin/AdminChatController.java:[3,35] cannot find symbol
                  symbol:   class AdminConversationSummary
                  location: package com.cyhub.backend.dto.admin
                [ERROR] /home/gitlab-runner/builds/x/Backend/src/main/java/com/cyhub/backend/controller/admin/AdminChatController.java:[4,35] cannot find symbol
                  symbol:   class AdminReplyRequest
                  location: package com.cyhub.backend.dto.admin
                """.trim();

        Method method = EnterprisePipelineAnalysisService.class.getDeclaredMethod("fallbackAnalysis", String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(service, log);

        assertEquals("Code Compilation Failure", result.get("category"));
        assertEquals("Code Compilation Failure", result.get("failureType"));
        assertTrue(String.valueOf(result.get("errorMessage")).contains("cannot find symbol"));
        assertTrue(String.valueOf(result.get("fixRecommendation")).contains("compiler error"));
    }
}
