package com.cangshu.mvp.web;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangshu.mvp.domain.ResourceItem;
import com.cangshu.mvp.service.ResourceNotFoundException;
import com.cangshu.mvp.service.ResourceService;
import com.cangshu.mvp.web.dto.PageResponse;
import com.cangshu.mvp.web.dto.ResourceResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceService service;

    public ResourceController(ResourceService service) {
        this.service = service;
    }

    /** 上传：multipart 字段 file，流式落盘并计算 SHA-256。 */
    @PostMapping
    public ResponseEntity<ResourceResponse> upload(@RequestParam("file") MultipartFile file) throws IOException {
        ResourceItem item = service.store(file);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/resources/" + item.getId())
                .body(ResourceResponse.from(item));
    }

    /** 分页列表：默认 1/10，size 上限 100。 */
    @GetMapping
    public PageResponse<ResourceResponse> list(@RequestParam(name = "page", defaultValue = "1") long page,
                                               @RequestParam(name = "size", defaultValue = "10") long size) {
        Page<ResourceItem> result = service.page(page, size);
        List<ResourceResponse> items = result.getRecords().stream().map(ResourceResponse::from).toList();
        return new PageResponse<>(items, result.getCurrent(), result.getSize(), result.getTotal());
    }

    @GetMapping("/{id}")
    public ResourceResponse detail(@PathVariable("id") Long id) {
        return ResourceResponse.from(service.getRequired(id));
    }

    /** 下载：Content-Disposition 用 RFC 5987 编码中文名，附 X-Content-SHA256 便于校验。 */
    @GetMapping("/{id}/content")
    public ResponseEntity<FileSystemResource> content(@PathVariable("id") Long id) {
        ResourceItem item = service.getRequired(id);
        Path path = service.resolveExisting(item);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(resolveMediaType(item.getContentType()));
        headers.setContentLength(item.getSizeBytes());
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(item.getOriginalName(), StandardCharsets.UTF_8)
                .build());
        headers.set("X-Content-SHA256", item.getSha256());
        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(path));
    }

    /** 删除：记录 + 物理文件一起删。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") Long id) {
        if (!service.delete(id)) {
            throw new ResourceNotFoundException(id);
        }
    }

    private static MediaType resolveMediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
