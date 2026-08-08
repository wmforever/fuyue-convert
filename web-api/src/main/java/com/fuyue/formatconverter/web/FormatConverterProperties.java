package com.fuyue.formatconverter.web;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("format-converter")
public class FormatConverterProperties {
    private Path dataRoot = Path.of("./data");
    private int concurrency = 2;
    private int queueCapacity = 20;
    private Duration timeout = Duration.ofMinutes(5);
    private Duration resultTtl = Duration.ofHours(24);
    private long maxFileSize = 50L * 1024 * 1024;
    private long maxExpandedSize = 200L * 1024 * 1024;
    private long maxEntrySize = 40L * 1024 * 1024;
    private int maxEntries = 10_000;
    private double maxCompressionRatio = 100d;
    private int maxPages = 500;
    private boolean officeEnabled = true;
    private String officeBinary = "";
    private Duration officeTimeout = Duration.ofMinutes(2);
    private String apiToken = "";

    public ParseLimits parseLimits() { return new ParseLimits(maxFileSize, maxExpandedSize, maxEntrySize, maxEntries, maxCompressionRatio, maxPages); }
    public Path getDataRoot() { return dataRoot; }
    public void setDataRoot(Path dataRoot) { this.dataRoot = dataRoot; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public Duration getResultTtl() { return resultTtl; }
    public void setResultTtl(Duration resultTtl) { this.resultTtl = resultTtl; }
    public long getMaxFileSize() { return maxFileSize; }
    public void setMaxFileSize(long maxFileSize) { this.maxFileSize = maxFileSize; }
    public long getMaxExpandedSize() { return maxExpandedSize; }
    public void setMaxExpandedSize(long maxExpandedSize) { this.maxExpandedSize = maxExpandedSize; }
    public long getMaxEntrySize() { return maxEntrySize; }
    public void setMaxEntrySize(long maxEntrySize) { this.maxEntrySize = maxEntrySize; }
    public int getMaxEntries() { return maxEntries; }
    public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
    public double getMaxCompressionRatio() { return maxCompressionRatio; }
    public void setMaxCompressionRatio(double maxCompressionRatio) { this.maxCompressionRatio = maxCompressionRatio; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
    public boolean isOfficeEnabled() { return officeEnabled; }
    public void setOfficeEnabled(boolean officeEnabled) { this.officeEnabled = officeEnabled; }
    public String getOfficeBinary() { return officeBinary; }
    public void setOfficeBinary(String officeBinary) { this.officeBinary = officeBinary; }
    public Duration getOfficeTimeout() { return officeTimeout; }
    public void setOfficeTimeout(Duration officeTimeout) { this.officeTimeout = officeTimeout; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }
}
