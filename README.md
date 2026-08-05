# OFD 转可编辑 Word

基于 Java 17、Spring Boot、OFDRW、Apache POI 和 Vue 3 的内网 OFD 转 DOCX 服务。主转换链路直接解析 OFD 的文字、Path 和图片，不经过 PDF，也不会把整页 OFD 作为图片写入 Word。

## 当前版本能力

- 浏览器单个/批量上传 `.ofd`；
- 文件结构、安全解压、Zip Slip、压缩炸弹和危险 XML 防护；
- 使用 OFDRW 2.3.9 按源顺序解析全部页面的文字、样式、文字 CTM/HScale、Path、普通图片和数字签章外观；
- 使用混合语义渲染：普通文字生成为可连续编辑的 Word 段落，旋转、斜切或相互重叠的复杂版头才使用少量定位文本框；
- 同尺寸页面使用普通分页，仅在纸张尺寸或横竖方向变化时建立新分节，保留源页数并避免分节符产生空白页；
- 把规则有线表格转换为正文中的真实 Word 表格，不因局部表格改变整份文档的排版模式；
- 支持横向、纵向合并模型及多行单元格；
- 异步进度、警告、失败隔离、DOCX/ZIP 下载、任务删除和 TTL 清理；
- 服务重启后遗留转换任务标记为失败，不误报正在转换；
- 普通 JAR 和 Docker 两种部署方式。

多页 OFD 会完整转换，不再截断第一页。扫描页只返回 `OCR_REQUIRED`，不会调用云 OCR 或伪造文字。为满足安全要求，页数仍受 `ofd2word.max-pages` 配置限制，默认 500 页；在允许范围内要么全部成功，要么明确失败，不会静默少页。

标准位图签章及签章内嵌 OFD 外观会作为可移动图片保留；遇到厂商私有、加密或损坏的签章外观时会给出明确警告，而不会让整份文档转换失败。任意复杂曲线、特殊字体替代以及依赖厂商私有扩展的效果仍可能存在兼容差异。

## 构建

构建机需要 Maven 3.9+ 和 JDK 17。前端构建所需的 Node/npm 由 Maven 插件下载到 `web-api/target`，不会提交到源码。

```bash
mvn clean verify
```

产物：

```text
web-api/target/web-api-0.1.0-SNAPSHOT.jar
```

## 运行

```bash
java -jar web-api/target/web-api-0.1.0-SNAPSHOT.jar
```

访问：<http://127.0.0.1:8080>

生产部署可复制 JAR、`deploy/application.yml.example` 和三个管理脚本到同一目录，然后执行：

```bash
./start.sh
./status.sh
./stop.sh
```

外部配置：

```bash
java -jar app.jar --spring.config.additional-location=./application.yml
```

## 模块

- `layout-model`：与库无关的页面、文字、线、段落、表格/行/单元格和警告模型。
- `ofd-parser`：安全解压、`OfdParser`/`OcrEngine` SPI 和 OFDRW 适配器。
- `table-recognizer`：线段归一化、网格、合并单元格和文字分配。
- `docx-renderer`：POI/OOXML 页面、段落、真实表格和图片生成。
- `task-service`：异步状态机、批量转换、ZIP、清理和重启恢复。
- `web-api`：Spring Boot REST API 和打包后的 Vue 3 前端。

详细设计、接口和测试结果见 `docs/`。
