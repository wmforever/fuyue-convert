package com.fuyue.formatconverter.web;

import com.fuyue.formatconverter.task.ConversionTaskService;
import com.fuyue.formatconverter.task.DownloadArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskControllerTest {
    @TempDir Path tempDir;

    @Test void downloadDisablesCachingAndContentSniffing() throws Exception {
        Path result = tempDir.resolve("result.pdf");
        Files.writeString(result, "%PDF-1.7\n%%EOF");
        ConversionTaskService tasks = mock(ConversionTaskService.class);
        when(tasks.download("task-1")).thenReturn(new DownloadArtifact(result, "结果.pdf", "application/pdf"));

        ResponseEntity<FileSystemResource> response = new TaskController(tasks).download("task-1");

        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("private, no-store, max-age=0");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(Files.size(result));
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
    }
}
