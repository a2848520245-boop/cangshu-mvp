package com.cangshu.mvp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangshu.mvp.domain.ResourceItem;
import com.cangshu.mvp.mapper.ResourceItemMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ResourceService {

    private static final Logger log = LoggerFactory.getLogger(ResourceService.class);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final int MAX_NAME_LENGTH = 200;
    private static final long MAX_PAGE_SIZE = 100L;

    private final ResourceItemMapper mapper;
    private final Path storageRoot;

    public ResourceService(ResourceItemMapper mapper, @Value("${cangshu.storage.root}") String storageRoot) {
        this.mapper = mapper;
        this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    /** 数据目录随时可能被整体删除，启动时确保存储目录存在。 */
    @PostConstruct
    void prepareStorage() throws IOException {
        Files.createDirectories(storageRoot);
        log.info("文件存储目录：{}", storageRoot);
    }

    public ResourceItem store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传内容为空");
        }
        String originalName = normalizeName(file.getOriginalFilename());
        MessageDigest digest = sha256();

        // 先写同目录临时文件，成功后原子改名：半截文件不会进入正式目录
        Path temp = Files.createTempFile(storageRoot, ".upload-", ".tmp");
        long size;
        try (InputStream in = new DigestInputStream(file.getInputStream(), digest);
             OutputStream out = Files.newOutputStream(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            size = in.transferTo(out);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        } catch (RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        String storageName = UUID.randomUUID().toString().replace("-", "");
        Path target = storageRoot.resolve(storageName);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }

        ResourceItem item = new ResourceItem();
        item.setOriginalName(originalName);
        item.setContentType(contentType(file.getContentType()));
        item.setSizeBytes(size);
        item.setSha256(HexFormat.of().formatHex(digest.digest()));
        item.setStorageName(storageName);
        item.setCreatedAt(System.currentTimeMillis());
        try {
            mapper.insert(item);
        } catch (RuntimeException e) {
            Files.deleteIfExists(target); // 落库失败不留孤儿文件
            throw e;
        }
        return item;
    }

    public Page<ResourceItem> page(long page, long size) {
        long current = Math.max(page, 1L);
        long limit = Math.min(Math.max(size, 1L), MAX_PAGE_SIZE);
        Page<ResourceItem> request = new Page<>(current, limit);
        return mapper.selectPage(request, new LambdaQueryWrapper<ResourceItem>().orderByDesc(ResourceItem::getId));
    }

    public ResourceItem getRequired(Long id) {
        ResourceItem item = mapper.selectById(id);
        if (item == null) {
            throw new ResourceNotFoundException(id);
        }
        return item;
    }

    public Path resolveExisting(ResourceItem item) {
        Path path = storageRoot.resolve(item.getStorageName());
        if (!Files.isRegularFile(path)) {
            throw new StorageOperationException("磁盘文件缺失：" + path);
        }
        return path;
    }

    /** 先删物理文件再删记录：文件删不掉就报错，避免「库里有、盘上无」。 */
    public boolean delete(Long id) {
        ResourceItem item = mapper.selectById(id);
        if (item == null) {
            return false;
        }
        Path path = storageRoot.resolve(item.getStorageName());
        try {
            if (!Files.deleteIfExists(path)) {
                log.warn("物理文件已不存在，仅清理记录：{}", path);
            }
        } catch (IOException e) {
            throw new StorageOperationException("物理文件删除失败：" + path, e);
        }
        mapper.deleteById(id);
        return true;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static String contentType(String value) {
        return (value == null || value.isBlank()) ? DEFAULT_CONTENT_TYPE : value;
    }

    /** 原始文件名只用于展示与下载响应头：剥掉目录与换行，避免头部注入与路径穿越。 */
    private static String normalizeName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "unnamed";
        }
        String name = rawName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            return "unnamed";
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(name.length() - MAX_NAME_LENGTH) : name;
    }
}
