package com.bankforecast.attachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalAttachmentStorage implements AttachmentStorage {
  private final Path root;

  public LocalAttachmentStorage(@Value("${bank-forecast.attachment.storage-root:./data/attachments}") String storageRoot) {
    this.root = Paths.get(storageRoot).toAbsolutePath().normalize();
  }

  @Override
  public String put(String objectKey, byte[] content) throws IOException {
    Path target = resolve(objectKey);
    Files.createDirectories(target.getParent());
    Files.write(target, content);
    return objectKey;
  }

  @Override
  public byte[] get(String objectKey) throws IOException {
    return Files.readAllBytes(resolve(objectKey));
  }

  @Override
  public void delete(String objectKey) throws IOException {
    Files.deleteIfExists(resolve(objectKey));
  }

  private Path resolve(String objectKey) {
    Path target = root.resolve(objectKey).normalize();
    if (!target.startsWith(root)) throw new IllegalArgumentException("附件对象键无效");
    return target;
  }
}
