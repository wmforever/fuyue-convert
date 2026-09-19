package com.fuyue.formatconverter.web;

import com.fuyue.formatconverter.task.ConversionTaskService;
import com.fuyue.formatconverter.task.DownloadArtifact;
import com.fuyue.formatconverter.task.DownloadLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskControllerTest {
    @TempDir Path tempDir;

    @Test void downloadDisablesCachingAndContentSniffing() throws Exception {
        Path result = tempDir.resolve("result.pdf");
        Files.writeString(result, "%PDF-1.7\n%%EOF");
        ConversionTaskService tasks = mock(ConversionTaskService.class);
        AtomicBoolean released = new AtomicBoolean();
        DownloadArtifact artifact = new DownloadArtifact(result, "结果.pdf", "application/pdf");
        when(tasks.acquireDownload("task-1")).thenReturn(new DownloadLease(artifact, () -> released.set(true)));

        ResponseEntity<StreamingResponseBody> response = new TaskController(tasks).download("task-1");

        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("private, no-store, max-age=0");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(Files.size(result));
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(released).isFalse();
        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        response.getBody().writeTo(streamed);
        assertThat(streamed.toByteArray()).isEqualTo(Files.readAllBytes(result));
        assertThat(released).isTrue();
    }

    @Test void downloadHeadChecksAvailabilityWithoutHoldingALease() throws Exception {
        Path result = tempDir.resolve("head.pdf");
        Files.writeString(result, "%PDF-1.7\n%%EOF");
        ConversionTaskService tasks = mock(ConversionTaskService.class);
        AtomicBoolean released = new AtomicBoolean();
        DownloadArtifact artifact = new DownloadArtifact(result, "结果.pdf", "application/pdf");
        when(tasks.acquireDownload("task-1")).thenReturn(new DownloadLease(artifact, () -> released.set(true)));

        ResponseEntity<Void> response = new TaskController(tasks).downloadHead("task-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentLength()).isEqualTo(Files.size(result));
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment");
        assertThat(released).isTrue();
    }

    @Test void springMvcUsesExplicitHeadAndStreamsGetAsynchronously() throws Exception {
        Path result = tempDir.resolve("mvc.pdf");
        byte[] bytes = "%PDF-1.7\nstreamed\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(result, bytes);
        ConversionTaskService tasks = mock(ConversionTaskService.class);
        DownloadArtifact artifact = new DownloadArtifact(result, "结果.pdf", "application/pdf");
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        when(tasks.acquireDownload(anyString())).thenAnswer(_invocation -> {
            acquired.incrementAndGet();
            return new DownloadLease(artifact, released::incrementAndGet);
        });
        var mvc = MockMvcBuilders.standaloneSetup(new TaskController(tasks)).build();

        MvcResult headResult = mvc.perform(head("/api/tasks/task-1/download"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(headResult.getRequest().isAsyncStarted()).isFalse();
        assertThat(acquired).hasValue(1);
        assertThat(released).hasValue(1);

        MvcResult getResult = mvc.perform(get("/api/tasks/task-1/download"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(getResult))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
        assertThat(acquired).hasValue(2);
        assertThat(released).hasValue(2);
    }
}
