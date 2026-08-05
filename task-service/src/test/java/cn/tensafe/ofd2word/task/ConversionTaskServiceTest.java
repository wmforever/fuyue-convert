package cn.tensafe.ofd2word.task;

import cn.tensafe.ofd2word.docx.PoiDocxRenderer;
import cn.tensafe.ofd2word.parser.*;
import cn.tensafe.ofd2word.table.PageLayoutAnalyzer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Paragraph;
import org.ofdrw.layout.element.Position;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversionTaskServiceTest {
    @TempDir Path temp;

    @Test void invalidOfdFailsWithoutStoppingService() throws Exception {
        TaskServiceConfig config = new TaskServiceConfig(temp, 1, 2, Duration.ofSeconds(5), Duration.ofHours(1), ParseLimits.defaults());
        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            byte[] invalid = "not-an-ofd".getBytes();
            TaskSnapshot created = service.createTask(List.of(new UploadPayload("bad.ofd", invalid.length,
                    () -> new ByteArrayInputStream(invalid))));
            TaskSnapshot finished = await(service, created.taskId());
            assertEquals(TaskStatus.FAILED, finished.status());
            assertFalse(finished.downloadReady());
            service.delete(created.taskId());
            assertThrows(TaskNotFoundException.class, () -> service.get(created.taskId()));
        }
    }

    @Test void convertsAllPagesIntoOneEditableDocx() throws Exception {
        Path source = temp.resolve("all-pages.ofd");
        try (OFDDoc document = new OFDDoc(source)) {
            document.addVPage(page(210, 297, "第一页可编辑正文"));
            document.addVPage(page(297, 210, "第二页可编辑正文"));
            document.addVPage(page(148, 210, "第三页可编辑正文"));
        }
        byte[] ofd = Files.readAllBytes(source);
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("tasks-data"), 1, 2,
                Duration.ofSeconds(30), Duration.ofHours(1), ParseLimits.defaults());

        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            TaskSnapshot created = service.createTask(List.of(new UploadPayload("all-pages.ofd", ofd.length,
                    () -> new ByteArrayInputStream(ofd))));
            TaskSnapshot finished = await(service, created.taskId());
            assertEquals(TaskStatus.SUCCESS, finished.status(), finished.errorMessage());
            assertTrue(finished.downloadReady());
            assertEquals(3, finished.files().get(0).pageCount());

            try (XWPFDocument word = new XWPFDocument(Files.newInputStream(service.download(created.taskId()).path()))) {
                String text = word.getParagraphs().stream().map(p -> p.getText()).reduce("", String::concat);
                assertTrue(text.contains("第一页可编辑正文"));
                assertTrue(text.contains("第二页可编辑正文"));
                assertTrue(text.contains("第三页可编辑正文"));
                long sectionBreaks = word.getParagraphs().stream()
                        .filter(p -> p.getCTP().isSetPPr() && p.getCTP().getPPr().isSetSectPr())
                        .count();
                assertEquals(2, sectionBreaks);
            }
        }
    }

    private VirtualPage page(double width, double height, String text) {
        Paragraph paragraph = new Paragraph(text, 5d);
        paragraph.setPosition(Position.Absolute).setBox(15d, 15d, width - 30d, 15d);
        return new VirtualPage(width, height).add(paragraph);
    }

    private TaskSnapshot await(ConversionTaskService service, String id) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            TaskSnapshot current = service.get(id);
            if (current.status() == TaskStatus.SUCCESS || current.status() == TaskStatus.FAILED) return current;
            Thread.sleep(20);
        }
        fail("task did not finish");
        return null;
    }
}
